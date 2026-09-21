package com.plantidentify.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.plantidentify.data.ai.PlantAnalysis
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.TolerantJsonParser
import com.plantidentify.data.draft.CaptureDraft
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.draft.DraftImage
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.domain.cleaning.MergeField
import com.plantidentify.domain.cleaning.MergePlan
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.MergeLevel
import com.plantidentify.domain.model.MergeSuggestion
import com.plantidentify.domain.model.PlantFilters
import com.plantidentify.domain.model.PlantStatistics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 植物档案仓库 —— UI 层访问数据的唯一入口。
 *
 * 架构分层：UI → ViewModel → UseCase → Repository → (Room / File / AI)
 *
 * ## 为什么把草稿持有在这里
 *
 * 「保存识别结果」天然是跨存储介质的动作：三张表写进 Room，草稿存在 DataStore，
 * 而草稿在保存成功后必须清掉。如果由 ViewModel 分两步做，中间会存在不一致窗口
 * （库里有档案、草稿还在，用户再进「添加植物」会看到上一轮的照片）。
 *
 * 放进仓库后，落库与清草稿挨在一起，顺序与时机的判断只有一处。
 *
 * ## 图片文件的处理
 *
 * 原图在 Phase 2 就已写入 `filesDir`，本阶段的落库**只记录相对路径**，
 * 不复制、不移动文件 —— 复制会让存储凭空翻倍，而草稿与档案本来就指向同一批文件。
 *
 * 反过来，**删除时必须显式删文件**：Room 的级联只清理数据库行，
 * 文件不删就会永远留在磁盘上。这也是本仓库要持有 [ImageStore] 的原因。
 */
class PlantRepository(
    private val database: PlantIdentifyDatabase,
    private val draftStore: CaptureDraftStore,
    private val imageStore: ImageStore,
) {

    private val plantRecordDao = database.plantRecordDao()
    private val observationDao = database.plantObservationDao()
    private val imageDao = database.observationImageDao()
    private val cleaningIssueDao = database.cleaningIssueDao()

    /**
     * 观察统计。
     *
     * 注意：五项统计的分母不同（见 [PlantStatistics] 注释），
     * 这里用 combine 合并为一条 Flow，UI 侧保证三者始终同步更新。
     */
    fun observeStatistics(): Flow<PlantStatistics> = combine(
        plantRecordDao.observeDistinctPlantCount(),
        observationDao.observeObservationCount(),
        imageDao.observeImageCount(),
        plantRecordDao.observeFamilyCount(),
        plantRecordDao.observeGenusCount(),
    ) { distinctPlants, observationCount, imageCount, familyCount, genusCount ->
        PlantStatistics(
            distinctPlants = distinctPlants,
            observationCount = observationCount,
            imageCount = imageCount,
            familyCount = familyCount,
            genusCount = genusCount,
        )
    }

    /** 植物详情（含观察与图片），供详情页订阅 */
    fun observePlantDetail(plantId: Long) = plantRecordDao.observePlantDetail(plantId)

    // ---------------- 保存识别结果 ----------------

    /**
     * 把一次识别保存为**新的植物档案**。
     *
     * 事务内完成三件事：建 PlantRecord → 建 PlantObservation → 写入全部图片。
     * 任何一步失败都整体回滚，不会留下「有档案没图片」这种半截数据。
     *
     * ## 为什么不做重复植物归并
     *
     * 归并需要「匹配 → 提示 → 用户确认」的完整交互，属于 Phase 5。
     * 半成品地做静默复用风险更大：模型一旦识别错了物种名，
     * 静默挂到另一条档案上，用户根本不会发现自己的数据被写歪了。
     * 因此这里**总是新建**，重复由 Phase 5 的归并流程处理。
     *
     * @return 新档案的 plantId（供跳转详情页）
     */
    suspend fun saveAsNewPlant(
        result: RecognitionResult,
        rawAiJson: String,
    ): Result<Long> = runCatching {
        val draft = draftStore.current()
        require(!draft.isEmpty) { "没有可保存的照片" }

        val now = System.currentTimeMillis()

        val plantId = database.withTransaction {
            val plantId = plantRecordDao.insert(
                PlantRecordEntity(
                    name = result.name,
                    latinName = result.latinName,
                    family = result.family,
                    genus = result.genus,
                    category = result.category,
                    confidence = result.confidence,
                    // 文字分析尚未开始 —— 由调用方随后单独触发生成
                    analysisStatus = AnalysisStatus.NOT_REQUESTED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val observationId = insertObservation(
                plantId = plantId,
                timestamp = now,
                isPrimary = true,
                rawAiJson = rawAiJson,
                draft = draft,
            )

            insertImages(observationId, draft.images)
            plantId
        }

        // 只在事务成功后清草稿。
        // 注意用 clear() 而不是 discardAll() —— 后者会删除原图文件，
        // 而那些文件此刻已经属于正式档案了。
        draftStore.clear()

        plantId
    }

    /**
     * 把本次识别追加为某个已有植物的**新观察**。
     *
     * 对应规格书第十四点五节：用户选择「添加到已有植物」时，
     * 原有观察记录必须完整保留，这里只做追加。
     *
     * 由 Phase 5 的归并流程调用；Phase 4 尚未接入 UI。
     */
    suspend fun appendObservation(
        plantId: Long,
        result: RecognitionResult,
        rawAiJson: String,
    ): Result<Long> = runCatching {
        val draft = draftStore.current()
        require(!draft.isEmpty) { "没有可保存的照片" }

        val existing = plantRecordDao.getById(plantId)
            ?: error("目标植物档案不存在")

        val now = System.currentTimeMillis()

        val observationId = database.withTransaction {
            val observationId = insertObservation(
                plantId = plantId,
                timestamp = now,
                // 新观察不抢占代表观察 —— 封面由用户或 Phase 5 决定
                isPrimary = false,
                rawAiJson = rawAiJson,
                draft = draft,
            )
            insertImages(observationId, draft.images)

            // 档案上的置信度跟随最近一次识别更新，其余文字内容保持不变
            plantRecordDao.update(
                existing.copy(
                    confidence = result.confidence,
                    updatedAt = now,
                ),
            )

            observationId
        }

        draftStore.clear()
        observationId
    }

    // ---------------- 列表与搜索 ----------------

    /** 单条观察（含图片），用于「只看这一次观察」的场景 */
    fun observeObservation(observationId: Long) = observationDao.observeWithImages(observationId)

    /** 档案名称 —— 观察记录页要显示「这是哪株植物的观察」 */
    suspend fun plantNameOf(plantId: Long): String =
        plantRecordDao.getById(plantId)?.name.orEmpty()

    /**
     * 地点筛选的候选：库里真实出现过的地名。
     *
     * 不写死行政区划表 —— 用户去过哪里，候选里才有哪里。
     * 地点为空（拒绝授权/关掉位置）时这里是空列表，界面自然不显示这一组筛选。
     */
    fun observeLocationNames(): Flow<List<String>> = observationDao.observeLocationNames()

    /** 全部植物卡片（按最近更新排序） */
    fun observePlantCards(): Flow<List<PlantCardRow>> = plantRecordDao.observePlantCards()

    /**
     * 一次性读出全部档案（含观察与图片），供导出使用。
     *
     * 与 [observePlantCards] 的区别：那个是列表页要的轻量投影（几个字段 + 计数），
     * 这个是导出要的**全字段 + 全部图片行**。分成两个查询是有意的 ——
     * 让列表页去加载所有百科正文与图片路径纯属浪费。
     */
    suspend fun loadArchive(): List<PlantWithObservationsAndImages> =
        plantRecordDao.getAllWithObservationsAndImages()

    /**
     * 搜索与筛选。
     *
     * 用空值表示「该条件不生效」，调用方不必拼 SQL —— 一条查询覆盖全部组合。
     */
    fun searchPlantCards(filters: PlantFilters): Flow<List<PlantCardRow>> =
        plantRecordDao.searchPlantCards(
            keyword = filters.keyword.trim(),
            family = filters.family.trim(),
            genus = filters.genus.trim(),
            fromDate = filters.fromDate ?: 0L,
            toDate = filters.toDate ?: 0L,
            place = filters.place.trim(),
        )

    // ---------------- 文字分析 ----------------

    /**
     * 写入文字分析结果。
     *
     * @param analysis 为 null 时只更新状态（用于标记失败）
     */
    suspend fun updateAnalysis(
        plantId: Long,
        status: AnalysisStatus,
        analysis: PlantAnalysis? = null,
    ): Result<Unit> = runCatching {
        val existing = plantRecordDao.getById(plantId)
            ?: error("植物档案不存在")

        plantRecordDao.update(
            existing.copy(
                // 别名与百科字段一样，来自文字分析；为空时保留原值，
                // 避免一次「模型没给出别名」的重新生成把用户手填的俗称抹掉
                commonNames = analysis?.commonNames ?: existing.commonNames,
                description = analysis?.description ?: existing.description,
                morphologicalFeatures = analysis?.morphologicalFeatures
                    ?: existing.morphologicalFeatures,
                growthHabits = analysis?.growthHabits ?: existing.growthHabits,
                floweringPeriod = analysis?.floweringPeriod ?: existing.floweringPeriod,
                fruitingPeriod = analysis?.fruitingPeriod ?: existing.fruitingPeriod,
                landscapeUses = analysis?.landscapeUses ?: existing.landscapeUses,
                careAdvice = analysis?.careAdvice ?: existing.careAdvice,
                pestControl = analysis?.pestControl ?: existing.pestControl,
                analysisStatus = status,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Unit
    }

    /** 标记文字分析开始（跨页面返回时可据此恢复「生成中」状态） */
    suspend fun markAnalysisPending(plantId: Long): Result<Unit> =
        updateAnalysis(plantId, AnalysisStatus.PENDING)

    // ---------------- 重复植物归并（规格书第十四点五节）----------------

    /**
     * 查找本次识别结果可能对应的已有档案。
     *
     * 匹配优先级严格照规格书：
     * 1. 拉丁学名 —— 生物学上唯一，证据最强
     * 2. 中文名 + 科 + 属 —— 三项全同，基本可以确定
     * 3. 中文名 + 科 —— 证据中等
     * 4. 名称相近 —— 证据最弱
     *
     * ## 为什么「只有中文名相同」不算高度匹配
     *
     * 规格书明确写了「不要仅根据中文名称进行绝对判断」。中文俗名跨科同名
     * 并不罕见（「芙蓉」既指木芙蓉也指荷花），所以仅凭名称命中的一律降为
     * [MergeLevel.POSSIBLE]，由用户决定。
     *
     * ## 这个方法不做任何写入
     *
     * 它只回答「有没有可能是同一株」。是否归并完全由用户确认 ——
     * 规格书要求系统在任何情况下都不得自动合并两条已有 PlantRecord。
     */
    suspend fun findMergeSuggestion(result: RecognitionResult): MergeSuggestion {
        val latin = result.latinName?.trim().orEmpty()
        val name = result.name.trim()

        // 1. 拉丁学名
        if (latin.isNotEmpty()) {
            plantRecordDao.findByLatinName(latin)?.let { hit ->
                return hit.toSuggestion(
                    level = MergeLevel.EXACT,
                    reason = "拉丁学名相同（$latin）",
                )
            }
        }

        if (name.isEmpty()) return MergeSuggestion.none()

        // 2. 中文名 + 科 + 属
        plantRecordDao.findByNameFamilyGenus(name, result.family, result.genus)?.let { hit ->
            return hit.toSuggestion(
                level = MergeLevel.EXACT,
                reason = "中文名、科、属都相同",
            )
        }

        // 3. 中文名 + 科
        if (!result.family.isNullOrBlank()) {
            plantRecordDao.findByNameAndFamily(name, result.family)?.let { hit ->
                return hit.toSuggestion(
                    level = MergeLevel.POSSIBLE,
                    reason = "中文名与科相同，属不同或缺失",
                )
            }
        }

        // 4. 名称相近（含被包含关系）。规格书要求此时只能说「可能」
        val fuzzy = plantRecordDao.findFuzzyByName(name)
            .filter { it.name != name }
        fuzzy.firstOrNull()?.let { hit ->
            return hit.toSuggestion(
                level = MergeLevel.POSSIBLE,
                reason = "名称相近：已有「${hit.name}」",
                alternatives = fuzzy.drop(1),
            )
        }

        // 名称完全相同但科属都对不上：仍然只能提示，不能自动合并
        val sameName = plantRecordDao.findFuzzyByName(name).filter { it.name == name }
        sameName.firstOrNull()?.let { hit ->
            return hit.toSuggestion(
                level = MergeLevel.POSSIBLE,
                reason = "已有同名档案，但科属不一致 —— 中文俗名可能与实际物种无关",
                alternatives = sameName.drop(1),
            )
        }

        return MergeSuggestion.none()
    }

    private suspend fun PlantRecordEntity.toSuggestion(
        level: MergeLevel,
        reason: String,
        alternatives: List<PlantRecordEntity> = emptyList(),
    ): MergeSuggestion = MergeSuggestion(
        plant = this,
        level = level,
        reason = reason,
        alternatives = alternatives,
        observationCount = observationDao.countByPlant(id),
    )

    // ---------------- 对已有观察补图并重新识别 ----------------

    /**
     * 读取某条观察现有的照片，用于装进草稿继续补图。
     *
     * 规格书第十四点五节要求补图时「原有照片 + 新增照片」一起参与重新识别，
     * 所以要先能把已有照片放回草稿。
     */
    suspend fun imagesOfObservation(observationId: Long): List<DraftImage> =
        imageDao.getByObservation(observationId).map { DraftImage(it.imagePath, it.role) }

    /**
     * 把本轮识别结果写回**已有观察**，而不是新建一条。
     *
     * 规格书第十四点五节：
     * ```
     * Observation #001
     * ├── 原有照片
     * ├── 新增照片
     * └── 重新进行多图联合识别
     * ```
     * 「此时更新当前 Observation 的识别结果，而不是创建新的 Observation。」
     *
     * 注意与 [appendObservation] 的区别：那个是「这是一株新看到的老植物，
     * 记一次新观察」，这个是「刚才那次观察照片不够，补几张重看」。
     * 两者在产品语义上完全不同，混用会让观察次数虚高。
     */
    suspend fun reanalyzeObservation(
        observationId: Long,
        result: RecognitionResult,
        rawAiJson: String,
    ): Result<Long> = runCatching {
        val draft = draftStore.current()
        require(!draft.isEmpty) { "没有可保存的照片" }

        val observation = observationDao.getById(observationId)
            ?: error("观察记录不存在")

        // 先把旧的图片路径记下来 —— 图片行删掉之后就查不到它们了
        val previousPaths = imageDao.getByObservation(observationId).map { it.imagePath }

        database.withTransaction {
            // 图片行整体重建：草稿里是最终的完整集合（原有 + 新增，可能还删过）
            imageDao.deleteByObservation(observationId)
            insertImages(observationId, draft.images)

            // 地点跟着新照片走：用户可能换了个地方重拍。
            // 但草稿里没有坐标时（关掉了位置、或没授权）保留原值 ——
            // 不能因为这次没取到地点就把上次记好的抹掉。
            val hasNewLocation = draft.latitude != null && draft.longitude != null
            observationDao.update(
                observation.copy(
                    aiResultJson = rawAiJson,
                    latitude = if (hasNewLocation) draft.latitude else observation.latitude,
                    longitude = if (hasNewLocation) draft.longitude else observation.longitude,
                    locationName = if (hasNewLocation) draft.locationName else observation.locationName,
                ),
            )

            // 档案上的置信度跟随最近一次识别
            plantRecordDao.getById(observation.plantId)?.let { plant ->
                plantRecordDao.update(
                    plant.copy(
                        confidence = result.confidence,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        // 被用户移除的旧照片若已无人引用就删文件；
        // 仍在本轮里的（含预置回去的）引用计数不为 0，会被保留
        previousPaths.distinct().forEach { path ->
            if (imageDao.countByPath(path) == 0) {
                imageStore.delete(path)
            }
        }

        draftStore.clear()
        observation.plantId
    }

    // ---------------- 编辑与删除 ----------------
    /**
     * 更新档案的**全部可编辑内容**。
     *
     * ## 为什么不再锁死「AI 结论」
     *
     * 早期版本只开放名称/学名/科/属/类型，理由是「改写 AI 结论会让档案
     * 失去可追溯性」。但实际用下来这条原则是错的：**AI 确实会认错**，
     * 而用户手上就有那株植物 —— 他要的是把错的地方改对，
     * 不是为了保全 AI 的原始输出而被迫留着一个错误的名字。
     *
     * 可追溯性由**观察记录里的 `aiResultJson`** 保证：模型当初返回了什么，
     * 一个字都没动地存在那儿。档案上的字段是「当前认为正确的结论」，
     * 两者的职责本来就不同。
     *
     * ## 不在这里改的东西
     *
     * - `analysisStatus`：由分析流程维护的状态机，让用户改没有意义
     * - `createdAt` / `updatedAt`：前者是事实，后者由本方法刷新
     * - 观察的 `timestamp` / 经纬度 / 地名：那是「某次观察在哪儿」的事实记录，
     *   不是 AI 的判断，改它等于篡改观察日志
     */
    suspend fun updatePlantInfo(
        plantId: Long,
        name: String,
        latinName: String?,
        commonNames: String?,
        family: String?,
        genus: String?,
        category: String?,
        confidence: Double,
        description: String?,
        morphologicalFeatures: String?,
        growthHabits: String?,
        floweringPeriod: String?,
        fruitingPeriod: String?,
        landscapeUses: String?,
        careAdvice: String?,
        pestControl: String?,
        note: String?,
    ): Result<Unit> = runCatching {
        val existing = plantRecordDao.getById(plantId)
            ?: error("植物档案不存在")

        plantRecordDao.update(
            existing.copy(
                name = name.trim(),
                latinName = latinName.clean(),
                commonNames = commonNames.clean(),
                family = family.clean(),
                genus = genus.clean(),
                category = category.clean(),
                // 置信度是 0.0~1.0 的比例值；界面按百分数输入，转换在这里收口
                confidence = confidence.coerceIn(0.0, 1.0),
                description = description.clean(),
                morphologicalFeatures = morphologicalFeatures.clean(),
                growthHabits = growthHabits.clean(),
                floweringPeriod = floweringPeriod.clean(),
                fruitingPeriod = fruitingPeriod.clean(),
                landscapeUses = landscapeUses.clean(),
                careAdvice = careAdvice.clean(),
                pestControl = pestControl.clean(),
                note = note.clean(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 去掉首尾空白；空串与全空白一律存成 null，「空」在库里只有一种表示 */
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 向一条已有观察追加照片。
     *
     * ## 为什么不走「补图重识别」
     *
     * 那条路径会把整个观察的图片行删掉重建，并**强制重跑一次 AI 识别**。
     * 用户想做的可能只是「当时漏传了一张叶子」，为此付一次 API 调用、
     * 外加结论被改写的风险，代价明显不对等。这里只做纯粹的文件 + 行插入。
     *
     * 新的照片落 `role = UNKNOWN`（用户没标部位）与末尾 `sortOrder`，
     * 因此**不会抢走封面** —— 封面的选取规则见 [PlantRecordDao.observePlantCards]。
     */
    suspend fun addImagesToObservation(
        observationId: Long,
        uris: List<Uri>,
    ): Result<Int> = runCatching {
        if (uris.isEmpty()) error("没有选择照片")

        val observation = observationDao.getById(observationId)
            ?: error("观察记录不存在")

        // 先全部落盘，再一次性入库：中途某张读不出来时，
        // 已导入的文件要回收，否则会在 filesDir 里留下没人引用的孤儿图
        val imported = mutableListOf<String>()
        try {
            uris.forEach { uri ->
                imageStore.importFromUri(uri)
                    .onSuccess { imported += it }
            }
            if (imported.isEmpty()) error("所选照片都无法读取")

            var order = imageDao.maxSortOrder(observationId) + 1
            imageDao.insertAll(
                imported.map { path ->
                    ObservationImageEntity(
                        observationId = observationId,
                        imagePath = path,
                        role = ImageRole.UNKNOWN,
                        sortOrder = order++,
                    )
                },
            )
            plantRecordDao.touch(observation.plantId)
            imported.size
        } catch (error: Throwable) {
            imported.forEach { imageStore.delete(it) }
            throw error
        }
    }

    /**
     * 删除一张照片（数据库行 + 磁盘文件）。
     *
     * ## 为什么拒绝删掉最后一张
     *
     * 档案没有照片就变成空壳：列表没有封面、补图重识别无从谈起、
     * 备份恢复后也没法核对。与其让用户走到那一步再解释，
     * 不如在点下去的时候就说清楚。
     *
     * ## 文件为什么要看引用计数
     *
     * 同一个文件可能被多条观察引用（同一批照片保存到两个档案）。
     * 直接删文件会让另一份档案的照片变成空白 —— 行删了、文件还在，
     * 只是浪费空间；行还在、文件没了，就是数据损坏。
     */
    suspend fun deleteObservationImage(imageId: Long): Result<Unit> = runCatching {
        val image = imageDao.getById(imageId) ?: error("照片不存在")
        val observation = observationDao.getById(image.observationId)
            ?: error("观察记录不存在")

        // 「最后一张」按整株植物算，而不是按这次观察 —— 用户看到的是
        // 这株植物还有几张照片，不是这条观察还有几张
        val plantPhotoCount = imageDao.getImagesForPlant(observation.plantId).size
        if (plantPhotoCount <= 1) error("这株植物只剩这一张照片了，删掉就没有封面了")

        imageDao.deleteById(imageId)
        // 先删行再判引用：此时这一行已经不在了，计数为 0 才是真的没人用
        if (imageDao.countByPath(image.imagePath) == 0) {
            imageStore.delete(image.imagePath)
        }
        plantRecordDao.touch(observation.plantId)
        Unit
    }

    /** 更新某次观察的备注 */
    suspend fun updateObservationNote(observationId: Long, note: String?): Result<Unit> =
        runCatching {
            val existing = observationDao.getById(observationId)
                ?: error("观察记录不存在")
            observationDao.update(
                existing.copy(note = note?.trim()?.takeIf { it.isNotEmpty() }),
            )
        }

    /**
     * 删除一条观察，并**显式删除它的图片文件**。
     *
     * Room 的级联只负责数据库行；文件不删就会永远留在 `filesDir` 里成为垃圾。
     * 而且不能简单按「路径是否还被引用」判断 —— 同一文件可能被多个观察共享
     * （测试复用草稿就会造成这种情况），所以要删前先查引用计数。
     */
    suspend fun deleteObservation(observationId: Long): Result<Unit> = runCatching {
        val images = imageDao.getByObservation(observationId)
        // 先记下待删路径，再删数据库行 —— 行删掉之后就查不到路径了
        val candidatePaths = images.map { it.imagePath }

        database.withTransaction {
            // 若删的正好是代表观察，把代表观察交给剩下最新的一条
            val observation = observationDao.getById(observationId)
            imageDao.deleteByObservation(observationId)
            observationDao.deleteById(observationId)

            if (observation != null && observation.isPrimary) {
                observationDao.getByPlant(observation.plantId).firstOrNull()?.let { next ->
                    observationDao.markPrimary(next.id)
                }
            }
        }

        // 事务成功后再删文件；删除失败不影响数据一致性（只是留下垃圾）
        candidatePaths.forEach { path ->
            if (imageDao.countByPath(path) == 0) {
                imageStore.delete(path)
            }
        }
        Unit
    }

    /**
     * 删除整份档案：连带它的全部观察、图片行，以及**图片文件**。
     *
     * 规格书把「删除植物时须显式删除图片文件」列为本 Phase 的主要风险之一 ——
     * 数据库级联不等于文件级联。
     */
    suspend fun deletePlant(plantId: Long): Result<Unit> = runCatching {
        val existing = plantRecordDao.getById(plantId)
            ?: error("植物档案不存在")
        // 只打一个删除标记：行还在、照片还在，回收站里能看能恢复。
        // 真正的物理删除在 [purgePlant] —— 那个操作不可撤销，
        // 必须由用户在回收站点「彻底删除」时明确触发
        plantRecordDao.update(
            existing.copy(deletedAt = System.currentTimeMillis()),
        )
        Unit
    }

    // ---------------- 回收站（Phase 3）----------------

    /** 回收站列表（已删档案，最近删的在最上面） */
    fun observeDeletedPlants(): Flow<List<PlantRecordEntity>> = plantRecordDao.observeDeleted()

    fun observeDeletedCount(): Flow<Int> = plantRecordDao.observeDeletedCount()

    suspend fun loadDeleted(): List<PlantRecordEntity> = plantRecordDao.getDeleted()

    suspend fun countDeleted(): Int = plantRecordDao.countDeleted()

    /**
     * 从回收站恢复。
     *
     * 只清删除标记，不碰任何其它字段 —— 恢复的意义是「当作没删过」，
     * 若顺手更新 `updatedAt`，这株植物会凭空跳到列表最前面。
     */
    suspend fun restorePlant(plantId: Long): Result<Unit> = runCatching {
        plantRecordDao.getById(plantId) ?: error("植物档案不存在")
        plantRecordDao.restoreById(plantId)
        Unit
    }

    /**
     * 彻底删除 —— **不可撤销**。
     *
     * 这是原先 [deletePlant] 的物理删逻辑，位置上从「一个按钮的动作」
     * 降级为「回收站里的二次确认」。文件清理走引用计数：
     * 同一张图可能被多条观察共享，数到 0 才能删。
     */
    suspend fun purgePlant(plantId: Long): Result<Unit> = runCatching {
        val paths = imageDao.getImagesForPlant(plantId).map { it.imagePath }

        database.withTransaction {
            imageDao.getObservationIdsForPlant(plantId).forEach { observationId ->
                imageDao.deleteByObservation(observationId)
            }
            observationDao.deleteByPlant(plantId)
            plantRecordDao.deleteById(plantId)
        }

        // 共享文件要留到最后一起判断，避免前一个观察删掉了后面还要用的文件
        paths.distinct().forEach { path ->
            if (imageDao.countByPath(path) == 0) {
                imageStore.delete(path)
            }
        }
        Unit
    }

    /**
     * 清空回收站。
     *
     * 逐条走 [purgePlant]（而不是一条 SQL 全删）—— 文件清理必须按株做：
     * 一次性删完行之后就再也查不出每株有过哪些照片，磁盘上会留下
     * 永远无人认领的孤儿文件。
     *
     * @return 实际清掉的株数
     */
    suspend fun purgeAllDeleted(): Result<Int> = runCatching {
        val deleted = plantRecordDao.getDeleted()
        deleted.forEach { plant ->
            purgePlant(plant.id).getOrThrow()
        }
        deleted.size
    }

    // ---------------- 合并（Phase 3 步骤 6）----------------

    /**
     * 按合并方案把两株档案合成一株（方案 §7.6）。
     *
     * ## 四步，全在一个事务里
     *
     * 1. 把 `drop` 名下的观察整体改挂到 `keep`（照片跟着观察走，见
     *    [PlantObservationDao.reassignPlant]）
     * 2. 按 [MergePlan] 逐字段写回 `keep`
     * 3. `drop` **软删**（置 `deletedAt`），进回收站
     * 4. 保证 `keep` 有一个代表观察
     *
     * ## 为什么是软删而不是物理删
     *
     * 合并把几十次观察、几十张照片改了父亲 —— 这是本 App 最重的破坏性操作。
     * 软删让「合错了」从**事故**降级为**可撤销**：用户在回收站里
     * 把被合并的那株恢复出来即可（它的观察已经改挂了，恢复后是一株空档案，
     * 但字段都在），再手动改回去。方案 §22.9/22.10 明确要求这么做。
     *
     * 事务外还有一步：把围绕 `drop` 的待处理清洗问题标成已解决 ——
     * 否则用户合并完还会看到「这株疑似重复」和「这株缺拉丁名」，
     * 点进去却是「植物不存在」。
     *
     * @return 合并后的档案 id（即 `keepId`）
     */
    suspend fun mergeInto(plan: MergePlan): Result<Long> = runCatching {
        val keep = plantRecordDao.getById(plan.keepId) ?: error("要保留的档案不存在")
        val drop = plantRecordDao.getById(plan.dropId) ?: error("被合并的档案不存在")
        if (keep.id == drop.id) error("不能把档案合并到它自己")

        val now = System.currentTimeMillis()

        // 代表观察要**在改挂之前**读：drop 原本的代表观察是用户选过的，
        // 改挂之后再查就分不出哪条曾经是它的代表
        val inheritedPrimary = observationDao.getByPlant(drop.id).firstOrNull { it.isPrimary }

        database.withTransaction {
            observationDao.reassignPlant(from = drop.id, to = keep.id)

            plantRecordDao.update(applyMergePlan(keep, plan, now))

            // drop 软删。**不删它的照片**：照片已经随观察改挂到 keep 名下，
            // 此刻再按 drop 去清理文件会把 keep 正在用的照片删掉
            plantRecordDao.update(drop.copy(deletedAt = now))

            if (observationDao.getByPlant(keep.id).none { it.isPrimary }) {
                inheritedPrimary?.let { observationDao.markPrimary(it.id) }
            }
        }

        // 只结「只涉及被合并株」与「正是这两株是不是同一株」的问题，
        // 不能把涉及多株的问题一起结掉 —— 见 CleaningIssueDao.resolveOnMerge
        cleaningIssueDao.resolveOnMerge(keepId = keep.id, dropId = drop.id, now = now)

        keep.id
    }

    /**
     * 把 [MergePlan] 施加到保留侧。
     *
     * 逐字段取方案里的值 —— 方案对**每一个**可合并字段都有取值
     * （两边都空时是 null），所以这里不需要「null 就不覆盖」的保护：
     * 真出现 null，说明两边本来都没有值，写 null 与保留原值等价。
     *
     * 例外是 [MergeField.NAME]：方案的默认规则是「用保留侧的」，
     * 但用户可以改选成另一边的名字；两侧都为空（不可能，中文名必填）
     * 时退回原值，免得把标题清空。
     *
     * `updatedAt` 要更新：档案内容变了，它该回到列表最前面，
     * 也才会被清洗的增量扫描重新检查。
     */
    private fun applyMergePlan(
        keep: PlantRecordEntity,
        plan: MergePlan,
        now: Long,
    ): PlantRecordEntity {
        fun value(field: MergeField): String? = plan.value(field)

        return keep.copy(
            name = value(MergeField.NAME)?.takeIf { it.isNotBlank() } ?: keep.name,
            latinName = value(MergeField.LATIN_NAME),
            commonNames = value(MergeField.COMMON_NAMES),
            family = value(MergeField.FAMILY),
            genus = value(MergeField.GENUS),
            category = value(MergeField.CATEGORY),
            confidence = plan.confidence,
            description = value(MergeField.DESCRIPTION),
            morphologicalFeatures = value(MergeField.MORPHOLOGICAL_FEATURES),
            growthHabits = value(MergeField.GROWTH_HABITS),
            floweringPeriod = value(MergeField.FLOWERING_PERIOD),
            fruitingPeriod = value(MergeField.FRUITING_PERIOD),
            landscapeUses = value(MergeField.LANDSCAPE_USES),
            careAdvice = value(MergeField.CARE_ADVICE),
            pestControl = value(MergeField.PEST_CONTROL),
            note = value(MergeField.NOTE),
            updatedAt = now,
        )
    }

    // ---------------- 任务队列（Phase 8）----------------

    /** 任务照片的轻量引用 —— 只带落库需要的两个字段，避免把实体带出数据层 */
    data class TaskImageRef(val relativePath: String, val role: ImageRole)

    /** 任务落库的结果：写到了哪个档案、哪条观察、是不是后台自动挂靠 */
    data class TaskPersisted(
        val plantId: Long,
        val observationId: Long,
        /**
         * true = 后台命中候选档案、**自动挂靠**到了它名下（方案 §6.2）。
         *
         * 后台没有用户在场，弹不出归并确认框；而照片已经识别完，
         * 用户显然希望它进档案。挂到候选档案下是**可撤销**的
         * （任务列表上提供「拆分为新档案」），比堆在「未归类」角落里更符合预期。
         * 挂靠时目标档案的文字内容一律不动，只把候选信息写进任务的
         * `pendingMerge*` 三字段等用户裁决。
         */
        val attachedToExisting: Boolean,
    )

    /**
     * 任务队列专用的落库。
     *
     * 与 [saveAsNewPlant] / [appendObservation] 的关键差别：
     * 照片**来自任务表而不是拍摄草稿**。后台任务没有草稿
     * （草稿是「添加植物」页面的单例，批量任务若共用它，前一个任务
     * 保存时 `draftStore.clear()` 会把后面任务的照片引用全清掉），
     * 所以照片引用由调用方从 `recognition_task_image` 读出来传进来。
     *
     * 同样**不碰草稿**：任务落库后由 Worker 清任务图片行，草稿完全无关。
     *
     * @param attachToPlantId 非空 = 挂靠到该档案下作为新观察（后台自动挂靠）
     */
    suspend fun persistTaskResult(
        result: RecognitionResult,
        rawAiJson: String,
        images: List<TaskImageRef>,
        attachToPlantId: Long? = null,
    ): Result<TaskPersisted> = runCatching {
        require(images.isNotEmpty()) { "任务没有照片" }

        val existing = attachToPlantId?.let { id ->
            plantRecordDao.getById(id) ?: error("目标植物档案不存在")
        }

        val now = System.currentTimeMillis()

        val persisted = database.withTransaction {
            if (existing != null) {
                val observationId = insertObservationRecord(
                    plantId = existing.id,
                    timestamp = now,
                    isPrimary = false,
                    rawAiJson = rawAiJson,
                    latitude = null,
                    longitude = null,
                    locationName = null,
                )
                insertImagesByRef(observationId, images)
                // 与 appendObservation 一致：置信度跟随最近一次识别，文字内容不动
                plantRecordDao.update(existing.copy(confidence = result.confidence, updatedAt = now))
                TaskPersisted(existing.id, observationId, attachedToExisting = true)
            } else {
                val plantId = plantRecordDao.insert(
                    PlantRecordEntity(
                        name = result.name,
                        latinName = result.latinName,
                        family = result.family,
                        genus = result.genus,
                        category = result.category,
                        confidence = result.confidence,
                        analysisStatus = AnalysisStatus.NOT_REQUESTED,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val observationId = insertObservationRecord(
                    plantId = plantId,
                    timestamp = now,
                    isPrimary = true,
                    rawAiJson = rawAiJson,
                    latitude = null,
                    longitude = null,
                    locationName = null,
                )
                insertImagesByRef(observationId, images)
                TaskPersisted(plantId, observationId, attachedToExisting = false)
            }
        }

        persisted
    }

    /**
     * 用户裁决「确实是同一种」。
     *
     * 观察保持挂在目标档案下不动，只做两件轻量修正：
     * 置信度取两边更高的、刷新档案的 `updatedAt`。
     *
     * **不并入俗称** —— 识别结果里没有俗称字段（方案 Phase 1 的刻意设计：
     * 知识性字段只走文字分析通道），自然也无从并入。
     */
    suspend fun confirmTaskMerge(observationId: Long, plantId: Long): Result<Unit> = runCatching {
        val observation = observationDao.getById(observationId)
            ?: error("该观察记录不存在")
        val target = plantRecordDao.getById(plantId)
            ?: error("目标植物档案不存在")
        val result = resultFromAiJson(observation.aiResultJson)

        val now = System.currentTimeMillis()
        plantRecordDao.update(
            target.copy(
                confidence = maxOf(target.confidence, result?.confidence ?: 0.0),
                updatedAt = now,
            ),
        )
    }

    /**
     * 用户裁决「这其实是新植物」：把该观察从目标档案上**拆**出来建独立档案。
     *
     * 新档案的字段从观察的 `aiResultJson` 反解析恢复 —— 那是识别落库时的原文，
     * 足以重建 [RecognitionResult]。解析不出来就报错而不是硬拆：
     * 拆出一个叫「未知」的空档案只会制造新的清洗问题。
     */
    suspend fun splitTaskToNewPlant(observationId: Long): Result<Long> = runCatching {
        val observation = observationDao.getById(observationId)
            ?: error("该观察记录不存在")
        val result = resultFromAiJson(observation.aiResultJson)
            ?: error("无法从识别结果恢复档案信息，拆分失败")

        val now = System.currentTimeMillis()
        database.withTransaction {
            val newPlantId = plantRecordDao.insert(
                PlantRecordEntity(
                    name = result.name,
                    latinName = result.latinName,
                    family = result.family,
                    genus = result.genus,
                    category = result.category,
                    confidence = result.confidence,
                    analysisStatus = AnalysisStatus.NOT_REQUESTED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            observationDao.update(observation.copy(plantId = newPlantId))
            // 拆出来的是这条观察自己的档案，它理应成为代表观察
            observationDao.setPrimaryObservation(newPlantId, observationId)
            newPlantId
        }
    }

    /** 从观察的 `aiResultJson` 恢复识别结果；解析不出返回 null（含原文为空） */
    private fun resultFromAiJson(raw: String?): RecognitionResult? =
        raw?.takeIf { it.isNotBlank() }?.let { text ->
            when (val attempt = TolerantJsonParser.parse(text)) {
                is TolerantJsonParser.ParseAttempt.Success -> attempt.result
                is TolerantJsonParser.ParseAttempt.Degraded -> attempt.result
                is TolerantJsonParser.ParseAttempt.Failed -> null
            }
        }

    /**
     * 任务落库用的观察写入：地点数据被拆成三个可空字段。
     *
     * 任务表（方案 §3.1）不携带地点 —— 批量任务的来源是「提前拍好的一批照片」，
     * 拍摄时的坐标不在任务里。等将来任务编辑页支持补充地点时，
     * 把三件套加进任务表再传到这里，观察行结构与现在完全兼容。
     */
    private suspend fun insertObservationRecord(
        plantId: Long,
        timestamp: Long,
        isPrimary: Boolean,
        rawAiJson: String,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
    ): Long = observationDao.insert(
        PlantObservationEntity(
            plantId = plantId,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            locationName = locationName,
            aiResultJson = rawAiJson,
            isPrimary = isPrimary,
        ),
    )

    private suspend fun insertImagesByRef(
        observationId: Long,
        images: List<TaskImageRef>,
    ) {
        imageDao.insertAll(
            images.mapIndexed { index, ref ->
                ObservationImageEntity(
                    observationId = observationId,
                    imagePath = ref.relativePath,
                    role = ref.role,
                    sortOrder = index,
                )
            },
        )
    }

    // ---------------- 内部 ----------------

    /**
     * 新建一条观察记录。
     *
     * 地点从**草稿**里读，而不是由调用方再传一遍。
     *
     * 草稿是拍摄那一刻的快照（spec 第十八节：用户点进「添加植物」时人就在植物跟前，
     * 那一刻的坐标才是这株植物的位置），地点三件套在草稿里是原子的一组。
     * 让调用方另行传 locationName 会出现「传了名字却漏了坐标」这类半吊子状态，
     * 而且实际上没有任何调用方传过它 —— 那个参数从 Phase 4 起就是死的。
     */
    private suspend fun insertObservation(
        plantId: Long,
        timestamp: Long,
        isPrimary: Boolean,
        rawAiJson: String,
        draft: CaptureDraft,
    ): Long = observationDao.insert(
        PlantObservationEntity(
            plantId = plantId,
            timestamp = timestamp,
            latitude = draft.latitude,
            longitude = draft.longitude,
            locationName = draft.locationName,
            aiResultJson = rawAiJson,
            isPrimary = isPrimary,
        ),
    )

    /**
     * 写入图片行。
     *
     * 顺序与部位标注都按草稿里的现状落库 —— 图序会写进 prompt，
     * 对用户而言是有意义的排列，不能在保存时打乱。
     */
    private suspend fun insertImages(
        observationId: Long,
        images: List<DraftImage>,
    ) {
        imageDao.insertAll(
            images.mapIndexed { index, draftImage ->
                ObservationImageEntity(
                    observationId = observationId,
                    imagePath = draftImage.relativePath,
                    role = draftImage.role,
                    sortOrder = index,
                )
            },
        )
    }
}

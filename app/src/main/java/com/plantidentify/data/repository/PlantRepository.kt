package com.plantidentify.data.repository

import androidx.room.withTransaction
import com.plantidentify.data.ai.PlantAnalysis
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.draft.CaptureDraft
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.draft.DraftImage
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
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
                description = analysis?.description ?: existing.description,
                morphologicalFeatures = analysis?.morphologicalFeatures
                    ?: existing.morphologicalFeatures,
                growthHabits = analysis?.growthHabits ?: existing.growthHabits,
                floweringPeriod = analysis?.floweringPeriod ?: existing.floweringPeriod,
                fruitingPeriod = analysis?.fruitingPeriod ?: existing.fruitingPeriod,
                landscapeUses = analysis?.landscapeUses ?: existing.landscapeUses,
                careAdvice = analysis?.careAdvice ?: existing.careAdvice,
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
     * 更新档案的**人工可编辑字段**。
     *
     * 刻意只开放这几项：名称/学名/科/属/类型/备注。
     * 置信度、百科内容、观察记录都不在此列 —— 前者是 AI 结论的记录，
     * 后两者由各自的流程维护，允许在这里随意改写会让数据失去可追溯性。
     */
    suspend fun updatePlantInfo(
        plantId: Long,
        name: String,
        latinName: String?,
        family: String?,
        genus: String?,
        category: String?,
        note: String?,
    ): Result<Unit> = runCatching {
        val existing = plantRecordDao.getById(plantId)
            ?: error("植物档案不存在")

        plantRecordDao.update(
            existing.copy(
                name = name.trim(),
                latinName = latinName?.trim()?.takeIf { it.isNotEmpty() },
                family = family?.trim()?.takeIf { it.isNotEmpty() },
                genus = genus?.trim()?.takeIf { it.isNotEmpty() },
                category = category?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = System.currentTimeMillis(),
            ),
        )
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

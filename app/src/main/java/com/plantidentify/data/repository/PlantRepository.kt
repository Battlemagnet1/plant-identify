package com.plantidentify.data.repository

import androidx.room.withTransaction
import com.plantidentify.data.ai.PlantAnalysis
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.draft.DraftImage
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
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
 */
class PlantRepository(
    private val database: PlantIdentifyDatabase,
    private val draftStore: CaptureDraftStore,
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
        locationName: String? = null,
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
                locationName = locationName,
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
        locationName: String? = null,
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
                locationName = locationName,
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

    // ---------------- 内部 ----------------

    private suspend fun insertObservation(
        plantId: Long,
        timestamp: Long,
        isPrimary: Boolean,
        rawAiJson: String,
        locationName: String?,
    ): Long = observationDao.insert(
        PlantObservationEntity(
            plantId = plantId,
            timestamp = timestamp,
            latitude = null,
            longitude = null,
            locationName = locationName,
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

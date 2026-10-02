package com.plantidentify.data.import

import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.FolderImportStatus
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.cleaning.MergePlanner
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一批决定执行完之后的结果 */
data class ImportApplyResult(
    /** 合并进本地记录的条件数 */
    val merged: Int,

    /** 用户选择「保留为新记录」的条数 */
    val kept: Int,

    /** 执行失败的条目（植物 id），界面要如实报出来而不是静默跳过 */
    val failed: List<Long> = emptyList(),
) {
    val total: Int get() = merged + kept
}

/**
 * 导入决定的执行（v1.0.2 Phase 2）。
 *
 * ## 全部复用既有的合并路径
 *
 * 真正把两条档案并起来的是 `PlantRepository.mergeInto` —— 清洗模块用了很久的那条。
 * 它已经解决了几个很容易做错的地方：
 *
 * - 观察记录**整体改挂**到保留的那条（不是复制、不是丢弃）
 * - 照片行随观察走，所以照片一张都不会丢
 * - 被并入的那条只是**软删**（进回收站），万一合错了还能捞回来
 *
 * 这里一件新东西都没发明，只负责「拿导入的记录当 drop、拿本地记录当 keep」
 * 把参数喂进去。
 *
 * ## 为什么不在这里做「自动合并」
 *
 * 需求原文写死了：「不要未经用户确认直接破坏性合并」。所以 [apply] 的入参
 * 是一份**用户已经确认过**的决定表 —— 没有任何调用路径能绕过用户点击。
 */
class ImportMergeExecutor(
    private val database: PlantIdentifyDatabase,
    private val plantRepository: PlantRepository,
) {

    /**
     * 执行用户对某次导入做出的全部决定。
     *
     * @param decisions 植物 id → 目标植物 id；**值为 null 表示「保留为新记录」**
     */
    suspend fun apply(
        folderId: Long,
        decisions: Map<Long, Long?>,
    ): Result<ImportApplyResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (decisions.isEmpty()) return@runCatching ImportApplyResult(0, 0)

            // 一次读出全部条目：判定级别与原有匹配对象要沿用到状态更新里，
            // 逐条查询会让「一次导入几百株」变成几百次查询
            val existing = database.folderImportDao().getItems(folderId)
                .associateBy { it.importPlantId }

            var merged = 0
            var kept = 0
            val failed = mutableListOf<Long>()

            for ((importPlantId, targetPlantId) in decisions) {
                val current = existing[importPlantId]
                val outcome = if (targetPlantId == null) {
                    markKept(folderId, importPlantId, current?.matchLevel, current?.matchedPlantId)
                        .onSuccess { kept++ }
                } else {
                    mergeOne(folderId, importPlantId, targetPlantId, current?.matchLevel)
                        .onSuccess { merged++ }
                }
                // 单条失败不中断整批：用户可能一次处理几十条，
                // 因为其中一条的数据出了问题就全部回滚，比跳过它更糟
                if (outcome.isFailure) failed += importPlantId
            }

            refreshHandledCount(folderId)
            ImportApplyResult(merged = merged, kept = kept, failed = failed)
        }
    }

    private suspend fun mergeOne(
        folderId: Long,
        importPlantId: Long,
        targetPlantId: Long,
        matchLevel: Int?,
    ): Result<Unit> = runCatching {
        require(importPlantId != targetPlantId) { "不能把一条记录合并到它自己" }

        val keep = snapshotOf(targetPlantId) ?: error("目标档案不存在（可能已被删除）")
        val drop = snapshotOf(importPlantId) ?: error("导入的档案不存在")

        // 字段择优完全交给清洗模块那套规则（长的赢、取并集、备注拼接…），
        // 导入场景没有理由另立一套标准
        plantRepository.mergeInto(MergePlanner.plan(keep, drop)).getOrThrow()

        database.folderImportDao().updateStatus(
            folderId = folderId,
            importPlantId = importPlantId,
            status = FolderImportStatus.MERGED.name,
            matchedPlantId = targetPlantId,
            matchLevel = matchLevel,
            decidedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun markKept(
        folderId: Long,
        importPlantId: Long,
        matchLevel: Int?,
        matchedPlantId: Long?,
    ): Result<Unit> = runCatching {
        database.folderImportDao().updateStatus(
            folderId = folderId,
            importPlantId = importPlantId,
            status = FolderImportStatus.KEPT.name,
            // 保留原匹配对象：它记录的是「用户看过、觉得不是同一条」，
            // 抹掉的话以后重新检测又会把同一条报出来
            matchedPlantId = matchedPlantId,
            matchLevel = matchLevel,
            decidedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 重算「已处理」条数。
     *
     * 不做成 `handledCount++` 的增量：决定可能被重复执行（用户来回点），
     * 增量一旦重复就永远偏大，而重算总是对的 —— 这个表最多几十行，
     * 重算的代价可以忽略。
     */
    private suspend fun refreshHandledCount(folderId: Long) {
        val counts = database.folderImportDao().countGroupedByStatus(folderId)
        val handled = counts
            .filter { it.status != FolderImportStatus.PENDING.name }
            .sumOf { it.count }
        val data = database.folderImportDao().getData(folderId) ?: return
        database.folderImportDao().upsertData(
            data.copy(handledCount = handled, lastMergedAt = System.currentTimeMillis()),
        )
    }

    private suspend fun snapshotOf(plantId: Long): RecordSnapshot? =
        database.plantRecordDao().getById(plantId)?.toSnapshot(plantId)
}

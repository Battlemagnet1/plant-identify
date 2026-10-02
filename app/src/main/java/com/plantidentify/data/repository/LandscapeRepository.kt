package com.plantidentify.data.repository

import android.net.Uri
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.FolderImageEntity
import com.plantidentify.data.local.entity.FolderImageKind
import com.plantidentify.data.local.entity.LandscapeFolderDataEntity
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.landscape.LandscapeAnalyzer
import com.plantidentify.domain.landscape.LandscapePlant
import com.plantidentify.domain.landscape.LandscapeStatistics
import kotlinx.coroutines.flow.Flow

/**
 * 景观文件夹的数据（v1.0.2 Phase 3）。
 *
 * ## 它与 `FolderRepository` 的分工
 *
 * [FolderRepository] 管「文件夹里有哪些植物」——那是**所有类型**的文件夹共有的。
 * 本类只管两件景观特有的事：
 *
 * 1. **景观照片**（`folder_image`）—— 拍的是花坛、道路绿化、植物群落
 * 2. **景观档案与分析**（`landscape_folder_data`）—— 地点、项目类型、AI 分析结果
 *
 * 分开的理由与 Phase 1 把文件夹从 `PlantRepository` 拆出来一样：职责不同，
 * 而且分开之后**本类不持有任何写 `plant_record` 的入口** ——
 * 需求 §六 说「景观文件夹直接引用现有 PlantRecord，不要额外创建
 * 一套完全独立的植物数据」，这个结构本身就是那句话的落实。
 */
class LandscapeRepository(
    private val database: PlantIdentifyDatabase,
    private val imageStore: ImageStore,
) {

    // ------------------------------------------------------------------ 档案

    fun observeData(folderId: Long): Flow<LandscapeFolderDataEntity?> =
        database.landscapeFolderDataDao().observeByFolder(folderId)

    suspend fun getData(folderId: Long): LandscapeFolderDataEntity? =
        database.landscapeFolderDataDao().getByFolder(folderId)

    /** 保存地点 / 项目信息。这几项是**用户输入**的，与 AI 分析结果分开存 */
    suspend fun saveProfile(
        folderId: Long,
        location: String?,
        landscapeDescription: String?,
        projectType: String?,
    ): Result<Unit> = runCatching {
        val existing = database.landscapeFolderDataDao().getByFolder(folderId)
        database.landscapeFolderDataDao().upsert(
            (existing ?: LandscapeFolderDataEntity(folderId = folderId)).copy(
                location = location.cleanOrNull(),
                landscapeDescription = landscapeDescription.cleanOrNull(),
                projectType = projectType.cleanOrNull(),
            ),
        )
    }

    /**
     * 保存 AI 分析结果。
     *
     * `analysisVersion` 每次 +1 —— 它是「这份结论是第几版」的凭据。
     * 光有 `analysisUpdatedAt` 不够：用户看到「三天前的分析」，
     * 无法判断它是第一版还是第五版，而后者意味着数据已经被大改过。
     */
    suspend fun saveAnalysis(
        folderId: Long,
        result: String,
        model: String,
    ): Result<Int> = runCatching {
        val existing = database.landscapeFolderDataDao().getByFolder(folderId)
        val nextVersion = (existing?.analysisVersion ?: 0) + 1
        database.landscapeFolderDataDao().upsert(
            (existing ?: LandscapeFolderDataEntity(folderId = folderId)).copy(
                analysisResult = result,
                analysisModel = model,
                analysisUpdatedAt = System.currentTimeMillis(),
                analysisVersion = nextVersion,
                needsReanalysis = false,
            ),
        )
        nextVersion
    }

    /**
     * 标记「需要重新分析」。
     *
     * 需求 §十三：「当植物数据发生重大变化后，标记需要重新分析」。
     *
     * 刻意**不自动触发分析**：AI 调用要花钱，用户可能只是顺手加了一株植物，
     * 不该因此自动产生一次付费请求。标记出来、由用户决定什么时候重跑。
     */
    suspend fun markNeedsReanalysis(folderId: Long) {
        runCatching {
            val existing = database.landscapeFolderDataDao().getByFolder(folderId) ?: return
            // 从没分析过的不用标记 —— 界面上本来就是「还没分析」
            if (existing.analysisResult.isNullOrBlank()) return
            if (existing.needsReanalysis) return
            database.landscapeFolderDataDao().upsert(existing.copy(needsReanalysis = true))
        }
    }

    // ------------------------------------------------------------------ 照片

    fun observeImages(folderId: Long): Flow<List<FolderImageEntity>> =
        database.folderImageDao().observeAll(folderId)

    fun observeImageCount(folderId: Long): Flow<Int> =
        database.folderImageDao().observeCount(folderId)

    suspend fun getImages(folderId: Long): List<FolderImageEntity> =
        database.folderImageDao().getAll(folderId)

    /**
     * 加入一张景观照片。
     *
     * 走 `ImageStore.importFromUri` —— 与植物照片同一套存储约定
     * （写 `filesDir/images/`、库里只留相对路径）。
     * **文件层面不区分**植物照片与景观照片，区分靠引用它的那一行。
     */
    suspend fun addImage(
        folderId: Long,
        uri: Uri,
        kind: FolderImageKind = FolderImageKind.OTHER,
    ): Result<Long> = runCatching {
        val relativePath = imageStore.importFromUri(uri).getOrThrow()
        val nextOrder = database.folderImageDao().maxSortOrder(folderId) + 1
        database.folderImageDao().insert(
            FolderImageEntity(
                folderId = folderId,
                imagePath = relativePath,
                kind = kind.name,
                sortOrder = nextOrder,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 删除一张景观照片。
     *
     * **先删数据库行、再删文件**：如果反过来的话，删文件成功而删行失败
     * 会留下一条指向不存在文件的记录（界面上是一个永久加载不出来的格子）；
     * 而这个顺序最坏只留下一个孤儿文件，不影响任何界面。
     * 顺序的理由与 `PlantRepository` 删照片时一致。
     */
    suspend fun removeImage(image: FolderImageEntity): Result<Unit> = runCatching {
        database.folderImageDao().deleteById(image.id)
        imageStore.delete(image.imagePath)
    }

    suspend fun updateImage(
        imageId: Long,
        caption: String?,
        kind: FolderImageKind,
        folderId: Long,
    ): Result<Unit> = runCatching {
        val current = database.folderImageDao().getAll(folderId).firstOrNull { it.id == imageId }
            ?: return@runCatching
        database.folderImageDao().insertAll(
            listOf(current.copy(caption = caption.cleanOrNull(), kind = kind.name)),
        )
    }

    suspend fun reorderImages(ordered: List<FolderImageEntity>): Result<Unit> = runCatching {
        database.folderImageDao().insertAll(
            ordered.mapIndexed { index, image -> image.copy(sortOrder = index) },
        )
    }

    /** 文件夹成员的完整档案（观察 + 照片），供景观 PDF 的植物明细用 */
    suspend fun plantsWithDetails(folderId: Long) =
        database.plantRecordDao().getFolderPlantsWithObservationsAndImages(folderId)

    // ------------------------------------------------------------------ 统计

    /**
     * 跑一遍本地景观统计。
     *
     * 只读植物档案里**景观关心的那几个字段**，不读描述全文 ——
     * 一个景观文件夹可能有几百株，读全字段纯属浪费（这是清洗模块
     * 那一课学到的教训）。
     */
    suspend fun statisticsOf(folderId: Long): LandscapeStatistics {
        val plants = database.plantRecordDao().getLandscapePlants(folderId).map {
            LandscapePlant(
                id = it.id,
                name = it.name,
                family = it.family,
                genus = it.genus,
                category = it.category,
                growthHabits = it.growthHabits,
                morphologicalFeatures = it.morphologicalFeatures,
                floweringPeriod = it.floweringPeriod,
                fruitingPeriod = it.fruitingPeriod,
                landscapeUses = it.landscapeUses,
                description = it.description,
            )
        }
        return LandscapeAnalyzer.analyze(plants)
    }
}

/** 空白字符串统一成 null，避免库里混着 "" 和 null 两种「没填」 */
private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

package com.plantidentify.data.repository

import androidx.room.withTransaction
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.local.entity.FolderPlantEntity
import com.plantidentify.data.local.entity.FolderType
import com.plantidentify.data.local.projection.FolderCardRow
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.domain.model.FolderFilter
import com.plantidentify.domain.model.FolderPlantSort
import com.plantidentify.domain.model.FolderSort
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹域的唯一入口（v1.0.2 Phase 1）。
 *
 * ## 为什么单独一个 Repository，而不是塞进 PlantRepository
 *
 * [PlantRepository] 的职责是「植物档案的生命周期」——保存识别结果、追加观察、
 * 管理图片、归并、落库任务，已经 1000 行出头。文件夹是另一个聚合
 * （folder + folder_plant + 景观扩展），把它的十几个方法塞进去会让那个类
 * 彻底失焦，也会让「这个方法是改植物的还是改文件夹的」变得难判断。
 *
 * ## 一条硬约束
 *
 * **本类的任何方法都不会写 `plant_record`。**
 * 「从文件夹移除」和「删除文件夹」都只动 `folder_plant` ——
 * 需求文档原话是「从文件夹移除 ≠ 删除植物」，这一点在 Repository
 * 这一层就断掉，上层不可能误用。
 *
 * ## 关于 `updatedAt`
 *
 * 成员变化后只刷新**文件夹**的 `updatedAt`，**刻意不刷新植物的** ——
 * 把一株植物加进文件夹并不是「修改了这株植物」，如果顺带 touch 植物，
 * 它会在植物列表里凭空跳到最前，用户会以为自己不小心改了它。
 */
class FolderRepository(
    private val database: PlantIdentifyDatabase,
) {

    private val folderDao get() = database.folderDao()
    private val folderPlantDao get() = database.folderPlantDao()

    // ------------------------------------------------------------------
    // 列表与查询
    // ------------------------------------------------------------------

    /**
     * 文件夹主页的卡片列表。
     *
     * [Filter] 与 [sort] 一起映射成一条 SQL 的三个参数 ——
     * 空串表示该条件不生效（见 `FolderDao.observeFolderCards`）。
     */
    fun observeFolderCards(
        filter: FolderFilter = FolderFilter(),
        sort: FolderSort = FolderSort.DEFAULT,
    ): Flow<List<FolderCardRow>> = folderDao.observeFolderCards(
        type = filter.type?.name ?: "",
        keyword = filter.keyword.trim(),
        sort = sort.name,
    )

    /** 各类型的数量与总数（筛选行上的计数徽标） */
    fun observeTotalCount(): Flow<Int> = folderDao.observeTotalCount()

    fun observeCountByType(type: FolderType): Flow<Int> = folderDao.observeCountByType(type.name)

    fun observeFolder(folderId: Long): Flow<FolderEntity?> = folderDao.observeById(folderId)

    suspend fun getFolder(folderId: Long): FolderEntity? = folderDao.getById(folderId)

    /** 全部文件夹（「加入其他文件夹」「移动到其他文件夹」的选择列表用） */
    fun observeAllFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    /** 文件夹内的植物卡片列表（投影与植物列表一致，可直接复用同一个卡片组件） */
    fun observeFolderPlants(
        folderId: Long,
        keyword: String = "",
        sort: FolderPlantSort = FolderPlantSort.DEFAULT,
    ): Flow<List<PlantCardRow>> = folderPlantDao.observeFolderPlantCards(
        folderId = folderId,
        keyword = keyword.trim(),
        sort = sort.name,
    )

    /** 这株植物已经属于哪些文件夹（详情页「加入文件夹」进入时预勾选） */
    fun observeFolderIdsForPlant(plantId: Long): Flow<List<Long>> =
        folderPlantDao.observeFolderIdsForPlant(plantId)

    /** 文件夹内的全部 plantId（「全选」用；已软删的植物不算） */
    suspend fun getPlantIdsInFolder(folderId: Long): List<Long> =
        folderPlantDao.getPlantIdsInFolder(folderId)

    // ------------------------------------------------------------------
    // 创建 / 编辑 / 删除
    // ------------------------------------------------------------------

    suspend fun createFolder(
        name: String,
        type: FolderType,
        description: String? = null,
    ): Result<Long> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "文件夹名不能为空" }

        val now = System.currentTimeMillis()
        folderDao.insert(
            FolderEntity(
                name = trimmed,
                type = type,
                description = description.cleanOrNull(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateFolder(
        folderId: Long,
        name: String,
        type: FolderType,
        description: String? = null,
    ): Result<Unit> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "文件夹名不能为空" }

        val existing = folderDao.getById(folderId)
            ?: error("文件夹不存在（可能已被删除）")

        folderDao.update(
            existing.copy(
                name = trimmed,
                type = type,
                description = description.cleanOrNull(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 删除文件夹。
     *
     * **只删 `folder_plant` 的关联行与 `folder` 行，绝不触碰 `plant_record`。**
     *
     * 显式删关联再删文件夹（而不是只依赖外键级联），是为了让「删了什么」
     * 在代码里看得见 —— 读这段代码的人不必去翻 schema 才知道植物安不安全。
     */
    suspend fun deleteFolder(folderId: Long): Result<Unit> = runCatching {
        database.withTransaction {
            folderPlantDao.deleteByFolder(folderId)
            folderDao.deleteById(folderId)
        }
    }

    // ------------------------------------------------------------------
    // 关联操作（单株 / 批量）
    // ------------------------------------------------------------------

    /** 把一批植物加入**一个**文件夹 */
    suspend fun addPlantsToFolder(
        folderId: Long,
        plantIds: List<Long>,
    ): Result<Int> = runCatching {
        val ids = plantIds.distinct()
        if (ids.isEmpty()) return@runCatching 0

        database.withTransaction {
            val now = System.currentTimeMillis()
            val inserted = insertMembers(folderId, ids, now)
            if (inserted > 0) folderDao.touch(folderId, now)
            inserted
        }
    }

    /** 把一批植物加入**多个**文件夹（植物详情的「选择多个文件夹」、批量的「加入其他文件夹」） */
    suspend fun addPlantsToFolders(
        folderIds: List<Long>,
        plantIds: List<Long>,
    ): Result<Int> = runCatching {
        val ids = plantIds.distinct()
        val folders = folderIds.distinct()
        if (ids.isEmpty() || folders.isEmpty()) return@runCatching 0

        database.withTransaction {
            var total = 0
            for (folderId in folders) {
                val now = System.currentTimeMillis()
                val inserted = insertMembers(folderId, ids, now)
                if (inserted > 0) {
                    folderDao.touch(folderId, now)
                    total += inserted
                }
            }
            total
        }
    }

    /** 从文件夹移除（**不删植物**，只删关联） */
    suspend fun removePlantsFromFolder(
        folderId: Long,
        plantIds: List<Long>,
    ): Result<Int> = runCatching {
        val ids = plantIds.distinct()
        if (ids.isEmpty()) return@runCatching 0

        database.withTransaction {
            val removed = folderPlantDao.deleteMembers(folderId, ids)
            if (removed > 0) folderDao.touch(folderId)
            removed
        }
    }

    /**
     * 移动到另一个文件夹 = 目标加入 + 源移除。
     *
     * 同一个事务里完成，中途失败不会出现「两边都不在」或「两边都在」。
     */
    suspend fun movePlants(
        fromFolderId: Long,
        toFolderId: Long,
        plantIds: List<Long>,
    ): Result<Int> = runCatching {
        require(fromFolderId != toFolderId) { "源文件夹与目标文件夹相同" }
        val ids = plantIds.distinct()
        if (ids.isEmpty()) return@runCatching 0

        database.withTransaction {
            val now = System.currentTimeMillis()
            insertMembers(toFolderId, ids, now)
            folderPlantDao.deleteMembers(fromFolderId, ids)
            folderDao.touch(fromFolderId, now)
            folderDao.touch(toFolderId, now)
            ids.size
        }
    }

    /**
     * 选择页保存：把某株植物的归属整体设为目标集合。
     *
     * 按差集写入 —— 只动真正变化的那部分，避免「先全删再全加」把
     * `addedAt` 与 `sortOrder` 全部重置（用户会看到排序突然变了）。
     */
    suspend fun setPlantFolders(
        plantId: Long,
        folderIds: List<Long>,
    ): Result<Unit> = runCatching {
        database.withTransaction {
            val target = folderIds.distinct().toSet()
            val current = folderPlantDao.getFolderIdsForPlant(plantId).toSet()
            val toAdd = target - current
            val toRemove = current - target
            val now = System.currentTimeMillis()

            for (folderId in toRemove) {
                folderPlantDao.deleteMembers(folderId, listOf(plantId))
                folderDao.touch(folderId, now)
            }
            for (folderId in toAdd) {
                val sortOrder = folderPlantDao.maxSortOrder(folderId) + 1
                folderPlantDao.insert(
                    FolderPlantEntity(
                        folderId = folderId,
                        plantId = plantId,
                        addedAt = now,
                        sortOrder = sortOrder,
                    ),
                )
                folderDao.touch(folderId, now)
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 批量插入成员，返回**实际新增**的行数。
     *
     * 用 `IGNORE` 处理重复：用户可能把一株已经在该文件夹里的植物再次勾选，
     * 那不是错误。冲突的行插入返回 `-1L`，据此统计真实新增数 ——
     * 否则界面会显示「已加入 5 株」而其中 3 株本来就在里面。
     */
    private suspend fun insertMembers(
        folderId: Long,
        plantIds: List<Long>,
        now: Long,
    ): Int {
        var sortOrder = folderPlantDao.maxSortOrder(folderId)
        val rows = plantIds.map { plantId ->
            sortOrder += 1
            FolderPlantEntity(
                folderId = folderId,
                plantId = plantId,
                addedAt = now,
                sortOrder = sortOrder,
            )
        }
        return folderPlantDao.insertAll(rows).count { it != -1L }
    }
}

/** 空白字符串一律按「没填」处理，避免存进 "" 之后搜索与显示都要额外判断 */
private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.FolderPlantEntity
import com.plantidentify.data.local.projection.PlantCardRow
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹 ↔ 植物关联的读写（v1.0.2 Phase 1）。
 *
 * 这里是「一株植物属于多个文件夹」的全部实现所在 —— 只有这一张表在动，
 * `plant_record` 与 `plant_observation` 完全不受影响。
 */
@Dao
interface FolderPlantDao {

    // ---------- 写入 ----------

    /**
     * 批量加入。
     *
     * `IGNORE` 是有意的：用户可能把一株已经在该文件夹里的植物再次勾选，
     * 那不是错误，静默跳过即可。冲突的行返回值是 `-1L`，
     * 调用方据此统计「实际新增了几条」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<FolderPlantEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: FolderPlantEntity): Long

    /** @return 实际删除的行数 */
    @Query("DELETE FROM folder_plant WHERE folderId = :folderId AND plantId IN (:plantIds)")
    suspend fun deleteMembers(folderId: Long, plantIds: List<Long>): Int

    /**
     * 清空某个文件夹的全部关联。
     *
     * 删文件夹前显式调用它 —— 外键的 `ON DELETE CASCADE` 也会做同样的事，
     * 显式删一次是为了让「删了什么」在代码里看得见，而不是藏在 schema 里。
     */
    @Query("DELETE FROM folder_plant WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    // ---------- 读取 ----------

    @Query("SELECT COUNT(*) FROM folder_plant WHERE folderId = :folderId")
    suspend fun countByFolder(folderId: Long): Int

    @Query("SELECT IFNULL(MAX(sortOrder), -1) FROM folder_plant WHERE folderId = :folderId")
    suspend fun maxSortOrder(folderId: Long): Int

    /** 反查：这株植物属于哪些文件夹（详情页「加入文件夹」要预勾选） */
    @Query("SELECT folderId FROM folder_plant WHERE plantId = :plantId")
    fun observeFolderIdsForPlant(plantId: Long): Flow<List<Long>>

    @Query("SELECT folderId FROM folder_plant WHERE plantId = :plantId")
    suspend fun getFolderIdsForPlant(plantId: Long): List<Long>

    /** 文件夹内的全部 plantId（「全选」与「移动」用） */
    @Query(
        """
        SELECT fp.plantId FROM folder_plant fp
        INNER JOIN plant_record p ON p.id = fp.plantId
        WHERE fp.folderId = :folderId AND p.deletedAt IS NULL
        """,
    )
    suspend fun getPlantIdsInFolder(folderId: Long): List<Long>

    // ---------- 文件夹内的植物卡片 ----------

    /**
     * 文件夹内的植物卡片列表。
     *
     * 投影与 [PlantRecordDao.observePlantCards] 完全一致（`PlantCardRow`），
     * 所以详情页可以直接复用同一个卡片组件，不必为文件夹再造一套。
     *
     * 两点必须注意：
     *   - **`p.deletedAt IS NULL`**：植物是软删的，关联行不会自动消失，
     *     不滤掉就会出现「回收站里的植物还在文件夹里」
     *   - 搜索条件与植物列表保持同一套（中文名 / 拉丁名 / 科 / 属）
     */
    @Query(
        """
        SELECT
            p.id          AS plantId,
            p.name        AS name,
            p.latinName   AS latinName,
            p.family      AS family,
            p.genus       AS genus,
            p.category    AS category,
            p.confidence  AS confidence,
            p.updatedAt   AS updatedAt,
            (SELECT COUNT(*) FROM plant_observation o WHERE o.plantId = p.id)
                AS observationCount,
            (SELECT COUNT(*) FROM observation_image i
                INNER JOIN plant_observation o2 ON i.observationId = o2.id
                WHERE o2.plantId = p.id)
                AS photoCount,
            (SELECT i3.imagePath FROM observation_image i3
                INNER JOIN plant_observation o3 ON i3.observationId = o3.id
                WHERE o3.plantId = p.id
                ORDER BY o3.isPrimary DESC, o3.timestamp DESC, i3.sortOrder ASC
                LIMIT 1)
                AS coverPath
        FROM plant_record p
        INNER JOIN folder_plant fp ON fp.plantId = p.id
        WHERE fp.folderId = :folderId
          AND p.deletedAt IS NULL
          AND (:keyword = ''
               OR p.name LIKE '%' || :keyword || '%'
               OR IFNULL(p.latinName, '') LIKE '%' || :keyword || '%'
               OR IFNULL(p.family, '') LIKE '%' || :keyword || '%'
               OR IFNULL(p.genus, '') LIKE '%' || :keyword || '%')
        ORDER BY
            CASE WHEN :sort = 'JOINED'  THEN fp.addedAt   END DESC,
            CASE WHEN :sort = 'UPDATED' THEN p.updatedAt  END DESC,
            CASE WHEN :sort = 'NAME'    THEN p.name       END ASC
        """,
    )
    fun observeFolderPlantCards(
        folderId: Long,
        keyword: String,
        sort: String,
    ): Flow<List<PlantCardRow>>
}

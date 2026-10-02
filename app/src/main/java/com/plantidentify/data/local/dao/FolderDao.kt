package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.local.projection.FolderCardRow
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹主表的读写（v1.0.2 Phase 1）。
 *
 * 这里**没有任何一条 SQL 会写 plant_record** —— 「删文件夹不删植物」
 * 在 DAO 这一层就已经是结构性保证，而不是靠调用方自觉。
 */
@Dao
interface FolderDao {

    // ---------- 写入 ----------

    /** @return 新文件夹的 id */
    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    /**
     * 删除文件夹行。
     *
     * `folder_plant` 的关联行由外键 `ON DELETE CASCADE` 自动清掉；
     * `landscape_folder_data` 的扩展行同理。
     * **`plant_record` 不受任何影响** —— 没有任何外键从 folder 指向它。
     */
    @Query("DELETE FROM folder WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)

    /**
     * 只刷新「最后更新」时间。
     *
     * 成员增删改的是 `folder_plant`，但卡片按 `folder.updatedAt` 排序 ——
     * 不刷这一下，刚加完植物的文件夹会一直沉在列表底部。
     *
     * 与 `PlantRecordDao.touch` 同样刻意不用整体 `update(entity)`：
     * 那会把整行重写一遍，存在并发下覆盖别处刚写入字段的风险。
     */
    @Query("UPDATE folder SET updatedAt = :timestamp WHERE id = :folderId")
    suspend fun touch(folderId: Long, timestamp: Long = System.currentTimeMillis())

    // ---------- 读取 ----------

    @Query("SELECT * FROM folder WHERE id = :folderId")
    fun observeById(folderId: Long): Flow<FolderEntity?>

    @Query("SELECT * FROM folder WHERE id = :folderId")
    suspend fun getById(folderId: Long): FolderEntity?

    /** 全部文件夹（供「移动到其他文件夹」「加入其他文件夹」的选择列表用） */
    @Query("SELECT * FROM folder ORDER BY updatedAt DESC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folder ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    // ---------- 列表卡片 ----------

    /**
     * 文件夹列表卡片：一条 SQL 覆盖「类型筛选 + 关键词搜索 + 排序 + 成员数 + 封面」。
     *
     * 四个参数用「空值即不生效」的写法，与 `PlantRecordDao.searchPlantCards` 同一套路 ——
     * 这样主界面上切换筛选条件不会触发多次查询重建。
     *
     * - `type` 传 `FolderType.name`，空串表示「全部」
     * - `keyword` 匹配名称与描述
     * - `sort` 传 `FolderSort.name`（UPDATED / CREATED / NAME）
     *
     * **成员数与封面都必须过滤 `p.deletedAt IS NULL`** —— 植物是软删的，
     * 不滤掉已删的会出现「文件夹里躺着回收站的东西」。
     */
    @Query(
        """
        SELECT
            f.id          AS folderId,
            f.name        AS name,
            f.type        AS type,
            f.description AS description,
            f.createdAt   AS createdAt,
            f.updatedAt   AS updatedAt,
            (SELECT COUNT(*) FROM folder_plant fp
                INNER JOIN plant_record p ON fp.plantId = p.id
                WHERE fp.folderId = f.id AND p.deletedAt IS NULL)
                AS plantCount,
            COALESCE(f.coverImage, (
                SELECT i.imagePath FROM folder_plant fp2
                    INNER JOIN plant_record p2 ON p2.id = fp2.plantId AND p2.deletedAt IS NULL
                    INNER JOIN plant_observation o ON o.plantId = p2.id
                    INNER JOIN observation_image i ON i.observationId = o.id
                    WHERE fp2.folderId = f.id
                    ORDER BY fp2.sortOrder ASC, o.isPrimary DESC, o.timestamp DESC, i.sortOrder ASC
                    LIMIT 1))
                AS coverPath
        FROM folder f
        WHERE (:type = '' OR f.type = :type)
          AND (:keyword = ''
               OR f.name LIKE '%' || :keyword || '%'
               OR IFNULL(f.description, '') LIKE '%' || :keyword || '%')
        ORDER BY
            CASE WHEN :sort = 'UPDATED' THEN f.updatedAt END DESC,
            CASE WHEN :sort = 'CREATED' THEN f.createdAt END DESC,
            CASE WHEN :sort = 'NAME'    THEN f.name END ASC
        """,
    )
    fun observeFolderCards(
        type: String,
        keyword: String,
        sort: String,
    ): Flow<List<FolderCardRow>>

    /** 筛选行上的「全部」计数 */
    @Query("SELECT COUNT(*) FROM folder")
    fun observeTotalCount(): Flow<Int>

    /** 筛选行上各类型的计数（传 `FolderType.name`） */
    @Query("SELECT COUNT(*) FROM folder WHERE type = :type")
    fun observeCountByType(type: String): Flow<Int>
}

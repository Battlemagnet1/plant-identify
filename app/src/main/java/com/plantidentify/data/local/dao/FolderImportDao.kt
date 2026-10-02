package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.FolderImportDataEntity
import com.plantidentify.data.local.entity.FolderImportItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 协作文件夹的导入数据（v1.0.2 Phase 2）。
 *
 * 两张表放一个 DAO：它们的生命周期完全绑定（同一次导入产生一套数据，
 * 协作文件夹被删时一并级联消失），拆成两个只会让调用方每次都要同时注入两个对象。
 *
 * 与 [FolderDao] 一样，**这里没有任何一条 SQL 会写 `plant_record`** ——
 * 「导入合并只会软删、绝不物理删除植物」在 DAO 层就是结构性保证。
 */
@Dao
interface FolderImportDao {

    // ---------- folder_import_data（1:1 元信息） ----------

    /**
     * 写入或覆盖导入元信息。
     *
     * 用 REPLACE 是因为「重新导入一批数据到同一个协作文件夹」是合法操作：
     * 此时元信息应当被新的覆盖，而不是报唯一键冲突。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertData(data: FolderImportDataEntity)

    @Query("SELECT * FROM folder_import_data WHERE folderId = :folderId")
    suspend fun getData(folderId: Long): FolderImportDataEntity?

    /** 读取全部导入元信息（备份用） */
    @Query("SELECT * FROM folder_import_data")
    suspend fun getAllData(): List<FolderImportDataEntity>

    @Query("SELECT * FROM folder_import_data WHERE folderId = :folderId")
    fun observeData(folderId: Long): Flow<FolderImportDataEntity?>

    @Query("DELETE FROM folder_import_data WHERE folderId = :folderId")
    suspend fun deleteData(folderId: Long)

    // ---------- folder_import_item（逐条状态） ----------

    /**
     * 批量写入条目状态。
     *
     * 用 IGNORE 而非 REPLACE：同一条导入数据在同一文件夹里只该有一条状态记录，
     * 重复写入说明是同一批数据的重放，**不该覆盖用户已经做出的决定**。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FolderImportItemEntity>)

    /**
     * 查某文件夹下的全部条目状态。
     *
     * 一次性取回后由调用方按 `importPlantId` 建索引 —— 导入的记录数是
     * 一次导入的规模（几十到几百），远小于全库，不需要分页。
     */
    @Query("SELECT * FROM folder_import_item WHERE folderId = :folderId")
    suspend fun getItems(folderId: Long): List<FolderImportItemEntity>

    /** 读取全部导入条目状态（备份用） */
    @Query("SELECT * FROM folder_import_item")
    suspend fun getAllItems(): List<FolderImportItemEntity>

    @Query("SELECT * FROM folder_import_item WHERE folderId = :folderId")
    fun observeItems(folderId: Long): Flow<List<FolderImportItemEntity>>

    /** 只数「待用户确认」的条数 —— 卡片上那个小红点用得到 */
    @Query("SELECT COUNT(*) FROM folder_import_item WHERE folderId = :folderId AND status = :status")
    suspend fun countByStatus(folderId: Long, status: String): Int

    @Query("SELECT COUNT(*) FROM folder_import_item WHERE folderId = :folderId AND status = :status")
    fun observeCountByStatus(folderId: Long, status: String): Flow<Int>

    /**
     * 更新单条的处理结果。
     *
     * 刻意不用整体 `update(entity)`（与 `FolderDao.touch` 同一理由）：
     * 只改该改的几列，避免并发下把别处刚写的字段覆盖回去。
     */
    @Query(
        """
        UPDATE folder_import_item
        SET status = :status,
            matchedPlantId = :matchedPlantId,
            matchLevel = :matchLevel,
            decidedAt = :decidedAt
        WHERE folderId = :folderId AND importPlantId = :importPlantId
        """,
    )
    suspend fun updateStatus(
        folderId: Long,
        importPlantId: Long,
        status: String,
        matchedPlantId: Long?,
        matchLevel: Int?,
        decidedAt: Long,
    )

    /** 解析后立刻记下判定级别，但**不改状态**（仍是 PENDING，等用户决定） */
    @Query(
        """
        UPDATE folder_import_item
        SET matchLevel = :matchLevel, matchedPlantId = :matchedPlantId
        WHERE folderId = :folderId AND importPlantId = :importPlantId
        """,
    )
    suspend fun updateMatch(
        folderId: Long,
        importPlantId: Long,
        matchLevel: Int?,
        matchedPlantId: Long?,
    )

    /** 重算已处理条数时用：一次拿到各状态的数量 */
    @Query("SELECT status, COUNT(*) AS count FROM folder_import_item WHERE folderId = :folderId GROUP BY status")
    suspend fun countGroupedByStatus(folderId: Long): List<StatusCount>

    @Query("DELETE FROM folder_import_item WHERE folderId = :folderId")
    suspend fun deleteItems(folderId: Long)
}

/** `countGroupedByStatus` 的投影 */
data class StatusCount(
    val status: String,
    val count: Int,
)

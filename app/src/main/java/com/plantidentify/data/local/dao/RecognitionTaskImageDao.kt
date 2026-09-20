package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.plantidentify.data.local.entity.RecognitionTaskImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 任务照片表。
 *
 * 方法集**刻意与 [ObservationImageDao] 对齐**（同样的 `reorder` / `countByPath` /
 * `getAllPaths` 范式）—— 因为任务完成后图片行会整体迁移到那张表，
 * 两边口径不一致的话，迁移时就容易漏掉引用计数这类保护。
 */
@Dao
interface RecognitionTaskImageDao {

    // ---------------- 写入 ----------------

    @Insert
    suspend fun insert(image: RecognitionTaskImageEntity): Long

    @Insert
    suspend fun insertAll(images: List<RecognitionTaskImageEntity>): List<Long>

    @Query("DELETE FROM recognition_task_image WHERE id = :imageId")
    suspend fun deleteById(imageId: Long)

    @Query("DELETE FROM recognition_task_image WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    @Query("UPDATE recognition_task_image SET sortOrder = :sortOrder, role = :role WHERE id = :imageId")
    suspend fun updateOrderAndRole(imageId: Long, sortOrder: Int, role: String)

    @Query("UPDATE recognition_task_image SET sortOrder = :sortOrder WHERE id = :imageId")
    suspend fun updateSortOrder(imageId: Long, sortOrder: Int)

    /** 批量重排：按传入顺序把 sortOrder 依次设为 0,1,2…（与观察照片同一实现） */
    @Transaction
    suspend fun reorder(imageIds: List<Long>) {
        imageIds.forEachIndexed { index, id ->
            updateSortOrder(id, index)
        }
    }

    @Query("DELETE FROM recognition_task_image")
    suspend fun clearAll()

    // ---------------- 读取 ----------------

    @Query("SELECT * FROM recognition_task_image WHERE taskId = :taskId ORDER BY sortOrder ASC")
    suspend fun getByTask(taskId: Long): List<RecognitionTaskImageEntity>

    @Query("SELECT * FROM recognition_task_image WHERE taskId = :taskId ORDER BY sortOrder ASC")
    fun observeByTask(taskId: Long): Flow<List<RecognitionTaskImageEntity>>

    /**
     * 全部任务图片路径。
     *
     * **必须有这个方法**：备份恢复流程里的 `pruneOrphanFiles()` 会删掉
     * `filesDir/images` 下一切「未被 `observation_image` 引用」的文件 ——
     * 如果它不知道任务图片的存在，用户一恢复备份，**在途任务的图片就全没了**
     * （任务行还在，路径全成死链）。加这一份引用集是那个清理逻辑的前置条件。
     */
    @Query("SELECT imagePath FROM recognition_task_image")
    suspend fun getAllPaths(): List<String>

    /** 某个路径还被几条任务行引用 —— 删文件前查 */
    @Query("SELECT COUNT(*) FROM recognition_task_image WHERE imagePath = :imagePath")
    suspend fun countByPath(imagePath: String): Int

    @Query("SELECT IFNULL(MAX(sortOrder), -1) FROM recognition_task_image WHERE taskId = :taskId")
    suspend fun maxSortOrder(taskId: Long): Int

    @Query("SELECT COUNT(*) FROM recognition_task_image WHERE taskId = :taskId")
    suspend fun countByTask(taskId: Long): Int
}

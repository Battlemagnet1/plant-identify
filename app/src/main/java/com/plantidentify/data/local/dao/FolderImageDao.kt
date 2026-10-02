package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.FolderImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 景观照片的读写（v1.0.2 Phase 3）。
 *
 * 这些照片挂在**文件夹**上而不是观察上，所以这个 DAO 里没有任何语句会碰
 * `plant_record` / `plant_observation` / `observation_image` ——
 * 景观照片与植物档案是两条互不干扰的线（需求 §六 要求明确区分）。
 */
@Dao
interface FolderImageDao {

    @Insert
    suspend fun insert(image: FolderImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<FolderImageEntity>)

    @Query("SELECT * FROM folder_image WHERE folderId = :folderId ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(folderId: Long): List<FolderImageEntity>

    @Query("SELECT * FROM folder_image WHERE folderId = :folderId ORDER BY sortOrder ASC, id ASC")
    fun observeAll(folderId: Long): Flow<List<FolderImageEntity>>

    @Query("SELECT COUNT(*) FROM folder_image WHERE folderId = :folderId")
    fun observeCount(folderId: Long): Flow<Int>

    @Query("SELECT IFNULL(MAX(sortOrder), -1) FROM folder_image WHERE folderId = :folderId")
    suspend fun maxSortOrder(folderId: Long): Int

    @Query("DELETE FROM folder_image WHERE id = :imageId")
    suspend fun deleteById(imageId: Long)

    @Query("DELETE FROM folder_image WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    // ---------- 备份 / 恢复 ----------

    @Query("SELECT * FROM folder_image")
    suspend fun getAllForBackup(): List<FolderImageEntity>

    /** 恢复时用 —— 同时覆盖 sortOrder，所以是 REPLACE */
    @Query("SELECT * FROM folder_image WHERE folderId = :folderId")
    suspend fun getForFolder(folderId: Long): List<FolderImageEntity>
}

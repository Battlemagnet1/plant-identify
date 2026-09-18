package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.plantidentify.data.local.entity.ObservationImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationImageDao {

    // ---------- 写入 ----------

    @Insert
    suspend fun insert(image: ObservationImageEntity): Long

    @Insert
    suspend fun insertAll(images: List<ObservationImageEntity>): List<Long>

    @Query("DELETE FROM observation_image WHERE id = :imageId")
    suspend fun deleteById(imageId: Long)

    @Query("DELETE FROM observation_image WHERE observationId = :observationId")
    suspend fun deleteByObservation(observationId: Long)

    @Query("UPDATE observation_image SET sortOrder = :sortOrder, role = :role WHERE id = :imageId")
    suspend fun updateOrderAndRole(imageId: Long, sortOrder: Int, role: String)

    @Query("UPDATE observation_image SET sortOrder = :sortOrder WHERE id = :imageId")
    suspend fun updateSortOrder(imageId: Long, sortOrder: Int)

    /** 批量重排：按传入顺序把 sortOrder 依次设为 0,1,2… */
    @Transaction
    suspend fun reorder(imageIds: List<Long>) {
        imageIds.forEachIndexed { index, id ->
            updateSortOrder(id, index)
        }
    }

    // ---------- 读取 ----------

    @Query("SELECT * FROM observation_image WHERE observationId = :observationId ORDER BY sortOrder ASC")
    fun observeByObservation(observationId: Long): Flow<List<ObservationImageEntity>>

    @Query("SELECT * FROM observation_image WHERE observationId = :observationId ORDER BY sortOrder ASC")
    suspend fun getByObservation(observationId: Long): List<ObservationImageEntity>

    /**
     * 取某株植物的全部图片。
     *
     * 用途：删除植物时清理磁盘文件。
     * Room 的外键 CASCADE 只删除数据库行，不会删除文件系统上的图片，
     * 必须显式取路径再删文件，否则会残留「孤儿图片」占用存储。
     */
    @Query(
        """
        SELECT observation_image.* FROM observation_image
        INNER JOIN plant_observation
            ON observation_image.observationId = plant_observation.id
        WHERE plant_observation.plantId = :plantId
        """,
    )
    suspend fun getImagesForPlant(plantId: Long): List<ObservationImageEntity>

    @Query("SELECT * FROM observation_image")
    suspend fun getAll(): List<ObservationImageEntity>

    // ---------- 统计 ----------

    /** 照片数 = observation_image 行数 */
    @Query("SELECT COUNT(*) FROM observation_image")
    fun observeImageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM observation_image WHERE observationId = :observationId")
    suspend fun countByObservation(observationId: Long): Int

    @Query("SELECT IFNULL(MAX(sortOrder), -1) FROM observation_image WHERE observationId = :observationId")
    suspend fun maxSortOrder(observationId: Long): Int
}

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

    @Query("SELECT * FROM observation_image WHERE id = :imageId")
    suspend fun getById(imageId: Long): ObservationImageEntity?

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

    // ---------- 备份与恢复 ----------

    @Query("DELETE FROM observation_image")
    suspend fun clearAll()

    /**
     * 某株植物全部观察的 id。
     *
     * 删除植物时要先把每条观察的图片行删掉，再删观察行。
     */
    @Query("SELECT id FROM plant_observation WHERE plantId = :plantId")
    suspend fun getObservationIdsForPlant(plantId: Long): List<Long>

    /**
     * 某个图片路径还被多少行引用。
     *
     * 删除文件前必须先问一句 —— 同一张图可能被多条观察共享
     * （例如把同一批草稿照片保存到了两个档案），
     * 引用计数不为 0 时删文件会让其它档案的照片变成空白。
     */
    @Query("SELECT COUNT(*) FROM observation_image WHERE imagePath = :imagePath")
    suspend fun countByPath(imagePath: String): Int

    // ---------- 统计 ----------

    /**
     * 照片数 = observation_image 行数，**只数未被删除的档案下的照片**。
     *
     * 两级 JOIN 缺一不可：图片挂在观察上，观察挂在档案上，
     * 而删除标记只在档案上。
     */
    @Query(
        """
        SELECT COUNT(*) FROM observation_image i
        INNER JOIN plant_observation o ON i.observationId = o.id
        INNER JOIN plant_record p ON o.plantId = p.id
        WHERE p.deletedAt IS NULL
        """,
    )
    fun observeImageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM observation_image WHERE observationId = :observationId")
    suspend fun countByObservation(observationId: Long): Int

    @Query("SELECT IFNULL(MAX(sortOrder), -1) FROM observation_image WHERE observationId = :observationId")
    suspend fun maxSortOrder(observationId: Long): Int
}

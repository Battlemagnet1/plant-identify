package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.relation.ObservationWithImages
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantObservationDao {

    // ---------- 写入 ----------

    @Insert
    suspend fun insert(observation: PlantObservationEntity): Long

    @Update
    suspend fun update(observation: PlantObservationEntity)

    @Delete
    suspend fun delete(observation: PlantObservationEntity)

    @Query("DELETE FROM plant_observation WHERE id = :observationId")
    suspend fun deleteById(observationId: Long)

    // ---------- 读取 ----------

    @Query("SELECT * FROM plant_observation WHERE plantId = :plantId ORDER BY timestamp DESC")
    fun observeByPlant(plantId: Long): Flow<List<PlantObservationEntity>>

    @Query("SELECT * FROM plant_observation WHERE plantId = :plantId ORDER BY timestamp DESC")
    suspend fun getByPlant(plantId: Long): List<PlantObservationEntity>

    @Query("SELECT * FROM plant_observation WHERE id = :observationId")
    suspend fun getById(observationId: Long): PlantObservationEntity?

    @Query("SELECT * FROM plant_observation WHERE id = :observationId")
    fun observeById(observationId: Long): Flow<PlantObservationEntity?>

    /** 最近一次观察 —— 用于植物列表卡片的默认封面图 */
    @Query("SELECT * FROM plant_observation WHERE plantId = :plantId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForPlant(plantId: Long): PlantObservationEntity?

    @Query("SELECT COUNT(*) FROM plant_observation WHERE plantId = :plantId")
    suspend fun countByPlant(plantId: Long): Int

    // ---------- 代表观察 ----------

    @Query("UPDATE plant_observation SET isPrimary = 0 WHERE plantId = :plantId")
    suspend fun clearPrimary(plantId: Long)

    @Query("UPDATE plant_observation SET isPrimary = 1 WHERE id = :observationId")
    suspend fun markPrimary(observationId: Long)

    /** 设置代表观察：先清空该植物的标记，再标记目标记录 */
    @Transaction
    suspend fun setPrimaryObservation(plantId: Long, observationId: Long) {
        clearPrimary(plantId)
        markPrimary(observationId)
    }

    // ---------- 统计 ----------

    /** 观察次数 = plant_observation 行数 */
    @Query("SELECT COUNT(*) FROM plant_observation")
    fun observeObservationCount(): Flow<Int>

    // ---------- 关系查询 ----------

    @Transaction
    @Query("SELECT * FROM plant_observation WHERE id = :observationId")
    fun observeWithImages(observationId: Long): Flow<ObservationWithImages?>

    @Transaction
    @Query("SELECT * FROM plant_observation WHERE plantId = :plantId ORDER BY timestamp DESC")
    fun observeByPlantWithImages(plantId: Long): Flow<List<ObservationWithImages>>
}

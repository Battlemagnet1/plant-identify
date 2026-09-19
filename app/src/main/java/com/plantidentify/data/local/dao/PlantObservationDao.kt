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

    /** 删除某株植物的全部观察（删植物时调用；图片行由调用方先处理） */
    @Query("DELETE FROM plant_observation WHERE plantId = :plantId")
    suspend fun deleteByPlant(plantId: Long)

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

    /**
     * 全部已记录的地点名（去重）。
     *
     * 地点筛选的候选取自这里，而不是写死一份行政区划表 ——
     * 用户去过哪些地方，候选里才有那些地方。
     * 只取地名不取坐标：筛选是给人用的，人记的是「在哪」而不是经纬度。
     */
    @Query(
        "SELECT DISTINCT locationName FROM plant_observation " +
            "WHERE locationName IS NOT NULL AND locationName != '' ORDER BY locationName",
    )
    fun observeLocationNames(): Flow<List<String>>

    // ---------- 备份与恢复 ----------

    @Query("SELECT * FROM plant_observation ORDER BY id ASC")
    suspend fun getAll(): List<PlantObservationEntity>

    @Query("DELETE FROM plant_observation")
    suspend fun clearAll()

    // ---------- 关系查询 ----------

    @Transaction
    @Query("SELECT * FROM plant_observation WHERE id = :observationId")
    fun observeWithImages(observationId: Long): Flow<ObservationWithImages?>

    @Transaction
    @Query("SELECT * FROM plant_observation WHERE plantId = :plantId ORDER BY timestamp DESC")
    fun observeByPlantWithImages(plantId: Long): Flow<List<ObservationWithImages>>
}

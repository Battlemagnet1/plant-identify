package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.local.relation.PlantWithObservations
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantRecordDao {

    // ---------- 写入 ----------

    /** @return 新记录的 id */
    @Insert
    suspend fun insert(plant: PlantRecordEntity): Long

    @Update
    suspend fun update(plant: PlantRecordEntity)

    @Delete
    suspend fun delete(plant: PlantRecordEntity)

    @Query("DELETE FROM plant_record WHERE id = :plantId")
    suspend fun deleteById(plantId: Long)

    // ---------- 读取 ----------

    @Query("SELECT * FROM plant_record ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlantRecordEntity>>

    @Query("SELECT * FROM plant_record WHERE id = :plantId")
    fun observeById(plantId: Long): Flow<PlantRecordEntity?>

    @Query("SELECT * FROM plant_record WHERE id = :plantId")
    suspend fun getById(plantId: Long): PlantRecordEntity?

    /**
     * 搜索：支持中文名 / 拉丁学名 / 科 / 属 / 植物类型 / 备注（规格书第十九节）。
     * 空查询返回全部。
     */
    @Query(
        """
        SELECT * FROM plant_record
        WHERE :keyword = ''
           OR name LIKE '%' || :keyword || '%'
           OR IFNULL(latinName, '') LIKE '%' || :keyword || '%'
           OR IFNULL(family, '') LIKE '%' || :keyword || '%'
           OR IFNULL(genus, '') LIKE '%' || :keyword || '%'
           OR IFNULL(category, '') LIKE '%' || :keyword || '%'
           OR IFNULL(note, '') LIKE '%' || :keyword || '%'
        ORDER BY updatedAt DESC
        """,
    )
    fun search(keyword: String): Flow<List<PlantRecordEntity>>

    // ---------- 重复植物归并（规格书第十四点五节）----------
    // 匹配优先级：拉丁学名 → 中文名+科+属 → 中文名+科 → 模糊匹配
    // 任何情况下都不得自动合并两条已有 PlantRecord，只能由用户确认。

    @Query(
        """
        SELECT * FROM plant_record
        WHERE latinName IS NOT NULL
          AND LOWER(TRIM(latinName)) = LOWER(TRIM(:latinName))
        LIMIT 1
        """,
    )
    suspend fun findByLatinName(latinName: String): PlantRecordEntity?

    @Query(
        """
        SELECT * FROM plant_record
        WHERE name = :name
          AND IFNULL(family, '') = IFNULL(:family, '')
          AND IFNULL(genus, '') = IFNULL(:genus, '')
        LIMIT 1
        """,
    )
    suspend fun findByNameFamilyGenus(
        name: String,
        family: String?,
        genus: String?,
    ): PlantRecordEntity?

    @Query(
        """
        SELECT * FROM plant_record
        WHERE name = :name
          AND IFNULL(family, '') = IFNULL(:family, '')
        LIMIT 1
        """,
    )
    suspend fun findByNameAndFamily(name: String, family: String?): PlantRecordEntity?

    /** 低置信度候选：仅用于「可能已存在相同植物」提示，必须由用户确认 */
    @Query("SELECT * FROM plant_record WHERE name LIKE '%' || :name || '%' LIMIT 5")
    suspend fun findFuzzyByName(name: String): List<PlantRecordEntity>

    // ---------- 统计（规格书第二十节，口径 2026-09-18 已确认）----------

    /** 不同植物 = plant_record 行数 */
    @Query("SELECT COUNT(*) FROM plant_record")
    fun observeDistinctPlantCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT family) FROM plant_record WHERE family IS NOT NULL AND family != ''")
    fun observeFamilyCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT genus) FROM plant_record WHERE genus IS NOT NULL AND genus != ''")
    fun observeGenusCount(): Flow<Int>

    // ---------- 关系查询 ----------

    @Transaction
    @Query("SELECT * FROM plant_record WHERE id = :plantId")
    fun observePlantWithObservations(plantId: Long): Flow<PlantWithObservations?>

    @Transaction
    @Query("SELECT * FROM plant_record WHERE id = :plantId")
    fun observePlantDetail(plantId: Long): Flow<PlantWithObservationsAndImages?>

    @Transaction
    @Query("SELECT * FROM plant_record ORDER BY updatedAt DESC")
    fun observeAllWithObservations(): Flow<List<PlantWithObservations>>
}

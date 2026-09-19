package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.local.projection.PlantCardRow
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

    // ---------- 列表与搜索（规格书第十六、十九节）----------

    /**
     * 植物列表卡片。
     *
     * 观察次数与照片数用子查询算好 —— 列表页不该把全部观察与图片读进内存再统计。
     * 封面图优先取「代表观察」里的第一张，其次取最近一次观察的第一张。
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
        ORDER BY p.updatedAt DESC
        """,
    )
    fun observePlantCards(): Flow<List<PlantCardRow>>

    /**
     * 搜索 + 筛选（规格书第十九节）。
     *
     * 搜索覆盖：中文名 / 拉丁学名 / 科 / 属 / 植物类型 / 备注。
     * 筛选覆盖：科 / 属 / 日期区间 / 地点。
     *
     * 日期与地点是**观察**的属性而不是档案的，所以用 EXISTS 子查询匹配 ——
     * 只要该植物有一次观察落在区间内（或地点命中），这株植物就应出现在结果里。
     * 传空串或 0 表示该条件不生效，这样一条 SQL 就能覆盖全部筛选组合。
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
        WHERE (
                :keyword = ''
                OR p.name LIKE '%' || :keyword || '%'
                OR IFNULL(p.latinName, '') LIKE '%' || :keyword || '%'
                OR IFNULL(p.family, '') LIKE '%' || :keyword || '%'
                OR IFNULL(p.genus, '') LIKE '%' || :keyword || '%'
                OR IFNULL(p.category, '') LIKE '%' || :keyword || '%'
                OR IFNULL(p.note, '') LIKE '%' || :keyword || '%'
              )
          AND (:family = '' OR IFNULL(p.family, '') = :family)
          AND (:genus = '' OR IFNULL(p.genus, '') = :genus)
          AND (:fromDate = 0 OR EXISTS (
                SELECT 1 FROM plant_observation o4
                WHERE o4.plantId = p.id AND o4.timestamp >= :fromDate))
          AND (:toDate = 0 OR EXISTS (
                SELECT 1 FROM plant_observation o5
                WHERE o5.plantId = p.id AND o5.timestamp <= :toDate))
          AND (:place = '' OR EXISTS (
                SELECT 1 FROM plant_observation o6
                WHERE o6.plantId = p.id
                  AND IFNULL(o6.locationName, '') LIKE '%' || :place || '%'))
        ORDER BY p.updatedAt DESC
        """,
    )
    fun searchPlantCards(
        keyword: String,
        family: String,
        genus: String,
        fromDate: Long,
        toDate: Long,
        place: String,
    ): Flow<List<PlantCardRow>>

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

    /**
     * 一次性读出全部档案，含观察与图片（导出与备份用）。
     *
     * 按 `createdAt` **升序**而不是列表页的 `updatedAt` 降序：
     * 报告里的编号（01、02…）与备份内容都应当稳定且有时间顺序，
     * 按「最近修改」排会让同一份数据每次导出得到不同的编号。
     */
    @Transaction
    @Query("SELECT * FROM plant_record ORDER BY createdAt ASC")
    suspend fun getAllWithObservationsAndImages(): List<PlantWithObservationsAndImages>

    // ---------- 备份与恢复 ----------

    /**
     * 全量读出（含已删除标记之外的一切）。
     *
     * 备份必须连 id 一起带走 —— 恢复时按原 id 写回，三张表的外键关系才不会错位。
     */
    @Query("SELECT * FROM plant_record ORDER BY id ASC")
    suspend fun getAll(): List<PlantRecordEntity>

    @Query("DELETE FROM plant_record")
    suspend fun clearAll()
}

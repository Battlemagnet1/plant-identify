package com.plantidentify.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.plantidentify.data.local.dao.ObservationImageDao
import com.plantidentify.data.local.dao.PlantObservationDao
import com.plantidentify.data.local.dao.PlantRecordDao
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity

/**
 * plant Identify 本地数据库。
 *
 * 三张表的关系（规格书第十四节）：
 *
 * ```
 * plant_record（一种植物一条）
 *   └── plant_observation（一次观察一条）
 *         └── observation_image（一张照片一条）
 * ```
 *
 * 迁移策略（重要）：
 *   - [exportSchema] 必须为 true，schema JSON 提交到版本库做版本追溯
 *     （只含表结构，不含任何用户数据）
 *   - 每次提升 version 都必须提供显式 Migration
 *   - **禁止使用 fallbackToDestructiveMigration()** —— 用户档案是长期资产，
 *     破坏性迁移会在升级时清空全部记录
 *   - 外键 CASCADE 只作用于数据库行；磁盘上的图片文件需在 Repository 层显式清理
 */
@Database(
    entities = [
        PlantRecordEntity::class,
        PlantObservationEntity::class,
        ObservationImageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PlantIdentifyDatabase : RoomDatabase() {

    abstract fun plantRecordDao(): PlantRecordDao

    abstract fun plantObservationDao(): PlantObservationDao

    abstract fun observationImageDao(): ObservationImageDao

    companion object {
        const val NAME = "plant_identify.db"
    }
}

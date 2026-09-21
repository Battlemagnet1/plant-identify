package com.plantidentify.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.data.local.dao.CleaningStateDao
import com.plantidentify.data.local.dao.ImageFingerprintDao
import com.plantidentify.data.local.dao.ObservationImageDao
import com.plantidentify.data.local.dao.PlantObservationDao
import com.plantidentify.data.local.dao.PlantRecordDao
import com.plantidentify.data.local.dao.RecognitionTaskDao
import com.plantidentify.data.local.dao.RecognitionTaskImageDao
import com.plantidentify.data.local.entity.CleaningIssueEntity
import com.plantidentify.data.local.entity.CleaningStateEntity
import com.plantidentify.data.local.entity.ImageFingerprintEntity
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantObservationEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.local.entity.RecognitionTaskEntity
import com.plantidentify.data.local.entity.RecognitionTaskImageEntity

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
        RecognitionTaskEntity::class,
        RecognitionTaskImageEntity::class,
        CleaningIssueEntity::class,
        ImageFingerprintEntity::class,
        CleaningStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PlantIdentifyDatabase : RoomDatabase() {

    abstract fun plantRecordDao(): PlantRecordDao

    abstract fun plantObservationDao(): PlantObservationDao

    abstract fun observationImageDao(): ObservationImageDao

    /**
     * 识别任务队列（v3 新增）。
     *
     * 这两张表与档案表**没有外键往来** —— 任务照片在识别完成后是被
     * 「搬」进档案的（行迁移），不是「指向」档案。这样删植物时
     * 不会牵连任务，删任务也不会影响已归档的照片。
     */
    abstract fun recognitionTaskDao(): RecognitionTaskDao

    abstract fun recognitionTaskImageDao(): RecognitionTaskImageDao

    /**
     * 清洗问题（v5 新增）。用户点过「忽略」的决策就存在这张表里，
     * 靠 `fingerprint` 唯一索引保证每次扫描不会重问。
     */
    abstract fun cleaningIssueDao(): CleaningIssueDao

    /** 照片 SHA-256 缓存（v5）。纯派生数据，丢了只是下次检查慢一点 */
    abstract fun imageFingerprintDao(): ImageFingerprintDao

    /** 扫描游标（v5）。单行表，记住「上次扫到什么时候」 */
    abstract fun cleaningStateDao(): CleaningStateDao

    companion object {
        const val NAME = "plant_identify.db"
    }
}

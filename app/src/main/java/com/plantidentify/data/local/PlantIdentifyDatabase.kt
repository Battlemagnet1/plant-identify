package com.plantidentify.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.data.local.dao.CleaningStateDao
import com.plantidentify.data.local.dao.FolderDao
import com.plantidentify.data.local.dao.FolderImportDao
import com.plantidentify.data.local.dao.FolderPlantDao
import com.plantidentify.data.local.dao.ImageFingerprintDao
import com.plantidentify.data.local.dao.LandscapeFolderDataDao
import com.plantidentify.data.local.dao.ObservationImageDao
import com.plantidentify.data.local.dao.PlantObservationDao
import com.plantidentify.data.local.dao.PlantRecordDao
import com.plantidentify.data.local.dao.RecognitionTaskDao
import com.plantidentify.data.local.dao.RecognitionTaskImageDao
import com.plantidentify.data.local.entity.CleaningIssueEntity
import com.plantidentify.data.local.entity.CleaningStateEntity
import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.local.entity.FolderImportDataEntity
import com.plantidentify.data.local.entity.FolderImportItemEntity
import com.plantidentify.data.local.entity.FolderPlantEntity
import com.plantidentify.data.local.entity.ImageFingerprintEntity
import com.plantidentify.data.local.entity.LandscapeFolderDataEntity
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
 * 文件夹系统（v8，v1.0.2 Phase 1）在这三条之外**并列**长出两条边：
 *
 * ```
 * plant_record ──N:M── folder_plant ──N── folder
 *                                            ├── landscape_folder_data（1:1，景观用）
 *                                            └── folder_import_data（1:1，导入用，v9）
 *                                                     └── folder_import_item（导入逐条状态，v9）
 * ```
 *
 * 规律是：**`folder` 保持通用，各类型的专属字段各自一张 1:1 扩展表** ——
 * 以后再加文件夹类型就是再加一张扩展表，不动主表。
 *
 * 关键区别：**folder 与 plant_record 之间只有「关联」，没有「归属」** ——
 * 删文件夹不会影响任何植物档案（没有任何从 folder 指向 plant_record 的外键）。
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
        FolderEntity::class,
        FolderPlantEntity::class,
        LandscapeFolderDataEntity::class,
        FolderImportDataEntity::class,
        FolderImportItemEntity::class,
    ],
    version = 9,
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

    /**
     * 文件夹与它的关联表（v8 新增，v1.0.2 Phase 1）。
     *
     * [FolderEntity] 是**通用容器**（景观 / 协作 / 自定义三种类型共用），
     * [FolderPlantEntity] 是它与植物档案的**多对多**关联 ——
     * 一株植物可以同时属于多个文件夹，而库里始终只有一条 PlantRecord。
     *
     * 注意 [FolderPlantDao] 里没有任何语句会写 `plant_record`：
     * 「删文件夹不删植物」在 DAO 这一层就是结构性保证。
     *
     * 景观扩展表 [LandscapeFolderDataEntity] 的 DAO 在 **Phase 2 才建** ——
     * Phase 1 的注释写的是「本阶段只把结构建好」，那时确实没有调用方；
     * Phase 2 的备份需要读它（景观数据不随备份走的话，
     * 用户换机后景观文件夹会退化成普通文件夹），它才成为必要。
     */
    abstract fun folderDao(): FolderDao

    abstract fun folderPlantDao(): FolderPlantDao

    abstract fun landscapeFolderDataDao(): LandscapeFolderDataDao

    /**
     * 协作文件夹的导入数据（v9 新增，v1.0.2 Phase 2）。
     *
     * [FolderImportDataEntity] 记录「这批数据从哪来、导入了多少、处理到哪一步」，
     * [FolderImportItemEntity] 记录**每一条导入植物**的处理状态
     * （待确认 / 已合并 / 保留为新）。
     *
     * 同样地，这里没有任何语句会写 `plant_record` ——
     * 导入合并走的是既有的软删合并路径（`PlantRepository.mergeInto`），
     * 不会物理删除任何植物。
     */
    abstract fun folderImportDao(): FolderImportDao

    companion object {
        const val NAME = "plant_identify.db"
    }
}

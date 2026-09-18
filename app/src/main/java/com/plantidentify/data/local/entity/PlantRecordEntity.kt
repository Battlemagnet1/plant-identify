package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 植物档案（PlantRecord）—— 一种植物一条记录，长期保存。
 *
 * 与 [PlantObservationEntity] 的区别：
 *   - PlantRecord  = 「这是哪一种植物」，去重后的物种档案
 *   - PlantObservation = 「我某一次在哪儿看到了它」，一次观察一条
 *
 * 统计口径（2026-09-18 已确认）：
 *   不同植物 = 本表行数；观察次数 = plant_observation 行数；照片数 = observation_image 行数。
 *   规格书中原来含混的「植物记录」一词已弃用。
 */
@Entity(
    tableName = "plant_record",
    indices = [
        // 重复植物归并的第一优先级：拉丁学名
        Index(value = ["latinName"]),
        // 第二、三优先级：中文名 + 科 + 属 / 中文名 + 科
        Index(value = ["name", "family", "genus"]),
    ],
)
data class PlantRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 中文名称 */
    val name: String,

    /** 拉丁学名（归并匹配的第一优先级，务必尽量填全） */
    val latinName: String? = null,

    /** 科 */
    val family: String? = null,

    /** 属 */
    val genus: String? = null,

    /** 植物类型，如「落叶灌木或小乔木」 */
    val category: String? = null,

    /** 最近一次识别的 AI 置信度（0.0 ~ 1.0） */
    val confidence: Double = 0.0,

    // ---- 以下为 TextProvider 生成的植物百科内容 ----

    /** 植物简介 */
    val description: String? = null,

    /** 形态特征 */
    val morphologicalFeatures: String? = null,

    /** 生长习性 */
    val growthHabits: String? = null,

    /** 花期 */
    val floweringPeriod: String? = null,

    /** 果期 */
    val fruitingPeriod: String? = null,

    /** 园林用途 */
    val landscapeUses: String? = null,

    /** 养护建议 */
    val careAdvice: String? = null,

    /** 文字分析状态 —— 失败时基础识别结果仍须保留 */
    val analysisStatus: AnalysisStatus = AnalysisStatus.NOT_REQUESTED,

    /** 用户备注 */
    val note: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
)

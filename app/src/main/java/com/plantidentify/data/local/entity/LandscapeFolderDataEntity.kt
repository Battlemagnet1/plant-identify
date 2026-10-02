package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 景观文件夹的扩展数据（1:1，v1.0.2 Phase 1 只建结构）。
 *
 * ## 为什么不把这些字段直接加进 folder 表
 *
 * [FolderEntity] 是**通用容器** —— 三种类型共用同一张表、同一套增删改查。
 * 把景观专用字段混进去会有两个后果：自定义文件夹也带着一堆永远为空的列；
 * 以后每加一种文件夹类型（课程、比赛、协作…）就要再往 folder 加一批列。
 *
 * 拆成 1:1 扩展表后，「新增类型 = 新增一张扩展表」，folder 表保持干净。
 *
 * ## Phase 1 刻意不建 DAO
 *
 * 本阶段只要求「把结构建好」，没有任何代码读写它 —— 建一个无人调用的 DAO
 * 就是死代码。景观照片、植物配置统计、AI 分析、报告都在 Phase 3。
 */
@Entity(
    tableName = "landscape_folder_data",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LandscapeFolderDataEntity(
    @PrimaryKey
    val folderId: Long,

    /** 场地位置（如「华南农业大学 第三教学楼前」） */
    val location: String? = null,

    /** 项目 / 场地描述 */
    val landscapeDescription: String? = null,

    /** 项目类型（校园 / 公园 / 小区 / 景区…） */
    val projectType: String? = null,

    /**
     * AI 景观分析结果（JSON）。
     *
     * 分析一次就存下来 —— 每次打开页面都请求一次 API 既慢又费钱，
     * 而分析结果在植物数据不变时是稳定的。
     */
    val analysisResult: String? = null,

    val analysisModel: String? = null,

    val analysisUpdatedAt: Long? = null,

    /**
     * 分析所依据的数据版本。
     *
     * 植物数据发生重大变化后（增删了成员），据此判断「需要重新分析」——
     * 光看 `analysisUpdatedAt` 无法区分「分析过之后成员变了」这种情况。
     */
    val analysisVersion: Int? = null,
)

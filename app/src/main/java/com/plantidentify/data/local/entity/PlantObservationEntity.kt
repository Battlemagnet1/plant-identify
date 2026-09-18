package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 观察记录（PlantObservation）—— 用户某一次对某株植物的观察/识别。
 *
 * 同一种植物可以拥有长期、多次、多地点的观察历史；
 * 每次识别都新增一条本记录，绝不复写历史观察。
 *
 * 同一观察的「补图重新识别」不会新建记录，而是更新当前记录
 * （见规格书第十四点五节「同一观察的重新识别」）。
 */
@Entity(
    tableName = "plant_observation",
    foreignKeys = [
        ForeignKey(
            entity = PlantRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["plantId"],
            onDelete = ForeignKey.CASCADE,
            // 注意：数据库级联只删除数据库行，
            // 磁盘上的图片文件需要在 Repository 层显式清理，
            // 否则会持续残留「孤儿图片」占用存储。
        ),
    ],
    indices = [
        Index(value = ["plantId"]),
        Index(value = ["timestamp"]),
    ],
)
data class PlantObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 所属植物档案 id */
    val plantId: Long,

    /** 观察时间（epoch millis） */
    val timestamp: Long,

    /** 纬度 —— 位置为可选功能，用户拒绝授权时为 null */
    val latitude: Double? = null,

    /** 经度 */
    val longitude: Double? = null,

    /**
     * 反向地理编码得到的地名，如「某校园」。
     * 缓存下来避免每次渲染列表都重新解析（该操作需要网络且部分机型不可用）。
     * 仅存供展示，不做任何上传。
     */
    val locationName: String? = null,

    /** 本次观察的备注 */
    val note: String? = null,

    /**
     * AI 返回的原始结果全文（JSON 字符串）。
     * 无论解析成功与否都保留，便于问题追溯与后续 prompt 改进。
     */
    val aiResultJson: String? = null,

    /**
     * 是否为该植物的代表观察 —— 列表卡片取它作为封面图。
     * 没有这个标记就只能取「最新一次」，而用户可能希望固定某次。
     */
    val isPrimary: Boolean = false,
)

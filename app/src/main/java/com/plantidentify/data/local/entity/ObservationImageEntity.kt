package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 观察照片（ObservationImage）。
 *
 * 设计要点：
 *
 * 1. **imagePath 存相对路径**（如 `images/2026/09/xxx.jpg`），不存绝对路径。
 *    应用私有目录的绝对路径包含随机化的沙盒段，换机恢复备份后绝对路径会全部失效。
 *    取绝对路径请通过 `ImageStore.resolve(relativePath)`。
 *
 * 2. **原图与 AI 上传副本分开**。本表只记录原图路径。
 *    上传用的压缩图（1536px / JPEG 80）属于派生数据，按需生成到缓存目录，
 *    不落库、不长期保存 —— 避免存储翻倍，也避免压缩参数变更后留下过期副本。
 *
 * 3. **role 字段直接服务于「多图联合识别」** —— 见 [ImageRole] 的说明。
 */
@Entity(
    tableName = "observation_image",
    foreignKeys = [
        ForeignKey(
            entity = PlantObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["observationId", "sortOrder"]),
    ],
)
data class ObservationImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 所属观察记录 id */
    val observationId: Long,

    /** 原图相对路径 */
    val imagePath: String,

    /** 拍摄部位角色 —— 用于在 prompt 中显式告知模型每张图看什么 */
    val role: ImageRole = ImageRole.UNKNOWN,

    /** 展示与上传顺序（用户可调整）。图序会写入 prompt，因此需要保持稳定 */
    val sortOrder: Int = 0,
)

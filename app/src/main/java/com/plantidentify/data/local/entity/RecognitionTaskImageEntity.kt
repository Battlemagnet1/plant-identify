package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务照片 —— 某次识别用到的第 N 张图。
 *
 * 与 [ObservationImageEntity] 结构刻意保持同构（都是 `imagePath` + `role` + `sortOrder`），
 * 因为任务完成后这些行会被**整体迁移**到 `observation_image`：
 * 路径不变，只换外键（`taskId` → `observationId`）。
 *
 * `role` 的语义与 [ImageRole] 完全一致 —— 「多图联合识别」时告诉模型每张图看什么，
 * 这是全项目对冲「多图退化」的关键字段，任务表里同样不能省。
 *
 * 生命周期：任务照片落在同一个 `filesDir/images/` 下（不是临时目录）——
 * 因为任务可能要等网络、等几小时才跑，放 `cacheDir` 会被系统清掉，
 * 届时任务只剩一堆失效路径。**这是硬约束，别改成 cacheDir。**
 */
@Entity(
    tableName = "recognition_task_image",
    foreignKeys = [
        ForeignKey(
            entity = RecognitionTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "sortOrder"]),
    ],
)
data class RecognitionTaskImageEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 所属任务 id */
    val taskId: Long,

    /** 原图相对路径（同 [ObservationImageEntity.imagePath]） */
    val imagePath: String,

    /** 拍摄部位角色 —— 与观察照片同一套语义 */
    val role: ImageRole = ImageRole.UNKNOWN,

    /** 展示与上传顺序（用户可调） */
    val sortOrder: Int = 0,
)

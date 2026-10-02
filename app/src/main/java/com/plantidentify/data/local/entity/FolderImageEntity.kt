package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 景观照片的类别（用户拍这一张是为了看什么） */
enum class FolderImageKind {
    /** 整个场地 / 全景 */
    OVERALL,

    /** 花坛、花境 */
    FLOWER_BED,

    /** 道路绿化 */
    ROAD_GREEN,

    /** 植物群落（多株成片） */
    COMMUNITY,

    /** 景观节点（景石、亭廊、入口） */
    NODE,

    /** 其他 */
    OTHER,
}

/**
 * 景观照片（v1.0.2 Phase 3）。
 *
 * ## 为什么必须与 `ObservationImage` 分开（需求 §六 特意强调）
 *
 * 两者拍的**根本不是一回事**：
 *
 * | | `ObservationImage` | `FolderImage` |
 * |---|---|---|
 * | 拍的是 | 一株植物的叶片 / 花 / 果实 / 树皮 | 整个公园、花坛、道路绿化、植物群落 |
 * | 挂在 | 某次**观察**上（因此属于某株植物） | 某个**文件夹**上（与植物无关） |
 * | 用途 | 识别与档案 | 景观分析与报告 |
 *
 * 混成一张表会立刻出问题：景观照片没有「属于哪株植物」这个概念，
 * 硬塞进 `observation_image` 就得把 `observationId` 设成假的或可空，
 * 而后者会破坏「照片一定属于某次观察」这条不变量 —— 那条不变量正是
 * 植物档案里「照片不会丢」的保证。
 *
 * ## 图片文件仍走 `ImageStore`
 *
 * 只存相对路径，与 `ObservationImage` 同一个约定 —— 换机或清数据后
 * 绝对路径必然失效。文件本身不做区分，区分在**引用它的那一行**。
 */
@Entity(
    tableName = "folder_image",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId")],
)
data class FolderImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val folderId: Long,

    /** 相对路径（`ImageStore` 口径） */
    val imagePath: String,

    /** 用户写的说明，如「北门花坛东侧」 */
    val caption: String? = null,

    /** 类别，`FolderImageKind.name` */
    val kind: String = FolderImageKind.OTHER.name,

    val sortOrder: Int = 0,

    val addedAt: Long = 0L,
)

package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件夹 —— 组织植物数据的通用容器（v1.0.2 Phase 1）。
 *
 * ## 与 PlantRecord 的关系：多对多
 *
 * 一株植物可以同时属于多个文件夹，而库里**始终只有一条 PlantRecord**
 * （关联表见 [FolderPlantEntity]）。
 *
 * 这是本功能最重要的一条设计约束。如果改成「一个植物属于一个文件夹」，
 * 「悬铃木同时属于校园调查 / 广东常见植物 / 我的植物库」就只能靠复制三条档案
 * 来实现 —— 而它们会各自长出观察记录，从此再也归并不回去。
 *
 * ## 删文件夹不删植物
 *
 * 删除文件夹时只删 [FolderPlantEntity] 的关联行，**绝不触碰 plant_record**。
 * 这一点由外键方向在 SQL 层保证：没有任何从 `folder` 指向 `plant_record`
 * 的外键，删文件夹时 SQLite 只能顺着 `folderId` 那一侧级联。
 */
@Entity(
    tableName = "folder",
    indices = [
        // 主页按类型筛选
        Index(value = ["type"]),
        // 默认排序（最后更新）
        Index(value = ["updatedAt"]),
    ],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * 文件夹名（用户可改）。
     *
     * 刻意**不加唯一约束** —— 「校园植物调查」这种名字出现两次并不违反任何
     * 业务规则，强行唯一只会让用户在重名时收到一个没有意义的报错。
     */
    val name: String,

    val type: FolderType,

    val description: String? = null,

    /**
     * 封面图：**相对路径**（与 [ObservationImageEntity.imagePath] 同一约定），可空。
     *
     * 为空时列表页回退到「文件夹内第一株植物的封面」，再取不到就显示占位块。
     * 刻意不复制图片文件 —— 文件夹只是容器，不该拥有自己的图片副本
     * （复制出来的副本还会在删除文件夹时变成无人引用的垃圾文件）。
     */
    val coverImage: String? = null,

    val createdAt: Long,

    /**
     * 最后更新时间。
     *
     * 成员增删、改名、改描述都要刷新它 —— 卡片上显示的「最后更新」
     * 是用户判断「这个文件夹我最近动过没有」的唯一依据。
     */
    val updatedAt: Long,
)

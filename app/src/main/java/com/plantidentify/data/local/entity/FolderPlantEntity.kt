package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 文件夹 ↔ 植物的多对多关联（v1.0.2 Phase 1）。
 *
 * ## 为什么是关联表，而不是给 PlantRecord 加一个 folderId 列
 *
 * 加外键列只能表达「一株植物属于一个文件夹」。需求明确要求一株植物可以
 * 同时出现在多个文件夹里（校园调查 / 广东常见植物 / 我的植物库），
 * 且**不允许复制出多条 PlantRecord** —— 复制出来的档案会各自长出观察记录，
 * 之后再也归并不回去。
 *
 * ## 两个外键的方向决定了删除语义（重要）
 *
 * | 删除动作 | 结果 |
 * |---|---|
 * | 删文件夹 | 只级联删本表的关联行 —— **plant_record 毫发无伤** |
 * | 物理删植物（回收站彻底删除） | 关联行随之消失，文件夹保留（成员数少一） |
 *
 * 关键在于**没有任何从 `folder` 指向 `plant_record` 的外键**：
 * 删文件夹时 SQLite 只能顺着 `folderId` 这一侧级联，碰不到植物。
 *
 * ## 软删的植物不会被自动清关联
 *
 * 植物是**软删**的（改 `plant_record.deletedAt`），不触发 `ON DELETE CASCADE`。
 * 所以查询文件夹成员时必须显式带 `p.deletedAt IS NULL`，
 * 否则回收站里的植物会继续出现在文件夹里。
 */
@Entity(
    tableName = "folder_plant",
    // 复合主键 = 「同一株植物在同一文件夹里天然只能有一条关联」，无需额外唯一索引
    primaryKeys = ["folderId", "plantId"],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlantRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["plantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // 反查「这株植物属于哪些文件夹」（植物详情页的「加入文件夹」要预勾选）
        Index(value = ["plantId"]),
        // 文件夹内的自定义排序
        Index(value = ["folderId", "sortOrder"]),
    ],
)
data class FolderPlantEntity(
    val folderId: Long,

    val plantId: Long,

    /** 加入时间。默认排序用它（「最近加入」） */
    val addedAt: Long,

    /**
     * 文件夹内的排序位次。
     *
     * 新加入的植物取「当前最大值 + 1」，所以默认顺序就是加入顺序；
     * 用户手动调序时改这一列（Phase 1 只写入，不提供拖拽界面）。
     */
    val sortOrder: Int = 0,

    /**
     * 来源：手动加入还是从数据包导入（Phase 2 的数据溯源用）。
     *
     * Phase 1 只会写入 null（此刻所有的关联都是手动加的）。留着它是因为
     * 导入功能必须能回答「这株是从张三的数据包里来的」——
     * 那时再加列就要再写一次迁移。
     */
    val source: String? = null,

    /** 备注（同样为 Phase 2 预留） */
    val note: String? = null,
)

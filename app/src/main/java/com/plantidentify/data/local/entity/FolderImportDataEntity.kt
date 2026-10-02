package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 协作文件夹的导入元信息（1:1 扩展，v1.0.2 Phase 2）。
 *
 * ## 与 [LandscapeFolderDataEntity] 是同一种东西
 *
 * 两者都是「某种文件夹类型的专属数据」。Phase 1 把景观字段拆出去、
 * Phase 2 把导入字段拆出去，都是为了 [FolderEntity] 保持通用 ——
 * 否则自定义文件夹会带着一堆永远为空的列，而每加一种类型就要再改 folder 表。
 *
 * ## 为什么必须持久化「来源」而不是每次从数据推断
 *
 * 「这株植物是从张三的数据包里来的」这件事，一旦导入完成就再也推不出来了：
 * 导入的记录与手动添加的记录在 `plant_record` 里长得一模一样
 * （这正是设计要求 —— 不搞两套植物模型）。所以来源信息只能在这里记。
 *
 * ## `rawPackagePath` 存相对路径
 *
 * 与图片同一个约定：库里只存相对路径（`imports/<uuid>.zip`），
 * 绝对路径在换机或清数据后必然失效。原始数据包保留下来是为了
 * 「事后想核对、或重新导入同一批数据」——平时不会被读取，纯属留档。
 */
@Entity(
    tableName = "folder_import_data",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderImportDataEntity(
    @PrimaryKey
    val folderId: Long,

    /** 数据来源名（如「张三的植物库」）。导入时由用户确认或取自数据包 */
    val sourceName: String,

    /** 导入时间 */
    val importedAt: Long,

    /** 原始文件名（仅用于显示） */
    val sourceFileName: String? = null,

    /** 这次导入了多少条植物记录 */
    val totalCount: Int = 0,

    /**
     * 已经处理完的条数（合并进本地库 或 用户确认保留为新）。
     *
     * 剩下的 `totalCount - handledCount` 就是「待处理」——
     * 协作文件夹详情页据此显示「还有 N 条需要确认」。
     */
    val handledCount: Int = 0,

    /** 原始数据包（ZIP）的相对路径（`imports/…`），留档用 */
    val rawPackagePath: String? = null,

    /** 最后一次执行合并的时间 */
    val lastMergedAt: Long? = null,
)

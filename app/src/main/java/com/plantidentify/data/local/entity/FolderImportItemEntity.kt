package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 导入条目状态：**每条被导入的植物**在合并流程里走到哪一步了（v1.0.2 Phase 2）。
 *
 * ## 为什么状态必须存下来，而不是每次重新计算
 *
 * 打开「导入检查」页时当然可以重跑一遍重复检测，但**用户的决定无法重算**：
 * 用户看了一条疑似重复、选了「保留为新记录」，下次打开页面时它仍然疑似重复 ——
 * 如果不记住这个决定，用户会被同一条反复打扰，而「忽略」按钮会变成摆设。
 *
 * ## 为什么不复用 `folder_plant`
 *
 * [FolderPlantEntity] 表达的是「植物属于这个文件夹」这个事实，
 * 而这里表达的是「这条导入数据的处理进度」—— 两者生命周期不同：
 * 用户可能把导入的植物**也**加进别的文件夹（多一条 folder_plant），
 * 但导入状态只有一份；反过来，从协作文件夹移出植物不该清掉处理记录。
 *
 * ## `matchedPlantId` 刻意不加外键
 *
 * 它指向「这条导入数据被合并进了哪条本地记录」。如果加了外键并级联，
 * 一旦那条本地记录被清理，这条历史记录会跟着消失 —— 而我们要的恰恰是
 * 「记录下曾经匹配到过它」。所以这里只做逻辑引用，允许悬空。
 */
@Entity(
    tableName = "folder_import_item",
    primaryKeys = ["folderId", "importPlantId"],
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
            childColumns = ["importPlantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // 反查「这条植物是从哪次导入来的」
        Index(value = ["importPlantId"]),
    ],
)
data class FolderImportItemEntity(
    val folderId: Long,

    /**
     * 导入进来的那条 `plant_record` 的 id。
     *
     * 导入的实现方式是「先把数据插进主库、再按需与本地记录合并」——
     * 所以每条导入数据在库里都是一个**真实的 PlantRecord**
     * （这也是为什么它可以是外键）。
     */
    val importPlantId: Long,

    /** [FolderImportStatus] 的 `name()`。只能追加，不能改名（改动会让老库崩） */
    val status: String,

    /** 合并进了哪条本地记录（仅 [FolderImportStatus.MERGED] 时有值） */
    val matchedPlantId: Long? = null,

    /**
     * 重复判定级别（[com.plantidentify.domain.cleaning.DuplicateCandidate.level]）。
     *
     * 存下来是为了在 UI 上说明「为什么判定为重复」——
     * 不存的话，用户看到的只是一个孤零零的徽章，无法理解依据。
     */
    val matchLevel: Int? = null,

    /** 用户做出决定 / 系统自动处理的时间 */
    val decidedAt: Long? = null,
)

/**
 * 导入条目的处理状态。
 *
 * 命名即数据库里的值（`name()`），**只能追加不能改名** —— 与
 * [FolderType] / [FolderSort] 同一个约定：改名会让老库里的值 `valueOf` 失败。
 */
enum class FolderImportStatus {
    /** 待用户确认（疑似重复 / 冲突）。**只有这个状态会出现在「待处理」里** */
    PENDING,

    /** 判定为全新记录，已自动入库，无需用户操作 */
    AUTO_NEW,

    /** 已合并进某条本地记录（导入的那条进回收站，观察与照片全部转移过去） */
    MERGED,

    /** 用户确认「保留为独立的新记录」，不再提示 */
    KEPT,
}

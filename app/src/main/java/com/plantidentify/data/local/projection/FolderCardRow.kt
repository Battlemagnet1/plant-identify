package com.plantidentify.data.local.projection

import com.plantidentify.data.local.entity.FolderType

/**
 * 文件夹列表卡片的查询投影。
 *
 * 与 [PlantCardRow] 同样的理由：一张卡片只需要「成员数 + 一张封面」，
 * 用子查询在 SQLite 里算好即可 —— 把全部成员读进内存再统计，
 * 几十个文件夹、每个装几十株植物时就会明显拖慢首屏。
 *
 * 字段名要与 SQL 里的别名一致 —— Room 按名字映射，不是按顺序。
 */
data class FolderCardRow(
    val folderId: Long,
    val name: String,
    val type: FolderType,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long,

    /** 成员数。**不含回收站里的植物** —— 与植物列表的口径保持一致 */
    val plantCount: Int,

    /**
     * 封面图（相对路径），可能为 null。
     *
     * 优先用文件夹自己设置的 `coverImage`；没设置时回退到「成员里第一株植物的封面」——
     * 这样刚建好的文件夹只要加了植物就自动有封面，不必让用户手动挑一张。
     */
    val coverPath: String?,
)

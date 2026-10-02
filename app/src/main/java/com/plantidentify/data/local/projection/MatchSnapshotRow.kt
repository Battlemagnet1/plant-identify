package com.plantidentify.data.local.projection

/**
 * 匹配用的**轻量**档案投影（v1.0.2 Phase 2）。
 *
 * ## 为什么不直接用 `RecordSnapshot` 查全字段
 *
 * 导入时要拿本地全部档案去和导入的记录做重复检测。清洗模块的
 * `CleaningDataLoader` 是**读全字段**的（8 个百科长文本 + 观察 + 照片，
 * 实测约 14KB/株，1 万株就会 OOM），导入侧不能重蹈覆辙。
 *
 * 而六级判定实际只用到这五个字段：
 * 拉丁学名、中文名、科、属、简介（级 1–6 的全部判据）。
 * 其余百科字段**不参与任何一条判定规则**，取回来纯属浪费。
 *
 * ⚠️ **改 `DuplicateMatcher` 的判据时，记得同步这里** ——
 * 少取一个字段不会报错，只会让那条规则静默失效（这是本类唯一的风险点）。
 *
 * `description` 在 SQL 里就截到 500 字符：`Similarity.descriptionSimilarity`
 * 本来就 `take(500)`，多取没有意义。
 */
data class MatchSnapshotRow(
    val id: Long,
    val name: String,
    val latinName: String? = null,
    val family: String? = null,
    val genus: String? = null,
    val description: String? = null,
)

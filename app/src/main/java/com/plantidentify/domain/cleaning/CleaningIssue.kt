package com.plantidentify.domain.cleaning

/**
 * 清洗问题的分类。
 *
 * 只有四类，与清洗中心页面上那四个计数**一一对应** ——
 * 页面要显示「疑似重复 3 / 信息冲突 1 / 缺失字段 5 / 图片异常 2」，
 * 如果算法侧枚举出十几类，界面上就得再维护一张「十几类 → 四类」的映射表，
 * 迟早会漏一个类型让它在界面上凭空消失。
 *
 * 格式类问题（时间戳越界、坐标非法、置信度越界、JSON 坏掉）
 * 一律归到 [CONFLICT]：它们对用户的意义都是「这个字段的值不对劲」，
 * 用户的动作也是同一个 —— 去编辑页改掉。
 */
enum class CleaningIssueCategory(val label: String) {
    /** 疑似重复：可能有两株是同一株 */
    DUPLICATE("疑似重复"),

    /** 信息冲突：字段值不合法或自相矛盾 */
    CONFLICT("信息冲突"),

    /** 缺失字段：该有的信息没有 */
    MISSING("缺失字段"),

    /** 图片异常：照片缺失、损坏、重复 */
    IMAGE("图片异常"),
}

/**
 * 问题类型。
 *
 * `label` 直接展示给用户，`hint` 是「该怎么办」——
 * 只说「有问题」而不说「怎么修」，用户就只能干瞪眼。
 */
enum class CleaningIssueType(
    val category: CleaningIssueCategory,
    val label: String,
) {
    // ---------- 缺失字段 ----------
    MISSING_FIELD(CleaningIssueCategory.MISSING, "关键字段为空"),
    ORPHAN_OBSERVATION(CleaningIssueCategory.MISSING, "观察记录无主"),
    NO_IMAGE(CleaningIssueCategory.MISSING, "观察没有照片"),
    FOREIGN_KEY(CleaningIssueCategory.MISSING, "关联数据异常"),

    // ---------- 信息冲突 ----------
    INVALID_TIMESTAMP(CleaningIssueCategory.CONFLICT, "观察时间不合理"),
    INVALID_COORDINATE(CleaningIssueCategory.CONFLICT, "坐标不合法"),
    INVALID_CONFIDENCE(CleaningIssueCategory.CONFLICT, "置信度越界"),
    INVALID_JSON(CleaningIssueCategory.CONFLICT, "AI 原始结果损坏"),
    INVALID_CATEGORY(CleaningIssueCategory.CONFLICT, "植物分类字段可疑"),

    // ---------- 图片异常 ----------
    MISSING_IMAGE(CleaningIssueCategory.IMAGE, "照片文件丢失"),
    BROKEN_IMAGE(CleaningIssueCategory.IMAGE, "照片文件损坏"),
    DUPLICATE_IMAGE(CleaningIssueCategory.IMAGE, "照片内容重复"),

    // ---------- 疑似重复 ----------
    POSSIBLE_DUPLICATE(CleaningIssueCategory.DUPLICATE, "疑似同一株植物"),
}

/**
 * 严重程度。
 *
 * 只分三档，且**与「能不能自动修」无关** —— 本功能全程只提示，
 * 任何一条都不会被自动修改。它的用途只有两个：
 * 列表排序（严重的排前面）与健康度扣分权重。
 */
enum class CleaningSeverity(val label: String, val weight: Int) {
    /** 不影响使用（如分类字段措辞不规范） */
    LOW("轻微", 1),

    /** 会误导用户（如坐标非法、字段为空） */
    MEDIUM("一般", 3),

    /** 会污染档案（如观察无主、疑似重复） */
    HIGH("严重", 8),
}

/** 问题的处理状态 */
enum class CleaningIssueStatus {
    /** 待处理 */
    OPEN,

    /** 用户点了「忽略」—— 以后不再提示同一条 */
    IGNORED,

    /** 已解决（合并掉了、编辑修好了、照片补回来了） */
    RESOLVED,
}

/**
 * 一条清洗问题（领域模型，尚未落库）。
 *
 * ## 指纹是这套机制的地基
 *
 * [fingerprint] 决定「这条问题以前见过没有」。没有它，用户每次点检查
 * 都会被同一批问题重新问一遍，点过「忽略」的又会冒出来。
 *
 * 默认指纹 = `类型 + 排序去重后的 id`：
 * - **类型参与**：同一株植物「缺拉丁名」和「缺照片」是两条独立问题
 * - **id 排序**：候选 (A,B) 与 (B,A) 必须是同一条，否则每次扫描
 *   因为枚举顺序不同就会多出一条重复问题
 * - **去重**：同一株在多个规则里命中时 id 列表可能重复
 *
 * ## 为什么需要 [discriminator]
 *
 * 「类型 + id」这个组合在**同一条档案上只允许出一条问题**，但同一条档案
 * 完全可能同时缺拉丁名、缺科、缺属 —— 三者的 id 相同、类型相同，
 * 指纹于是也相同，唯一索引会让后两条被**静默丢弃**。
 * 结果就是「明明缺三个字段，界面上只报一个」。
 *
 * 所以指纹里要再带一个区分维度：
 * - 字段类规则用字段名（`latinName` / `family` / `genus`）
 * - 观察类规则用观察 id（`obs:37`），因为同一次观察可能既时间越界又坐标非法
 * - 重复照片用 **sha256**：它的判据是文件内容而不是 id，
 *   用户若重新导出照片（路径全变、字节没变），用 id 拼指纹会让
 *   老问题凭空消失、新问题凭空出现，白白多出一条
 */
data class CleaningIssue(
    val type: CleaningIssueType,
    val severity: CleaningSeverity,

    /**
     * 涉及的档案 id（**不是**观察 id / 照片 id）。
     *
     * 统一到「档案」这一层，因为用户能采取的动作都挂在档案上：
     * 去详情页、去编辑页、合并、移入回收站。
     *
     * 允许为空：当问题只由图片内容判定（重复照片）而暂时定位不到
     * 归属档案时，空列表比编一个假 id 诚实。
     */
    val recordIds: List<Long>,

    /** 相似度（仅疑似重复有值），0..1 */
    val similarity: Double? = null,

    /** 判据的中文说明（本地规则给的，如「拉丁学名相同」） */
    val reason: String,

    /** 是否经过 AI 判定 */
    val aiUsed: Boolean = false,

    /** AI 给出的一句话理由；未过 AI 时为 null */
    val aiReason: String? = null,

    /** 指纹的第三段，见类注释 */
    val discriminator: String? = null,
) {
    val fingerprint: String
        get() = buildString {
            append(type.name)
            append('|')
            append(discriminator.orEmpty())
            append('|')
            // 排序 + 去重：候选 (A,B) 与 (B,A) 必须是同一条，
            // 否则每次扫描因枚举顺序不同就会多报一条重复问题
            append(recordIds.distinct().sorted().joinToString(","))
        }

    /** 主档案 id（用于「点进去看」）；无关联档案时为 null */
    val primaryRecordId: Long? get() = recordIds.minOrNull()
}

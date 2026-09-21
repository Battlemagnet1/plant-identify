package com.plantidentify.domain.cleaning

/**
 * 可以被合并择优选中的字段。
 *
 * 枚举而不是字符串键：合并是**破坏性操作**，字段名写错一个字母
 * 就会导致「预览里显示了、执行时却写不进去」—— 而那发生在
 * 用户已经点下确认之后。枚举让这类错误在编译期就暴露。
 *
 * [label] 直接用在合并预览页的逐字段选择上。
 */
enum class MergeField(val label: String) {
    NAME("正式中文名称"),
    LATIN_NAME("拉丁学名"),
    FAMILY("科"),
    GENUS("属"),
    CATEGORY("植物类型"),
    COMMON_NAMES("其他俗称"),
    DESCRIPTION("植物简介"),
    MORPHOLOGICAL_FEATURES("形态特征"),
    GROWTH_HABITS("生长习性"),
    FLOWERING_PERIOD("花期"),
    FRUITING_PERIOD("果期"),
    LANDSCAPE_USES("园林用途"),
    CARE_ADVICE("养护建议"),
    PEST_CONTROL("病虫害防治"),
    NOTE("备注"),
}

/** 一个字段最终取自哪一边 */
enum class FieldSource(val label: String) {
    /** 取自保留的那一株 */
    KEEP("保留"),

    /** 取自被合并掉的那一株 */
    DROP("被合并"),

    /**
     * 两边合起来生成的新值（目前只有「其他俗称」会走到这里）。
     *
     * 它和「用户手动选择」共用一个取值，是因为界面上两者都是
     * 「不属于任何一边」——合并预览里显示成「两株合并」即可。
     */
    MANUAL("两边合并"),
}

/** 单个字段的择优结果 */
data class FieldChoice(
    val value: String?,
    val source: FieldSource,
)

/**
 * 合并方案（方案 §7.6）。
 *
 * ## 为什么先出方案再执行
 *
 * 合并是不可逆的大动作（几十次观察、几十张照片要换父亲），
 * 而且**两边都可能承载了对方没有的信息** —— 默认择优规则能覆盖
 * 九成情况，但「科」这种字段两边冲突时，只有用户知道哪个对。
 *
 * [fields] 是可改的：合并预览页允许用户逐字段改选，
 * 用户改过的项 [FieldSource] 会变成 [FieldSource.MANUAL]
 * （通过 [withChoice]）。
 */
data class MergePlan(
    val keepId: Long,
    val dropId: Long,

    /** 字段 → 择优结果。顺序与 [MergeField] 声明顺序一致，界面直接按序遍历 */
    val fields: Map<MergeField, FieldChoice>,

    /** 合并后的置信度（取两边更高的那个） */
    val confidence: Double,
    val confidenceSource: FieldSource,

    /** 合并后的观察数 / 照片数（预览页显示「合并后将保留 N 次观察」） */
    val observationCount: Int,
    val imageCount: Int,

    /**
     * 需要用户留意的取舍。
     *
     * 只在**有实质冲突**时产生（如两边科不同）。不是警告轰炸：
     * 每一条都对应一个「系统替你做了决定，但可能做错」的地方。
     */
    val warnings: List<String>,
) {
    fun choice(field: MergeField): FieldChoice? = fields[field]

    fun value(field: MergeField): String? = fields[field]?.value

    /** 用户改选了某个字段 */
    fun withChoice(field: MergeField, value: String?): MergePlan =
        copy(fields = fields + (field to FieldChoice(value, FieldSource.MANUAL)))

    /** 用户认可自动择优的结果（把 MANUAL 之外的项统一成 KEEP/DROP 不用改，这里只用于确认语义） */
    fun changedFields(): List<MergeField> =
        fields.filter { it.value.source == FieldSource.MANUAL }.keys.toList()
}

/**
 * 字段级择优（方案 §7.6）。
 *
 * ## 总原则：宁可保留信息，不可丢信息
 *
 * 合并之后被丢掉的那一株会被软删，用户几乎不会再翻回去看它。
 * 所以这里的默认规则一律偏向「哪边信息更多就留哪边」，
 * 而不是「哪边更可信」。真实性由用户在看预览时把关。
 *
 * ## 唯一的例外是名称
 *
 * [MergeField.NAME] **不按长度选**，直接用保留侧的。名称是档案的身份，
 * 而「被合并的那一株」正是用户判定为「其实是同一株」的那株 ——
 * 让一个被判为重复的名字覆盖主档名，等于让用户的选择失效。
 */
/**
 * 取某个合并字段在一条档案上的原始值。
 *
 * 界面要让用户「看到两边各自是什么」再决定用哪边 —— 只有方案里的
 * 择优结果是不够的（那只存了「选中哪一边」）。放在这里而不是界面层，
 * 是因为它必须与 [MergePlanner] 的字段清单**一一对应**：
 * 缺一个字段的表现是「那一行永远显示（暂无）」，而字段本身其实是有的。
 */
fun RecordSnapshot.valueOf(field: MergeField): String? = when (field) {
    MergeField.NAME -> name
    MergeField.LATIN_NAME -> latinName
    MergeField.FAMILY -> family
    MergeField.GENUS -> genus
    MergeField.CATEGORY -> category
    MergeField.COMMON_NAMES -> commonNames
    MergeField.DESCRIPTION -> description
    MergeField.MORPHOLOGICAL_FEATURES -> morphologicalFeatures
    MergeField.GROWTH_HABITS -> growthHabits
    MergeField.FLOWERING_PERIOD -> floweringPeriod
    MergeField.FRUITING_PERIOD -> fruitingPeriod
    MergeField.LANDSCAPE_USES -> landscapeUses
    MergeField.CARE_ADVICE -> careAdvice
    MergeField.PEST_CONTROL -> pestControl
    MergeField.NOTE -> note
}

object MergePlanner {

    /**
     * 生成合并方案。
     *
     * @param keep 保留的那一株（合并后活着）
     * @param drop 被合并掉的那一株（合并后进回收站，**不是物理删除**）
     */
    fun plan(keep: RecordSnapshot, drop: RecordSnapshot): MergePlan {
        val warnings = mutableListOf<String>()
        val fields = linkedMapOf<MergeField, FieldChoice>()

        // 名称：保留侧优先，不按长度
        fields[MergeField.NAME] = FieldChoice(keep.name, FieldSource.KEEP)

        // 拉丁学名：取更长（含作者引证的那个更完整），但两边不同不算「冲突」
        // —— 学名不一致时更长的未必对，所以只在明显一长一短时才算择优
        fields[MergeField.LATIN_NAME] = pickLonger(keep.latinName, drop.latinName)

        fields[MergeField.FAMILY] = pickLongerWithConflict(
            keep.family, drop.family, MergeField.FAMILY, keep, drop, warnings,
        )
        fields[MergeField.GENUS] = pickLongerWithConflict(
            keep.genus, drop.genus, MergeField.GENUS, keep, drop, warnings,
        )
        fields[MergeField.CATEGORY] = pickLongerWithConflict(
            keep.category, drop.category, MergeField.CATEGORY, keep, drop, warnings,
        )

        // 俗称：**两边的并集**，不取更长
        //
        // 与方案 §7.6 的「取更长」不同，这里刻意改成并集：
        // 俗称是逗号分隔的列表，「取更长」会让一方的别名整体消失 ——
        // 而别名恰好是「这株到底叫什么」最实用的信息，
        // 用户下次按俗称搜不到，也不会知道是被合并弄丢的。
        fields[MergeField.COMMON_NAMES] = unionAliases(keep.commonNames, drop.commonNames)

        // 八个百科字段：取更长，相同取保留侧
        fields[MergeField.DESCRIPTION] = pickLonger(keep.description, drop.description)
        fields[MergeField.MORPHOLOGICAL_FEATURES] =
            pickLonger(keep.morphologicalFeatures, drop.morphologicalFeatures)
        fields[MergeField.GROWTH_HABITS] = pickLonger(keep.growthHabits, drop.growthHabits)
        fields[MergeField.FLOWERING_PERIOD] = pickLonger(keep.floweringPeriod, drop.floweringPeriod)
        fields[MergeField.FRUITING_PERIOD] = pickLonger(keep.fruitingPeriod, drop.fruitingPeriod)
        fields[MergeField.LANDSCAPE_USES] = pickLonger(keep.landscapeUses, drop.landscapeUses)
        fields[MergeField.CARE_ADVICE] = pickLonger(keep.careAdvice, drop.careAdvice)
        fields[MergeField.PEST_CONTROL] = pickLonger(keep.pestControl, drop.pestControl)

        // 备注：**拼接**。用户手写的东西一律不丢
        fields[MergeField.NOTE] = joinNotes(keep.note, drop.note)

        val confidence = maxOf(keep.confidence, drop.confidence)
        val confidenceSource =
            if (drop.confidence > keep.confidence) FieldSource.DROP else FieldSource.KEEP

        return MergePlan(
            keepId = keep.id,
            dropId = drop.id,
            fields = fields,
            confidence = confidence,
            confidenceSource = confidenceSource,
            observationCount = keep.observationCount + drop.observationCount,
            imageCount = keep.imageCount + drop.imageCount,
            warnings = warnings,
        )
    }

    /**
     * 默认保留哪一株。
     *
     * 规则按优先级：
     * 1. **观察次数多**的（用户实际拍得多的那株，档案更有价值）
     * 2. 仍相同时取**创建更早**的 id（先来的那个，编号更小更符合直觉）
     *
     * 刻意不按置信度选：置信度是「这一次识别有多确定」，
     * 与「这一株作为档案的价值」无关 —— 一次误判的高置信度
     * 不该让它成为合并后的主档。
     */
    fun defaultKeep(a: RecordSnapshot, b: RecordSnapshot): RecordSnapshot =
        when {
            a.observationCount != b.observationCount ->
                if (a.observationCount > b.observationCount) a else b
            else -> if (a.id <= b.id) a else b
        }

    // ---------------------------------------------------------------- 内部

    /** 非空优先 → 都非空取更长 → 相同取保留侧 */
    private fun pickLonger(keepValue: String?, dropValue: String?): FieldChoice = when {
        keepValue.isNullOrBlank() && dropValue.isNullOrBlank() -> FieldChoice(null, FieldSource.KEEP)
        keepValue.isNullOrBlank() -> FieldChoice(dropValue, FieldSource.DROP)
        dropValue.isNullOrBlank() -> FieldChoice(keepValue, FieldSource.KEEP)
        dropValue.length > keepValue.length -> FieldChoice(dropValue, FieldSource.DROP)
        else -> FieldChoice(keepValue, FieldSource.KEEP)
    }

    /**
     * 与 [pickLonger] 相同，但两边**都非空且不同**时记一条 warning。
     *
     * 「科」和「属」不同意味着至少一边错了，而系统没有能力判断是哪一边 ——
     * 这正是必须让用户看一眼的地方。取更长的只是在用户不看时给一个
     * 相对合理的默认，不能替代提示。
     */
    private fun pickLongerWithConflict(
        keepValue: String?,
        dropValue: String?,
        field: MergeField,
        keep: RecordSnapshot,
        drop: RecordSnapshot,
        warnings: MutableList<String>,
    ): FieldChoice {
        val choice = pickLonger(keepValue, dropValue)
        if (!keepValue.isNullOrBlank() && !dropValue.isNullOrBlank() &&
            keepValue.trim() != dropValue.trim()
        ) {
            warnings += "「${field.label}」两边不一致：「${keep.name}」是「${keepValue.trim()}」，" +
                "「${drop.name}」是「${dropValue.trim()}」，已默认取更完整的那个，请确认"
        }
        return choice
    }

    /**
     * 俗称取并集。
     *
     * 分隔符按项目里出现过的写法收集：分析 prompt 要求逗号分隔，
     * 但用户手填时更常用顿号。用正则统一切分比猜一种更稳。
     */
    private fun unionAliases(keepValue: String?, dropValue: String?): FieldChoice {
        if (keepValue.isNullOrBlank() && dropValue.isNullOrBlank()) {
            return FieldChoice(null, FieldSource.KEEP)
        }
        val keepParts = splitAliases(keepValue)
        val dropParts = splitAliases(dropValue)

        val merged = mutableListOf<String>()
        keepParts.forEach { if (merged.none { m -> m.equals(it, ignoreCase = true) }) merged += it }
        dropParts.forEach { if (merged.none { m -> m.equals(it, ignoreCase = true) }) merged += it }

        val source = when {
            dropParts.isEmpty() -> FieldSource.KEEP
            keepParts.isEmpty() -> FieldSource.DROP
            merged.size == keepParts.size -> FieldSource.KEEP
            else -> FieldSource.MANUAL
        }
        return FieldChoice(merged.joinToString("、"), source)
    }

    private fun splitAliases(raw: String?): List<String> =
        raw?.split('、', '，', ',', '；', ';', '|', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** 备注拼接：两边都不空时换行分隔，完全相同则不重复 */
    private fun joinNotes(keepValue: String?, dropValue: String?): FieldChoice {
        val k = keepValue?.trim().orEmpty()
        val d = dropValue?.trim().orEmpty()
        return when {
            k.isEmpty() && d.isEmpty() -> FieldChoice(null, FieldSource.KEEP)
            k.isEmpty() -> FieldChoice(dropValue, FieldSource.DROP)
            d.isEmpty() -> FieldChoice(keepValue, FieldSource.KEEP)
            k == d -> FieldChoice(keepValue, FieldSource.KEEP)
            else -> FieldChoice("$k\n$d", FieldSource.MANUAL)
        }
    }
}

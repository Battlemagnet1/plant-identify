package com.plantidentify.domain.landscape

/**
 * 园林植物层次。这是景观分析的**第一维度** —— 乔灌草的比例决定一个
 * 场地的空间感，比「有多少种植物」重要得多。
 */
enum class PlantLayer(val label: String) {
    TREE("乔木"),
    SHRUB("灌木"),
    HERB("草本"),
    GROUND_COVER("地被 / 藤本"),
    /** 判不出来 —— **不猜**，界面上单列一项 */
    UNKNOWN("未分类"),
}

/** 观赏季节 */
enum class Season(val label: String) {
    SPRING("春"),
    SUMMER("夏"),
    AUTUMN("秋"),
    WINTER("冬"),
    /** 全年常绿 / 全年观赏 */
    EVERGREEN("全年"),
}

/**
 * 从植物档案的**文本字段**里提取景观分析要用的特征。
 *
 * ## 为什么是「文本启发式」而不是查植物学数据库
 *
 * 项目里关于一株植物的一切都来自 AI 识别与用户编辑，字段是自由文本：
 * 类型可能写「乔木」也可能写「落叶乔木」也可能写「小乔木，观花」。
 * 接一个植物学本体库来规范化是另一个量级的工程，而景观分析要的只是
 * **统计比例** —— 把「大致是乔木」判断出来就够用了。
 *
 * 所以这里的原则是：**宁可判成 UNKNOWN，也不硬猜**。
 * 比例算出来 30% 乔木、20% 判不出，用户看到「未分类 20%」会去补数据；
 * 而如果把判不出的都算进乔木，用户会得到一张漂亮的假图。
 */
object PlantTraits {

    // ---------------------------------------------------------------- 层次

    private val TREE_WORDS = listOf(
        "乔木", "大树", "行道树", "庭荫树", "小乔木", "大乔木", "落叶乔", "常绿乔",
    )
    private val SHRUB_WORDS = listOf(
        "灌木", "小灌木", "丛生", "球类", "绿篱", "花灌木", "落叶灌", "常绿灌",
    )
    private val HERB_WORDS = listOf(
        "草本", "一年生", "二年生", "多年生", "宿根", "球根", "水生植物", "花境植物",
    )
    private val GROUND_WORDS = listOf(
        "地被", "草坪", "藤本", "攀援", "攀缘", "匍匐", "爬藤",
    )

    /**
     * 判断植物层次。
     *
     * 按 `category` → `growthHabits` → `morphologicalFeatures` 的顺序找 ——
     * 越靠前的字段越是用户/AI 明确写下的「这是什么」，后面两个是描述性文本，
     * 里面出现「乔木」可能只是在打比方（「树形似乔木」）。
     */
    fun layerOf(
        category: String?,
        growthHabits: String? = null,
        morphologicalFeatures: String? = null,
    ): PlantLayer {
        // 顺序有讲究：先排除「地被/藤本」，再排「草本」——
        // 「藤本地被」这种写法里两者都出现，地被更具体
        val sources = listOf(category.orEmpty(), growthHabits.orEmpty(), morphologicalFeatures.orEmpty())

        // ① 类别字段最可信，单独过一遍
        matchLayer(category.orEmpty())?.let { return it }

        // ② 描述性字段只在类别没写时兜底
        for (source in sources.drop(1)) {
            matchLayer(source)?.let { return it }
        }
        return PlantLayer.UNKNOWN
    }

    private fun matchLayer(text: String): PlantLayer? {
        if (text.isBlank()) return null
        if (GROUND_WORDS.any { it in text }) return PlantLayer.GROUND_COVER
        if (HERB_WORDS.any { it in text }) return PlantLayer.HERB
        if (SHRUB_WORDS.any { it in text }) return PlantLayer.SHRUB
        if (TREE_WORDS.any { it in text }) return PlantLayer.TREE
        return null
    }

    // ---------------------------------------------------------------- 季相

    private val SEASON_WORDS = mapOf(
        Season.SPRING to listOf("春", "3月", "4月", "5月"),
        Season.SUMMER to listOf("夏", "6月", "7月", "8月"),
        Season.AUTUMN to listOf("秋", "9月", "10月", "11月"),
        Season.WINTER to listOf("冬", "12月", "1月", "2月"),
    )

    private val EVERGREEN_WORDS = listOf("全年", "四季", "常年", "终年")

    /**
     * 解析花期 / 果期文本，返回它覆盖的观赏季节。
     *
     * 处理的是真实数据的样子：「5-7月」「4～6月」「春末夏初」「全年」
     * 「5、6月」「花期5-8月」。**跨季节的区间会返回两项以上**
     * ——「5-9月」横跨春夏秋，只记一个季节会让季相图失真。
     *
     * 解析不出就返回空集合（不猜）。
     */
    fun seasonsOf(period: String?): Set<Season> {
        val text = period?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()

        if (EVERGREEN_WORDS.any { it in text }) return setOf(Season.EVERGREEN)

        val seasons = mutableSetOf<Season>()

        // ① 先展开「5-9月」这类区间。
        //
        // 只把两端映射成季节会漏掉中间的整季 —— 5→春、9→秋，
        // **夏天就这么没了**，而夏季恰恰是景观表现最重要的一季。
        // 所以区间要逐月展开。
        val rangeRegex = Regex("""(\d{1,2})\s*[-~～至到]\s*(\d{1,2})""")
        val consumedMonths = mutableSetOf<Int>()
        rangeRegex.findAll(text).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (start !in 1..12 || end !in 1..12) return@forEach

            // 支持跨年区间（「11月-次年2月」）
            val span = (end - start + 12) % 12 + 1
            var month = start
            repeat(span) {
                seasons += seasonOfMonth(month)
                consumedMonths += month
                month = if (month == 12) 1 else month + 1
            }
        }

        // ② 剩下的零散月份（「5、6月」这种区间表达式抓不到）
        Regex("""(\d{1,2})""").findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..12 && it !in consumedMonths }
            .forEach { seasons += seasonOfMonth(it) }

        if (seasons.isNotEmpty()) return seasons

        // ③ 完全没有数字，才看季节词
        SEASON_WORDS.forEach { (season, words) ->
            if (words.any { it in text }) seasons += season
        }
        return seasons
    }

    private fun seasonOfMonth(month: Int): Season = when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.AUTUMN
        else -> Season.WINTER
    }

    // ---------------------------------------------------------------- 色彩

    /**
     * 色彩关键词 → 归类。
     *
     * 顺序即优先级：「紫红」要归到紫而不是红，所以更长的词写在前面。
     */
    private val COLOR_WORDS = listOf(
        "紫红" to "紫", "粉红" to "粉", "橙红" to "橙", "金黄" to "黄",
        "银白" to "白", "蓝紫" to "紫",
        "红" to "红", "紫" to "紫", "粉" to "粉", "黄" to "黄",
        "橙" to "橙", "蓝" to "蓝", "白" to "白", "金" to "黄",
        "银" to "白", "绿" to "绿", "黑" to "深色", "褐" to "深色",
        "彩" to "彩叶", "斑" to "彩叶", "花" to "花色系",
    )

    /**
     * 明显不是讲颜色的词 —— 病名、虫名里带颜色字的最多。
     * 植物名由调用方传进来单独剔除（`红叶石楠`、`紫薇`、`白蜡` 这类）。
     */
    private val COLOR_FALSE_POSITIVES = listOf(
        "红蜘蛛", "黄化病", "白粉病", "黑斑病", "红斑病", "赤枯病", "褐斑病",
    )

    /**
     * 从描述文本里提取色彩关键词。
     *
     * ⚠️ **这是本文件里最不精确的一步**：植物名里带颜色字的太多了
     * （紫薇、黄杨、白蜡、红叶石楠），而它们跟「这株植物是什么颜色」无关。
     * 所以：
     *
     * - 先把**植物名本身**从文本里剔除再找色词
     * - 常见「病名 / 虫名」里的颜色字也排除
     *
     * 即便如此它也只能算参考 —— 界面与 AI 提示里都要标明
     * 「色彩为按关键词统计，非精确色彩分析」。
     */
    fun colorsOf(plantName: String, vararg texts: String?): Set<String> {
        val joined = texts.filterNotNull().joinToString(" ")
        if (joined.isBlank()) return emptySet()

        // 剔除名字与非色彩词
        var cleaned = joined
        if (plantName.isNotBlank()) cleaned = cleaned.replace(plantName, " ")
        COLOR_FALSE_POSITIVES.forEach { cleaned = cleaned.replace(it, " ") }

        val found = linkedSetOf<String>()
        COLOR_WORDS.forEach { (word, color) ->
            if (word in cleaned) found += color
        }
        return found
    }

    // ---------------------------------------------------------------- 常绿

    private val DECIDUOUS_WORDS = listOf("落叶", "冬季落叶", "落叶乔木", "落叶灌木")

    /**
     * 是否常绿。
     *
     * `true` 明确常绿、`false` 明确落叶、`null` **判不出来**。
     * 返回 null 而不是 false 很重要：把「不知道」算成落叶会让
     * 「常绿比例」这个指标系统性偏低，而它正是景观分析要看的。
     */
    fun evergreenOf(vararg texts: String?): Boolean? {
        val joined = texts.filterNotNull().joinToString(" ")
        if (joined.isBlank()) return null
        if (DECIDUOUS_WORDS.any { it in joined }) return false
        if (listOf("常绿", "四季常青", "终年常绿", "不落叶").any { it in joined }) return true
        return null
    }
}

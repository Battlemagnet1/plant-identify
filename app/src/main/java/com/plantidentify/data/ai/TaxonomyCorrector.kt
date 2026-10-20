package com.plantidentify.data.ai

/**
 * 用百科模型给出的规范名称，**只在该修正时**修正档案的名称 / 科 / 属。
 *
 * ## 为什么需要它（v1.0.2.1018）
 *
 * 两个识别通道的取名质量不一样：
 *
 * - 视觉 AI 通道：prompt 里明确要求「科」「属」用中文，通常是对的
 * - Pl@ntNet 通道：分类器只给拉丁学名；科属靠 `otherResults` 查中文，
 *   而**查不到时会退回拉丁名**（实测 `Asteraceae` 就只给了英文
 *   `["Composite","Daisy Family","Sunflowers"]`，没有「菊科」）
 *
 * 于是档案里会出现「科：Asteraceae」这种拉丁科名，与别的档案的
 * 「科：唇形科」混排 —— 而 `DuplicateMatcher` 是**按科属字符串比对**的，
 * 写法不一致就认不出「同一科」。
 *
 * 生成百科时会调用文字模型，它懂植物学，能顺手给出规范中文名与科属。
 * 把这份知识用起来，就能把拉丁科名补成中文。
 *
 * ## 铁律：只补不覆盖
 *
 * **绝不覆盖用户手改的内容**，也**不覆盖识别通道已给出的正式中文名**。
 * 只在下列情况才写入：
 *
 * | 目标字段 | 允许被纠正的条件 |
 * |---|---|
 * | 名称 | 为空、或是拉丁名/拼音（非中文） |
 * | 科 | 为空、或是拉丁文（非中文） |
 * | 属 | 同上 |
 *
 * 另外，模型给的值本身还要通过**格式校验**（科以「科」结尾、属以「属」结尾、
 * 名称含汉字、且不能与物种名撞车），不通过就一律不写。
 *
 * ## 为什么这么保守
 *
 * 识别与百科是两次独立调用，模型可能给出互相矛盾的结论。若百科模型
 * 说「这其实是 XX」就无脑改名，会把视觉 AI 的正确结论推翻，而用户
 * 完全不知道档案被改过 —— 这比留着拉丁名糟得多。留着至少是**如实**的。
 */
object TaxonomyCorrector {

    /** 一次纠正的结果；`null` 表示这一项不该改 */
    data class Correction(
        /** 字段显示用的名字，仅供日志与提示 */
        val field: String,
        val from: String?,
        val to: String,
    )

    /** 名称 / 科 / 属 三项分别该改成什么（不必改的为 null） */
    data class Plan(
        val name: String? = null,
        val family: String? = null,
        val genus: String? = null,
    ) {
        val isEmpty: Boolean
            get() = name == null && family == null && genus == null

        fun corrections(): List<Correction> = buildList {
            name?.let { add(Correction("中文名", null, it)) }
            family?.let { add(Correction("科", null, it)) }
            genus?.let { add(Correction("属", null, it)) }
        }
    }

    val NONE = Plan()

    /**
     * 计算纠正方案。
     *
     * @param currentName 档案当前的中文名
     * @param currentLatinName 档案当前的拉丁学名（用来判断「名称是不是就是个拉丁名」）
     * @param currentFamily 当前科
     * @param currentGenus 当前属
     * @param suggested 百科模型给出的规范值
     */
    fun plan(
        currentName: String?,
        currentLatinName: String?,
        currentFamily: String?,
        currentGenus: String?,
        suggested: PlantAnalysis,
    ): Plan {
        val name = correctName(
            current = currentName,
            latinName = currentLatinName,
            candidate = suggested.canonicalName,
        )
        val family = correctTaxon(
            current = currentFamily,
            candidate = suggested.canonicalFamily,
            suffix = "科",
        )
        val genus = correctTaxon(
            current = currentGenus,
            candidate = suggested.canonicalGenus,
            suffix = "属",
        )
        return Plan(name = name, family = family, genus = genus)
    }

    // ---------------------------------------------------------------- 名称

    /**
     * 名称：**只在其不是中文名时**才替换。
     *
     * 典型场景：Pl@ntNet 对某个物种没有中文俗名，`name` 直接成了
     * `Camphora officinarum`；百科模型能给出「樟」。这时补成中文是对的。
     *
     * 但如果档案里已经有一个中文名（哪怕与模型建议不同），**一律不动** ——
     * 那是视觉 AI 的判断或用户的手改，我们无权推翻。
     */
    private fun correctName(
        current: String?,
        latinName: String?,
        candidate: String?,
    ): String? {
        val c = candidate?.trim().orEmpty()
        if (c.isEmpty()) return null

        // 候选本身要是中文名，且不能是拉丁学名（模型偶尔把学名填回来）
        if (!containsHan(c)) return null
        if (c.equals(latinName?.trim(), ignoreCase = true)) return null
        // 也不能是把拉丁名换个大小写就交差
        if (c.contains(' ') && !containsHan(c)) return null

        val cur = current?.trim().orEmpty()
        if (cur.isEmpty()) return c

        // 已经是中文名 → 不动（不推翻视觉 AI / 用户手改）
        if (containsHan(cur)) return null

        // 当前值是拉丁名或拼音（不含汉字）→ 补中文
        return c
    }

    // ---------------------------------------------------------------- 科属

    /**
     * 科 / 属：**只在其不是中文时**才替换，且候选必须符合后缀规范。
     *
     * `suffix` 就是「科」或「属」—— 顺便挡住模型把属名写成
     * 「薰衣草」（缺「属」字）这种情况，以及「菊科」填到属一栏这种串栏。
     */
    private fun correctTaxon(
        current: String?,
        candidate: String?,
        suffix: String,
    ): String? {
        val c = candidate?.trim().orEmpty()
        if (c.isEmpty()) return null

        // 候选必须含汉字、且以规定后缀结尾
        if (!containsHan(c)) return null
        if (!c.endsWith(suffix)) return null
        // 「科」「属」两个字本身不算
        if (c.length <= suffix.length) return null

        val cur = current?.trim().orEmpty()
        if (cur.isEmpty()) return c
        if (containsHan(cur)) return null   // 已是中文 → 不推翻
        return c
    }

    private fun containsHan(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
}

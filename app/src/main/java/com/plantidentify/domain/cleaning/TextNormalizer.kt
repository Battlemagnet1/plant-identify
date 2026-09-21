package com.plantidentify.domain.cleaning

/**
 * 文本归一化 —— 所有相似度算法的前置步骤。
 *
 * ## 为什么归一化比算法本身更重要
 *
 * 用户录入（或模型返回）的同一株植物，字符串可能长成好几种样子：
 * ```
 * 悬铃木 / 悬铃木（疑似） / 悬铃木 sp. / Platanus acerifolia (Aiton) Willd. / PLATANUS ACERIFOLIA
 * ```
 * 如果直接拿它们算编辑距离，「悬铃木」与「悬铃木 sp.」会被判成不同 ——
 * 而它们显然是同一个东西。归一化把这些**纯形式上的差异**先抹平，
 * 后面的相似度才在比较「实质内容」。
 *
 * ## 刻意的保守
 *
 * 只处理形式差异（全角/半角、空白、标点、大小写、作者引证），
 * **不做同义词替换**（不把「悬铃木」改成「法国梧桐」）：
 * 那需要一份植物学词典，而误判的代价（把两种不同植物判成同一种）
 * 远高于漏判（把同一种判成两种，用户手动合并即可）。
 */
object TextNormalizer {

    /**
     * 基础归一化：全角→半角、折叠连续空白、去首尾。
     *
     * 全角空格 U+3000 必须处理 —— 中文输入法与从网页复制的文本里很常见，
     * 而它看起来与普通空格一模一样，用 `trim()` 是去不掉的
     * （`trim()` 默认只认 <= ' ' 的字符，U+3000 不在其中）。
     */
    fun normalizeBase(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                // 全角空格 → 普通空格
                ch == '\u3000' -> sb.append(' ')
                // 全角 ASCII（！到～）→ 半角
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * 中文名归一化：基础归一化 + 去括号内容 + 去噪声后缀 + 去标点。
     *
     * 括号内容整段丢弃（不是只去括号符）——「悬铃木（疑似）」里的
     * 「疑似」是**置信度标注**而不是名字的一部分，留着会让它与
     * 「悬铃木」的相似度白白降低。
     */
    fun normalizeChinese(raw: String): String {
        var s = normalizeBase(raw)
        // 中英文括号及其内容（「悬铃木（疑似）」「悬铃木(二球)」）
        s = s.replace(Regex("[（(][^）)]*[）)]"), "")
        // 噪声后缀：模型输出的不确定性标注与学名缩写
        NOISE_SUFFIXES.forEach { suffix ->
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trim()
        }
        // 其余标点（「·」「・」「、」「,」「.」等）
        s = s.replace(Regex("[\\p{Punct}·・、，。；：]"), "")
        return s.trim()
    }

    /**
     * 拉丁学名归一化：基础归一化 + 统一杂交符 + 剥离作者引证 + 小写。
     *
     * 杂交符至少有三种写法：`×`（U+00D7 乘号）、`x`（字母）、`✕`。
     * 不统一的话「Platanus × acerifolia」与「Platanus x acerifolia」
     * 会被判成不同物种 —— 而它们是同一株。
     */
    fun normalizeLatin(raw: String): String {
        var s = normalizeBase(raw)
        s = s.replace(Regex("[×✕╳]"), " x ")
        s = stripAuthorCitation(s)
        return s.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 剥离作者引证，只留双名法的前两个词。
     *
     * ```
     * Platanus acerifolia (Aiton) Willd.  →  Platanus acerifolia
     * Acer palmatum var. atropurpureum    →  Acer palmatum var. atropurpureum
     * ```
     *
     * 规则：前两个词必留（属 + 种）；若第三词是 `var.` / `subsp.` / `f.` / `cv.`，
     * 则再保留一个词（那是**种下等级**，属于学名的有效部分，
     * 丢掉会让两个不同的栽培变种被判成同一物种）。
     */
    fun stripAuthorCitation(raw: String): String {
        val cleaned = normalizeBase(raw)
            .replace(Regex("[()（）]"), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size <= 2) return words.joinToString(" ")

        val kept = words.take(2).toMutableList()
        if (words.size >= 4 && words[2].lowercase() in RANK_MARKERS) {
            kept.add(words[2])
            kept.add(words[3])
        }
        return kept.joinToString(" ")
    }

    /**
     * 切 token：CJK 用 1-gram ∪ 2-gram 字符组，拉丁按词切。
     *
     * ## 为什么不引分词库
     *
     * 中文植物名多为 2–5 字（「紫薇」「悬铃木」「银杏」），
     * 字符二元组已经足以刻画词形。引词典会带来体积与未登录词问题，
     * 而这里要的是**词形相似**，不是语义分词 ——
     * 分词库把「悬铃木」切成「悬铃/木」并不会让比较更准。
     *
     * 1-gram 也保留：单字名（「梅」「兰」「菊」）用 2-gram 会切出空集，
     * 那样它们与任何名字的相似度都是 0。
     */
    fun tokens(raw: String): Set<String> {
        val s = normalizeBase(raw)
        if (s.isEmpty()) return emptySet()

        val out = mutableSetOf<String>()
        val buffer = StringBuilder()

        fun flushLatin() {
            if (buffer.isNotEmpty()) {
                out.add(buffer.toString().lowercase())
                buffer.clear()
            }
        }

        for (ch in s) {
            if (isCjk(ch)) {
                flushLatin()
                out.add(ch.toString())
            } else if (ch.isLetterOrDigit()) {
                buffer.append(ch)
            } else {
                flushLatin()
            }
        }
        flushLatin()

        // 2-gram：只对连续的 CJK 段落生成，跨标点/空格的组合无意义
        CHINESE_RUN.findAll(s).forEach { run ->
            val text = run.value
            for (i in 0 until text.length - 1) {
                out.add(text.substring(i, i + 2))
            }
        }
        return out
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF ||   // 基本区
            ch.code in 0x3400..0x4DBF   // 扩展 A（生僻植物名）

    /** 种下等级标记 —— 星号后面那个词属于学名的有效部分 */
    private val RANK_MARKERS = setOf("var.", "var", "subsp.", "subsp", "ssp.", "f.", "cv.")

    /**
     * 噪声后缀：模型的不确定性标注与学名缩写。
     *
     * ⚠️ **刻意不含「属」「科」**（方案 §7.1 原本列了它们）。
     * 去掉会让「紫薇属」归一到「紫薇」—— 但那一个是**属名**、
     * 一个是**种名**，把属与种判成同一种是最典型的一类误合并。
     * 「（疑似）」「sp.」这类才是真正无信息的形式噪声。
     */
    private val NOISE_SUFFIXES = listOf(
        "（疑似）", "(疑似)", "疑似", " sp.", " sp", "sp.", "\\sp",
    )

    /** 连续 CJK 段（用于 2-gram） */
    private val CHINESE_RUN = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF]+")
}

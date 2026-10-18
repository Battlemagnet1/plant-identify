package com.plantidentify.data.plantnet

import org.json.JSONArray
import org.json.JSONObject

/**
 * 拉丁名 → 中文名 的查表，数据来自 Pl@ntNet 的 `otherResults`。
 *
 * ## 为什么需要它（v1.0.2.1018，中英文对齐问题）
 *
 * Pl@ntNet 的 `results[].species.family` / `.genus` **只有拉丁名**，
 * 连 `commonNames` 字段都没有：
 *
 * ```json
 * "family": { "scientificNameWithoutAuthor": "Lauraceae", ... }
 * ```
 *
 * 而科属的**中文名**（`樟科` / `樟属`）藏在另一处的 `otherResults` 里，
 * 且**只有请求带 `detailed=true` 才会返回**：
 *
 * ```json
 * "otherResults": {
 *   "family": [ { "score": 0.78,
 *                 "family": { "scientificName": "Lauraceae",
 *                             "commonNames": ["樟科"] } } ],
 *   "genus":  [ { "score": 0.41,
 *                 "genus":  { "scientificName": "Cinnamomum",
 *                             "commonNames": ["樟属", "肉桂属"] } } ]
 * }
 * ```
 *
 * ## 不处理会怎样
 *
 * 视觉 AI 通道的 prompt 明确要求「科」「属」给**中文**
 * （见 `PromptBuilder` 的字段说明），Pl@ntNet 通道却只能给拉丁文。
 * 于是同一个档案里出现「樟科 / Cinnamomum」这种中拉混排；
 * 更严重的是 **`DuplicateMatcher` 按科属比对** —— 两边写法不一致时，
 * 「同一科的两种植物」这个判据直接失效，清洗会把本该合并的漏掉、
 * 或者反过来认不出来。
 *
 * ## 只在命中时替换
 *
 * 查不到中文名时**原样返回拉丁名**，绝不编造，也不留空 ——
 * 空值会让「同一科」的判断彻底失去依据，而拉丁名至少还能跨语言比对。
 */
internal class TaxonomyNames(
    /** key 统一小写，便于大小写不敏感地匹配（`Lauraceae` / `lauraceae` 都能命中） */
    private val zhByLatin: Map<String, String>,
) {

    fun hasEntries(): Boolean = zhByLatin.isNotEmpty()

    /**
     * 把拉丁名换成中文名；查不到就原样返回拉丁名。
     *
     * @param latin 拉丁名（可为 null）
     * @param fallback 查不到时的返回值 —— 传 null 表示「没有就用 null」
     */
    fun displayOf(latin: String?, fallback: String? = latin): String? {
        if (latin.isNullOrBlank()) return fallback
        return zhByLatin[latin.trim().lowercase()] ?: fallback ?: latin
    }

    companion object {
        val EMPTY = TaxonomyNames(emptyMap())

        /**
         * 从 `otherResults` 构建查表。
         *
         * 结构是 `otherResults.<family|genus>` 两个数组，每项形如
         * `{ score, family: { scientificName, commonNames: [...] } }`。
         * 同样要防「拼音混在 commonNames 里」，所以挑含汉字的那个 ——
         * 与种级 `commonNames` 的处理保持一致。
         */
        fun fromOtherResults(otherResults: JSONObject?): TaxonomyNames {
            if (otherResults == null) return EMPTY

            val map = HashMap<String, String>()
            collect(otherResults.optJSONArray("family"), level = "family", into = map)
            collect(otherResults.optJSONArray("genus"), level = "genus", into = map)
            return if (map.isEmpty()) EMPTY else TaxonomyNames(map)
        }

        private fun collect(array: JSONArray?, level: String, into: MutableMap<String, String>) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val node = item.optJSONObject(level) ?: continue

                // 分类等级的拉丁名：family/genus 在 otherResults 里用的是
                // scientificName（可能带命名人），退化到 scientificNameWithoutAuthor
                val latin = node.optString("scientificNameWithoutAuthor")
                    .ifBlank { node.optString("scientificName") }
                    .substringBefore(' ')
                    .trim()
                if (latin.isBlank()) continue

                val names = node.optJSONArray("commonNames") ?: continue
                val all = (0 until names.length()).map { names.optString(it) }
                val zh = pickChinese(all) ?: continue

                // 先到先得：otherResults 按 score 降序，第一条就是最可能的那个
                into.putIfAbsent(latin.lowercase(), zh)
            }
        }

        /**
         * 从一组俗名里挑中文名，**简体优先**。
         *
         * ## 为什么要区分繁简
         *
         * Pl@ntNet 的 `commonNames` 常同时给简繁两种：
         *     ["新木姜子属", "新木薑子屬"]
         * 但也经常**只给繁体**：
         *     ["樟屬"]              （属名，只有繁体）
         *
         * 本项目的界面文案、视觉 AI 通道给的都是**简体**。
         * 若这里不挑，同一份档案里就会「樟科（简）+ 樟屬（繁）」混排，
         * 用户看到的是同一个概念两种写法 —— 与中英混排是同一类问题。
         *
         * 所以：优先选不含繁体字的候选；全都是繁体时退回第一个，
         * 而不是留空（有总比没有强，且拉丁名比空值更糟）。
         */
        private fun pickChinese(names: List<String>): String? {
            val chinese = names.filter { it.isNotBlank() && containsHan(it) }
            if (chinese.isEmpty()) return null
            return chinese.firstOrNull { !containsTraditional(it) } ?: chinese.first()
        }

        /** 与 `PlantNetProvider.hasHan` 同一判据（CJK 统一表意文字 + 扩展 A） */
        private fun containsHan(text: String): Boolean =
            text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

        /**
         * 粗判是否含**繁体**字。
         *
         * 不做全量繁简字典（那是几百 KB 的表），只覆盖植物分类里高频的
         * 「繁体专属」字：屬/科/屬/薑/莢/蒾/葉/樹/園/蘭/蓮/蕨/屬 …
         * 以及最通用的一条判据 —— 只出现在繁体字表的常用字。
         *
         * **宁可漏判也不误判**：漏判只是继续用繁体（现状），
         * 误判会把正确的简体名判成繁体而退回第一个，反而更差。
         */
        internal fun containsTraditional(text: String): Boolean =
            text.any { it in TRADITIONAL_ONLY }

        /**
         * 植物分类名里高频出现的**繁体专有字**（简体写法与它们不同）。
         *
         * 只收「简体一定不用这个字形」的字：
         *   屬→属  薑→姜  莢→荚  葉→叶  樹→树  園→园  蘭→兰  蓮→莲  藥→药
         *   薔→蔷（注意「薇」繁简同形，**不能**收 —— 收了会把「蔷薇科」误判成繁体）
         *   鳳→凤  龍→龙  貓→猫  魚→鱼  龜→龟 …
         *
         * ⚠️ **同形字绝不能列进来**（科、樟、蕨、杜、松、梅、薇、葵…）——
         * 收进来会让正确的简体名被判成繁体、退回第一个候选，反而更差。
         * 单元测试 `同形字不算繁体` 专门盯着这条。
         */
        private val TRADITIONAL_ONLY: Set<Char> =
            "屬薑莢葉樹園蘭蓮藥薔鳳龍貓魚龜鶴鷹鴨鵝鴿".toSet()
    }
}

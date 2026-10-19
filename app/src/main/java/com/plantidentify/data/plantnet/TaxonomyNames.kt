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
 * 科属的**中文名**（`樟科` / `樟属`）藏在另一处的 `otherResults` 里，
 * 且**只有请求带 `detailed=true` 才会返回**。
 *
 * ## 不处理会怎样
 *
 * 视觉 AI 通道的 prompt 要求「科」「属」给**中文**（见 `PromptBuilder`），
 * Pl@ntNet 通道却只能给拉丁文 → 同一个档案里「樟科 / Cinnamomum」中拉混排。
 * 更严重的是 **`DuplicateMatcher` 按科属字符串比对** —— 两边写法不一致时，
 * 「同一科的两种植物」这个判据直接失效，清洗会漏判重复。
 *
 * ## 只在命中时替换
 *
 * 查不到中文名时**原样返回拉丁名**，绝不编造、绝不留空 ——
 * 空值会让「同科」彻底失去依据，而拉丁名至少还能跨语言比对。
 */
internal class TaxonomyNames(
    /** key 统一小写，便于大小写不敏感匹配（`Lauraceae` / `lauraceae` 都命中） */
    private val zhByLatin: Map<String, String>,
) {

    fun hasEntries(): Boolean = zhByLatin.isNotEmpty()

    /** 把拉丁名换成中文名；查不到就原样返回 [fallback]（默认就是拉丁名本身） */
    fun displayOf(latin: String?, fallback: String? = latin): String? {
        if (latin.isNullOrBlank()) return fallback
        return zhByLatin[latin.trim().lowercase()] ?: fallback ?: latin
    }

    companion object {
        val EMPTY = TaxonomyNames(emptyMap())

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

                // 分类等级的拉丁名：otherResults 用 scientificName，
                // 退化到 scientificNameWithoutAuthor；截到第一个空格前（去命名人）
                val latin = node.optString("scientificName")
                    .ifBlank { node.optString("scientificNameWithoutAuthor") }
                    .substringBefore(' ')
                    .trim()
                if (latin.isBlank()) continue

                val names = node.optJSONArray("commonNames") ?: continue
                val all = (0 until names.length()).map { names.optString(it) }
                val zh = pickChinese(all, isGenus = level == "genus") ?: continue

                // 先到先得：otherResults 按 score 降序，第一条就是最可能的那个
                into.putIfAbsent(latin.lowercase(), zh)
            }
        }

        /**
         * 从一组俗名里挑中文名，转简体，并在**属名**上做规范化。
         *
         * ## 为什么要转，而不只是「优先挑简体」
         *
         * 用真实照片打真站实测（见 `tools/verify_plantnet_realphotos.py`）：
         * - 蔷薇科 → 接口只给 `["薔薇科"]`，**没有简体候选**
         * - 银杏科 → 接口只给 `["銀杏科"]`
         * - 樟屬   → 接口只给 `["樟屬"]`
         *
         * 所以「同时有简繁就挑简体」只解决一半；**只给繁体**时仍会存进档案。
         * 结果同一个科在不同记录里写成 `薔薇科` / `蔷薇科` 两种，
         * 而 `DuplicateMatcher` 按科属字符串比对 → 认不出「同一科」。
         *
         * ## 属名为什么还要挑「带属字的」
         *
         * 实测 `Lavandula` 的 commonNames 是：
         *     ["薰衣草", "薰衣草屬"]
         * **首项缺「属」字**，次项才是规范的属名。而其它属（鼠尾草属、
         * 荆芥属、马鞭草属）都是带「属」的完整形式。
         *
         * 直接取第一条会让同一批档案里出现「薰衣草」这种既像物种名
         * 又像属名的写法 —— 而物种名也叫「薰衣草」，两者撞在一起，
         * 科属比对与展示都会含糊。
         *
         * 所以属名**优先挑以「属」结尾的**；没有才退回第一个。
         * 注意**不能**反过来给任意名字无脑补「属」字 —— 那会发明
         * Pl@ntNet 没给的名字（例如把物种名「薰衣草」改成「薰衣草属」）。
         */
        private fun pickChinese(names: List<String>, isGenus: Boolean): String? {
            val chinese = names.filter { it.isNotBlank() && containsHan(it) }
            if (chinese.isEmpty()) return null

            val simplified = chinese.map(::toSimplified)

            if (isGenus) {
                // 属名：先挑「X属」形式；优先本来就简体的
                val proper = simplified.filter { it.endsWith("属") && it.length > 1 }
                if (proper.isNotEmpty()) return proper.first()
            }

            // 优先挑本来就全简体的那条（它通常也是更规范的写法）
            val alreadySimplified = chinese.withIndex()
                .firstOrNull { (_, v) -> toSimplified(v) == v }
                ?.let { (i, _) -> simplified[i] }
            return alreadySimplified ?: simplified.first()
        }

        /** 与 `PlantNetProvider.hasHan` 同一判据（CJK 统一表意文字 + 扩展 A） */
        private fun containsHan(text: String): Boolean =
            text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

        /**
         * 把**繁体**字转成简体，只覆盖植物分类名里真正会出现的字。
         *
         * ## 覆盖策略
         *
         * 只收「简体一定不用这个字形」的字；表里没有的字**原样保留** ——
         * 宁可漏转（少数字仍是繁体），也绝不猜。
         *
         * 刻意**不做** OpenCC 那种几万字的全量表：需要的一百来个字就够，
         * 大表会显著增加维护成本而收益不成正比。
         *
         * ## 铁律：繁简同形的字绝不能进来
         *
         * 科、薇、樟、蕨、松、梅、葵、菊、桃、李、杏、榕、楠、榆、槐 等
         * 繁简完全同形。把它们当繁体改，会把**正确的简体名改坏** ——
         * 比漏转危险得多。单元测试 `同形字不算繁体` 专门盯着这条。
         *
         * 特别注意：`薇`（U+8587）繁简同形，而 `薔`（U+8594）→ `蔷`（U+8537）
         * 不同 —— 所以只有 `薔` 能进表。这一条我写错过一次，被测试抓到了。
         */
        internal fun toSimplified(text: String): String {
            if (text.isEmpty()) return text
            var changed = false
            val out = StringBuilder(text.length)
            for (c in text) {
                val s = TRAD_TO_SIMP[c]
                if (s == null) {
                    out.append(c)
                } else {
                    out.append(s)
                    changed = true
                }
            }
            return if (changed) out.toString() else text
        }

        /** 是否含繁体字 —— 判据就是「转完会不一样」 */
        internal fun containsTraditional(text: String): Boolean = toSimplified(text) != text

        /**
         * 植物分类名里高频的繁体 → 简体（按用途分组，便于核对）。
         *
         * 每条都确认过「简体一定不用左侧字形」。表外的字一律不动。
         */
        private val TRAD_TO_SIMP: Map<Char, Char> = buildMap {
            fun add(pairs: String) {
                // 形如 "屬属 薔蔷"，每组两个字：繁、简
                pairs.split(' ').forEach { g ->
                    if (g.length == 2) put(g[0], g[1])
                }
            }

            // 分类等级后缀 —— 「屬」几乎每个属名都带，最关键
            add("屬属 綱纲 門门 亞亚 種种 變变 雜杂")

            // 本次实测真遇到的（薔薇科 / 銀杏科 / 樟屬）
            add("薔蔷 銀银")

            // 常见植物用字
            add(
                "葉叶 樹树 園园 蘭兰 蓮莲 藥药 薑姜 莢荚 薊蓟 蕁荨 蘋苹 " +
                    "檳槟 櫚榈 楊杨 楓枫 樺桦 櫟栎 楨桢 蘇苏 蕎荞 麥麦 蔥葱 " +
                    "蘿萝 蔔卜 蕪芜 薺荠 蓀荪 蘚藓 參参 蔘参 營营 銳锐 鉤钩 " +
                    "絲丝 綿绵 棗枣 櫻樱 檸柠 欖榄 欒栾 構构 蘆芦 葦苇 蕷蓣 " +
                    "蕓芸 藺蔺 蓯苁 臘腊 蠟蜡 鹽盐 鹼碱 鹹咸 髮发 鬚须 鬍胡 " +
                    "鬥斗 雞鸡 鳥鸟 獸兽 蟲虫 蝦虾 貝贝 殼壳 醬酱 釀酿 " +
                    "鳳凤 龍龙 貓猫 魚鱼 龜龟 鶴鹤 鷹鹰 鴨鸭 鵝鹅 鴿鸽 " +
                    "萬万 與与 專专 業业 東东 兩两 嚴严 個个 豐丰 臨临 為为 " +
                    "麗丽 舉举 義义 烏乌 樂乐 喬乔 習习 鄉乡 書书 買买 亂乱 " +
                    "爭争 雲云 產产 畝亩 親亲 億亿 僅仅 從从 倉仓 儀仪 們们 " +
                    "價价 眾众 優优 會会 傘伞 偉伟 傳传 傷伤 倫伦 偽伪 體体 " +
                    "餘余 併并 俠侠 侶侣 僑侨 農农 決决 況况 凍冻 淨净 準准 " +
                    "涼凉 減减 湊凑 幾几 劃划 剛刚 創创 刪删 別别 劑剂 勁劲 " +
                    "動动 務务 勝胜 勞劳 勢势 勳勋 勻匀 匯汇 區区 醫医 華华 " +
                    "協协 單单 賣卖 盧卢 衛卫 卻却 廠厂 廳厅 曆历 厲厉 壓压 " +
                    "厭厌 縣县 雙双 疊叠 號号 嘆叹 嚇吓 呂吕 嗎吗 響响 啞哑 " +
                    "啟启 吳吴 嘗尝 噴喷 喪丧 喚唤 問问 週周 噸吨 聽听 囑嘱 " +
                    "團团 圍围 圖图 圓圆 聖圣 場场 壞坏 塊块 堅坚 壇坛 壩坝 " +
                    "墳坟 墜坠 壟垄 壘垒 墾垦 墊垫 壯壮 聲声 處处 備备 復复 " +
                    "夠够 頭头 誇夸 夾夹 奪夺 奮奋 獎奖 妝妆 婦妇 媽妈 嬌娇 " +
                    "娛娱 嬰婴 孫孙 學学 寧宁 寶宝 實实 寵宠 審审 憲宪 宮宫 " +
                    "寬宽 賓宾 寢寝 對对 尋寻 導导 壽寿 將将 爾尔 塵尘 層层 " +
                    "岡冈 島岛 峽峡 崗岗 嵐岚 嶺岭 嶽岳 巒峦 巔巅 幣币 帥帅 " +
                    "師师 帳帐 帶带 幫帮 幹干 廣广 莊庄 慶庆 廬庐 廟庙 廢废 " +
                    "開开 異异 棄弃 張张 彌弥 彎弯 彈弹 強强 歸归 當当 錄录 " +
                    "彙汇 彥彦 徹彻 徑径 徵征 恆恒 恥耻 悅悦 懸悬 驚惊 懼惧 " +
                    "慘惨 慚惭 憊惫 懇恳 應应 懷怀 態态 憑凭 懲惩 憶忆 戰战 " +
                    "戲戏 戶户 拋抛 挾挟 捨舍 掃扫 掛挂 採采 揀拣 換换 揮挥 " +
                    "損损 搖摇 搶抢 摯挚 撐撑 撓挠 撥拨 撫抚 撲扑 撿捡 擁拥 " +
                    "擇择 擊击 擋挡 擔担 據据 擠挤 擬拟 擲掷 擴扩 擺摆 擾扰 " +
                    "攜携 攝摄 攢攒 敵敌 數数 斂敛 斃毙 斷断 舊旧 時时 晉晋 " +
                    "晝昼 顯显 曉晓 暈晕 暉晖 暢畅 暫暂 曠旷 曬晒 條条 來来 " +
                    "極极 樞枢 標标 棧栈 棟栋 欄栏 樣样 檔档 橋桥 機机 橫横 " +
                    "檢检 權权 樓楼 瀾澜 滅灭 燈灯 煩烦 熱热 熾炽 燒烧 燙烫 " +
                    "燭烛 爐炉 爛烂 爺爷 牆墙 犧牺 狀状 狹狭 獄狱 獅狮 獨独 " +
                    "獲获 獻献 現现 環环 璽玺 瓊琼 畢毕 疇畴 療疗 瘋疯 瘍疡 " +
                    "瘡疮 瘧疟 癢痒 發发 皺皱 盜盗 盞盏 盡尽 監监 盤盘 睜睁 " +
                    "睞睐 瞞瞒 矚瞩 礦矿 碼码 磚砖 硯砚 礙碍 禮礼 禍祸 祿禄 " +
                    "禪禅 離离 稱称 積积 穩稳 窮穷 竊窃 竄窜 競竞 筆笔 筍笋 " +
                    "節节 範范 築筑 篩筛 簡简 籃篮 籌筹 簽签 籠笼 糧粮 糾纠 " +
                    "紀纪 約约 紅红 級级 純纯 紗纱 納纳 紙纸 紋纹 練练 組组 " +
                    "細细 織织 終终 絕绝 統统 綁绑 結结 給给 絡络 絞绞 絹绢 " +
                    "經经 綜综 綠绿 綴缀 網网 維维 綸纶 緒绪 緘缄 線线 緝缉 " +
                    "緣缘 編编 緩缓 締缔 緯纬 緻致 縮缩 縱纵 纖纤 繩绳 繪绘 " +
                    "繫系 續续 纏缠 羅罗 罰罚 罷罢 翹翘 聳耸 聞闻 聯联 聰聪 " +
                    "職职 肅肃 脅胁 脈脉 膩腻 腳脚 膽胆 臉脸 臍脐 臟脏 艙舱 " +
                    "藝艺 蠻蛮 補补 裝装 製制 褲裤 襲袭 見见 規规 視视 覽览 " +
                    "觀观 覺觉 觸触 訂订 計计 訊讯 討讨 訓训 記记 講讲 許许 " +
                    "論论 設设 訪访 證证 評评 識识 譯译 詩诗 該该 詳详 認认 " +
                    "語语 誠诚 誤误 說说 請请 諸诸 課课 調调 談谈 誰谁 謀谋 " +
                    "諾诺 謂谓 謝谢 謹谨 譜谱 議议 護护 讀读 讓让 讚赞",
            )
        }
    }
}

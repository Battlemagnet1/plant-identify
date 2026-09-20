package com.plantidentify.data.export

import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.ui.components.label

/** 报告头部要用的三个统计数 */
data class ReportStats(
    val distinctPlants: Int,
    val observationCount: Int,
    val imageCount: Int,
)

/** 报告里的单次观察 */
data class ReportObservation(
    val timeText: String,
    val place: String?,
    val note: String?,
)

/**
 * 报告里的一张照片。
 *
 * [source] 直接就是 `<img src>` 的值，两种形态：
 * - 单文件模式：`data:image/jpeg;base64,...`
 * - zip 模式：`images/2026/09/xxx.jpg`
 *
 * 为空表示这张图读不出来（文件缺失），此时渲染占位框而不是破图。
 */
data class ReportImage(
    val role: ImageRole,
    val source: String?,
)

/** 报告里的一株植物 */
data class ReportPlant(
    val name: String,
    val latinName: String?,
    val commonNames: String?,
    val family: String?,
    val genus: String?,
    val category: String?,
    val confidence: Double,
    val description: String?,
    val morphologicalFeatures: String?,
    val growthHabits: String?,
    val floweringPeriod: String?,
    val fruitingPeriod: String?,
    val landscapeUses: String?,
    val careAdvice: String?,
    val pestControl: String?,
    val note: String?,
    val observations: List<ReportObservation>,
    val images: List<ReportImage>,
)

/**
 * 生成 HTML 报告（规格书第二十一节的结构）。
 *
 * ## 为什么是「逐株拼接」而不是一次性生成整个文档
 *
 * 报告最大的风险是体积（缺口 3）。如果把整份 HTML 变成一个 String，
 * 一个 50 MB 的文件在 Kotlin 里会占约 100 MB 的 UTF-16 内存 —— 直接 OOM。
 * 所以这里每个方法只返回**一株植物**的片段，由调用方逐段写进输出流，
 * 任意时刻内存里只有一株植物的内容。
 *
 * ## 为什么要转义
 *
 * 植物的简介、备注、地名都来自用户与模型，里面完全可能出现 `<`、`&`。
 * 不转义的话轻则排版错乱，重则把一段文本变成标签、破坏整个文档结构。
 */
object HtmlReportBuilder {

    /** charset 必须显式声明为 UTF-8，否则在电脑浏览器上中文会乱码 */
    fun documentStart(title: String, generatedAt: String, stats: ReportStats): String = """
        |<!DOCTYPE html>
        |<html lang="zh-CN">
        |<head>
        |<meta charset="UTF-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>${escape(title)} — 植物调查报告</title>
        |<style>
        |  :root { color-scheme: light; }
        |  body {
        |    margin: 0; padding: 32px 20px 64px;
        |    font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
        |    line-height: 1.7; color: #1f2933; background: #f7f8fa;
        |  }
        |  body.modal-open { overflow: hidden; }
        |  .page { max-width: 820px; margin: 0 auto; }
        |  h1 { font-size: 28px; margin: 0 0 4px; letter-spacing: .5px; }
        |  .subtitle { color: #6b7280; font-size: 14px; margin-bottom: 28px; }
        |  .stats { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
        |  .stat {
        |    flex: 1 1 140px; background: #fff; border: 1px solid #e5e7eb;
        |    border-radius: 10px; padding: 14px 16px;
        |  }
        |  .stat b { display: block; font-size: 24px; }
        |  .stat span { color: #6b7280; font-size: 13px; }
        |  .hint { color: #6b7280; font-size: 13px; margin: 10px 0 0; }
        |  hr.sep { border: 0; border-top: 1px solid #e5e7eb; margin: 28px 0; }
        |
        |  /* 列表：每株植物只显示一个「按钮」—— 编号 + 封面 + 名称/别名/学名。
        |     完整内容默认收起，点开后在整屏窗口里看，避免一屏铺满几千字。 */
        |  .plant { margin-bottom: 12px; }
        |  .plant-head {
        |    display: flex; gap: 14px; align-items: center; width: 100%;
        |    text-align: left; font: inherit; color: inherit; cursor: pointer;
        |    background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
        |    padding: 14px 16px;
        |  }
        |  .plant-head:hover { border-color: #9ca3af; }
        |  .plant-head:focus-visible { outline: 2px solid #2563eb; outline-offset: 2px; }
        |  .thumb {
        |    flex: 0 0 auto; width: 72px; height: 72px; border-radius: 8px;
        |    overflow: hidden; background: #f3f4f6;
        |    display: flex; align-items: center; justify-content: center;
        |    color: #9ca3af; font-size: 22px;
        |  }
        |  .thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
        |  .idx { flex: 0 0 auto; font-size: 13px; color: #9ca3af; font-variant-numeric: tabular-nums; }
        |  .titles { flex: 1 1 auto; min-width: 0; }
        |  .cn { display: block; font-size: 17px; font-weight: 600; }
        |  .alias { display: block; font-size: 13px; color: #6b7280; margin-top: 1px; }
        |  .latin { display: block; font-size: 13px; color: #6b7280; font-style: italic; }
        |  .taxon { display: block; font-size: 12px; color: #9ca3af; margin-top: 2px; }
        |  .chev { flex: 0 0 auto; font-size: 13px; color: #6b7280; white-space: nowrap; }
        |
        |  /* 详情：.plant.is-open 时整屏铺开 */
        |  .plant-body { display: none; }
        |  .plant.is-open .plant-body {
        |    display: block; position: fixed; inset: 0; z-index: 60; overflow: auto;
        |    background: #f7f8fa; padding: 24px 20px 64px;
        |  }
        |  .body-inner {
        |    max-width: 820px; margin: 0 auto; background: #fff;
        |    border: 1px solid #e5e7eb; border-radius: 12px; padding: 20px 22px;
        |  }
        |  .body-head {
        |    display: flex; align-items: flex-start; justify-content: space-between;
        |    gap: 12px; margin-bottom: 14px;
        |  }
        |  .body-head h2 { font-size: 20px; margin: 0; }
        |  .body-head .body-sub { font-size: 13px; color: #6b7280; margin-top: 2px; }
        |  .plant-close {
        |    flex: 0 0 auto; font: inherit; font-size: 13px; cursor: pointer;
        |    background: #fff; border: 1px solid #d1d5db; border-radius: 8px;
        |    padding: 6px 14px; color: #374151;
        |  }
        |  .plant-close:hover { background: #f3f4f6; }
        |
        |  .photos { display: flex; flex-wrap: wrap; gap: 10px; margin: 0 0 16px; }
        |  .photos figure { margin: 0; width: 200px; }
        |  .photos img { width: 100%; border-radius: 8px; display: block; }
        |  .photos figcaption { font-size: 12px; color: #6b7280; margin-top: 4px; }
        |  .missing { width: 200px; height: 140px; border: 1px dashed #d1d5db; border-radius: 8px;
        |             display: flex; align-items: center; justify-content: center;
        |             color: #9ca3af; font-size: 12px; }
        |  dl { margin: 0; }
        |  dt { font-weight: 600; color: #374151; font-size: 14px; margin-top: 12px; }
        |  dd { margin: 2px 0 0; white-space: pre-wrap; }
        |  table.obs { width: 100%; border-collapse: collapse; margin-top: 6px; font-size: 14px; }
        |  table.obs th, table.obs td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #eef0f3; }
        |  table.obs th { color: #6b7280; font-weight: 500; }
        |  footer { color: #9ca3af; font-size: 12px; margin-top: 32px; text-align: center; }
        |
        |  /* 打印时把全部内容摊平 —— 否则打印出来的报告只有一排按钮，
        |     而纸面上没法「点击查看」。这是打印与屏幕的真实差别，不是降级。 */
        |  @media print {
        |    body { padding: 0; background: #fff; }
        |    .plant-head { break-inside: avoid; }
        |    .plant-body { display: block !important; position: static !important;
        |                  overflow: visible !important; background: #fff !important; padding: 0 !important; }
        |    .plant-close { display: none; }
        |    .body-inner { border: 0; padding: 0; max-width: none; }
        |  }
        |</style>
        |<!--
        |  禁用 JavaScript 时的兜底：直接把全部内容摊平显示。
        |  折叠依赖脚本，脚本没了就必须把内容放出来 ——
        |  否则用户会拿到一份「每株植物都点不开」的报告。
        |-->
        |<noscript>
        |  <style>
        |    .plant-body { display: block !important; position: static !important;
        |                  background: #fff !important; padding: 0 !important; }
        |    .body-inner { border: 0; padding: 0; max-width: none; }
        |    .plant-close { display: none; }
        |    .plant-head { cursor: default; }
        |    .chev { display: none; }
        |  </style>
        |</noscript>
        |</head>
        |<body>
        |<div class="page">
        |<h1>${escape(title)}</h1>
        |<div class="subtitle">植物调查报告 · 生成时间 ${escape(generatedAt)}</div>
        |<div class="stats">
        |  <div class="stat"><b>${stats.distinctPlants}</b><span>不同植物</span></div>
        |  <div class="stat"><b>${stats.observationCount}</b><span>观察次数</span></div>
        |  <div class="stat"><b>${stats.imageCount}</b><span>照片数</span></div>
        |</div>
        |<p class="hint">三项分母不同：不同植物为去重后的物种数，观察次数为累计记录数，照片数为图片文件数。</p>
        |<hr class="sep">
        |""".trimMargin()

    /**
     * 一株植物：一个「按钮」+ 一份收起的完整内容。
     *
     * ## 为什么改成折叠
     *
     * 原来每株都把照片、八个百科字段、观察表格全部铺开。13 株就已经是
     * 一份要滑很久的长文 —— 而报告的第一用途是「快速翻看有哪些植物」，
     * 不是从头读到尾。折叠保证了：**一屏能看十来株**，
     * 想看细节再点开。
     *
     * 按钮上放的三样东西是刻意的：编号（知道有多少株）、封面（一眼认出）、
     * 名称 / 别名 / 学名（确认是不是它）。其余一律进弹窗。
     *
     * ## 为什么不用 `<details>`
     *
     * `<details>` 是内联展开 —— 点开后把下方内容顶下去，在长列表里
     * 用户会瞬间失去位置感。这里要的是「整一个窗口」，用 `position: fixed`
     * 的整屏面板更贴近预期。
     *
     * 字段顺序照规格书第二十一节，只渲染有值的字段 —— 一堆空的
     * 「形态特征：—」只会让报告显得像没做完。
     */
    fun plantCard(index: Int, plant: ReportPlant): String = buildString {
        val no = index.toString().padStart(2, '0')
        val cover = plant.images.firstOrNull()?.source

        append("<section class=\"plant\">\n")

        // ---------- 折叠状态的「按钮」 ----------
        // 用 <button> 而不是 <div onclick>：焦点、键盘 Enter/Space、
        // 屏幕阅读器都能直接工作，不必自己补 ARIA 与键盘处理
        append("<button type=\"button\" class=\"plant-head\">")
        append("<span class=\"idx\">").append(no).append("</span>")
        append("<span class=\"thumb\">")
        if (cover != null) {
            append("<img src=\"").append(cover)
                .append("\" alt=\"").append(escape(plant.name)).append("\">")
        } else {
            // 没有图时用植物名首字当占位，比一个破图图标有信息量
            append(escape(plant.name.take(1).ifBlank { "?" }))
        }
        append("</span>")
        append("<span class=\"titles\">")
        append("<b class=\"cn\">").append(escape(plant.name)).append("</b>")
        plant.commonNames?.takeIf { it.isNotBlank() }?.let { alias ->
            append("<span class=\"alias\">别名：").append(escape(alias)).append("</span>")
        }
        plant.latinName?.takeIf { it.isNotBlank() }?.let { latin ->
            append("<i class=\"latin\">").append(escape(latin)).append("</i>")
        }
        // family / genus 的值本身就带着「科」「属」二字（如「千屈菜科」「紫薇属」），
        // 所以只能加前缀标签，不能再拼后缀
        val taxon = listOfNotNull(
            plant.family?.takeIf { it.isNotBlank() }?.let { "科 $it" },
            plant.genus?.takeIf { it.isNotBlank() }?.let { "属 $it" },
        ).joinToString(" · ")
        if (taxon.isNotEmpty()) {
            append("<span class=\"taxon\">").append(escape(taxon)).append("</span>")
        }
        append("</span>")
        append("<span class=\"chev\">查看详情 ›</span>")
        append("</button>\n")

        // ---------- 展开后的完整内容 ----------
        append("<div class=\"plant-body\">\n<div class=\"body-inner\">\n")

        append("<div class=\"body-head\"><div>")
        append("<h2><span class=\"idx\">").append(no).append("</span> ")
            .append(escape(plant.name)).append("</h2>")
        val subtitleParts = listOfNotNull(
            plant.commonNames?.takeIf { it.isNotBlank() }?.let { "别名 $it" },
            plant.latinName?.takeIf { it.isNotBlank() },
        )
        if (subtitleParts.isNotEmpty()) {
            append("<div class=\"body-sub\">")
                .append(escape(subtitleParts.joinToString(" · "))).append("</div>")
        }
        append("</div>")
        append("<button type=\"button\" class=\"plant-close\">关闭</button>")
        append("</div>\n")

        if (plant.images.isNotEmpty()) {
            append("<div class=\"photos\">\n")
            plant.images.forEach { image ->
                append("<figure>")
                if (image.source != null) {
                    append("<img src=\"")
                    append(image.source)
                    append("\" alt=\"")
                    append(escape(plant.name))
                    append("\">")
                } else {
                    append("<div class=\"missing\">图片缺失</div>")
                }
                append("<figcaption>")
                append(escape(image.role.label))
                append("</figcaption></figure>\n")
            }
            append("</div>\n")
        }

        append("<dl>\n")
        field("正式中文名称", plant.name)
        field("常用名称 / 俗称", plant.commonNames)
        field("拉丁学名", plant.latinName)
        field("科", plant.family)
        field("属", plant.genus)
        field("植物类型", plant.category)
        field("AI识别置信度", "${(plant.confidence * 100).toInt()}%")
        field("植物简介", plant.description)
        field("形态特征", plant.morphologicalFeatures)
        field("生长习性", plant.growthHabits)
        field("花期", plant.floweringPeriod)
        field("果期", plant.fruitingPeriod)
        field("园林用途", plant.landscapeUses)
        field("养护建议", plant.careAdvice)
        field("病虫害防治", plant.pestControl)
        field("备注", plant.note)
        append("</dl>\n")

        if (plant.observations.isNotEmpty()) {
            append("<dl><dt>观察记录</dt></dl>\n")
            append("<table class=\"obs\">\n")
            append("<tr><th>时间</th><th>地点</th><th>备注</th></tr>\n")
            plant.observations.forEach { observation ->
                append("<tr><td>")
                append(escape(observation.timeText))
                append("</td><td>")
                append(escape(observation.place ?: "—"))
                append("</td><td>")
                append(escape(observation.note ?: "—"))
                append("</td></tr>\n")
            }
            append("</table>\n")
        }

        append("</div>\n</div>\n</section>\n")
    }

    /**
     * 收尾 + 折叠交互。
     *
     * ## 为什么可以有一个 `<script>`
     *
     * 报告是**单文件、离线、无外部依赖**的：脚本写在文件里，不请求任何 CDN。
     * 浏览器打开本地 HTML 执行内联脚本没有任何限制（不像 `file://` 下的
     * fetch/XHR 会被 CORS 拦掉），所以这是可靠的。
     *
     * ## 脚本干了什么
     *
     * 只做一件事：点按钮时给它所属的 `.plant` 加 `is-open`，
     * 同时给 `body` 加 `modal-open` 锁滚动。开新的会自动先关掉旧的那个 ——
     * 否则连点两株植物会叠出两层整屏面板，关掉一层还剩一层。
     *
     * 只用了 `classList`，不写 innerHTML、不用任何用户内容拼接字符串，
     * 所以即便植物名里带 `<script>` 也只是文本（何况服务端早已转义过一遍）。
     */
    fun documentEnd(disclaimer: String): String = """
        |<footer>${escape(disclaimer)}</footer>
        |</div>
        |<script>
        |(function () {
        |  function closeOpen() {
        |    var opened = document.querySelector('.plant.is-open');
        |    if (opened) opened.classList.remove('is-open');
        |    document.body.classList.remove('modal-open');
        |  }
        |  document.querySelectorAll('.plant-head').forEach(function (head) {
        |    head.addEventListener('click', function () {
        |      var section = head.closest('.plant');
        |      if (!section) return;
        |      closeOpen();
        |      section.classList.add('is-open');
        |      document.body.classList.add('modal-open');
        |      var panel = section.querySelector('.plant-body');
        |      if (panel && panel.scrollTo) panel.scrollTo(0, 0);
        |    });
        |  });
        |  document.querySelectorAll('.plant-close').forEach(function (button) {
        |    button.addEventListener('click', closeOpen);
        |  });
        |  document.addEventListener('keydown', function (event) {
        |    if (event.key === 'Escape') closeOpen();
        |  });
        |})();
        |</script>
        |</body>
        |</html>
        |""".trimMargin()

    /** 图片以 Data URI 内嵌 —— 单文件 HTML 的关键 */
    fun dataUri(jpegBytes: ByteArray): String =
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(
            jpegBytes,
            android.util.Base64.NO_WRAP,
        )

    private fun StringBuilder.field(label: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return
        append("<dt>").append(escape(label)).append("</dt>")
        append("<dd>").append(escape(text)).append("</dd>\n")
    }

    /**
     * HTML 转义。
     *
     * `&` 必须最先替换 —— 否则后面替换出来的 `&lt;` 会被二次转义成 `&amp;lt;`。
     */
    fun escape(raw: String): String = buildString(raw.length + 16) {
        raw.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}

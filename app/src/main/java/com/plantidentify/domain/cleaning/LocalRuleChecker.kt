package com.plantidentify.domain.cleaning

/**
 * 本地规则检查器（方案 §7.5）。
 *
 * ## 定位：绝大多数问题在这一层就该被找出来
 *
 * AI 只用来判「两株是不是同一株」这种本地算不出来的事。
 * 字段空了、坐标越界了、照片文件没了 —— 这些都有确定答案，
 * 送去问模型既慢又不准（模型看不到磁盘），还按次收费。
 *
 * ## 每条规则一个函数，统一返回 `List<CleaningIssue>`
 *
 * 拆开的理由是**可测试性**：[checkCoordinates] 的正确性只取决于
 * 它自己的输入输出，不需要构造一整个库。也让「加一条规则」变成
 * 「加一个函数 + 在 [check] 里挂上」，而不用动任何既有逻辑。
 *
 * 所有函数都是纯函数（不读库、不碰文件），需要外部世界的结论时
 * 由参数传入（[ImageHealth]、[isAiJsonUsable]）—— 见各处的注释。
 */
object LocalRuleChecker {

    /**
     * 早于此时间的时间戳视为脏数据（2000-01-01）。
     *
     * 用固定常量而不是「十年前」：后者会让判定结果随时间漂移，
     * 同一份数据今年报「正常」、明年报「异常」，用户会以为是自己改坏了。
     */
    private const val MIN_VALID_TIMESTAMP = 946_684_800_000L

    /** 未来时间的容忍窗口：1 天（时区/时钟轻微偏差不该被当成异常） */
    private const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000

    /** 分类字段超过这个长度就明显不是「落叶灌木」这种短标签了 */
    private const val CATEGORY_MAX_LENGTH = 24

    /**
     * 跑全部规则。
     *
     * @param records **未删除**的档案快照
     * @param knownPlantIds **全部**档案 id（含回收站里的）
     * @param allObservations 全库观察，用于孤儿检测。
     *
     *        **不能省成 [observations]**：增量扫描时 [observations] 只含
     *        本次要检查的那几株，而「孤儿」的定义恰恰是「找不到父档案」——
     *        它根本不会出现在增量集合里，于是这个规则永远不报。
     *        默认值只是为了让全量调用的写法短一点。
     * @param isAiJsonUsable 由编排层注入的 JSON 可用性判定
     *        （真正的实现要用 `TolerantJsonParser`，它依赖 `org.json`，
     *        不能出现在这个纯 Kotlin 包里；单测直接给一个恒真/恒假的 lambda）
     */
    fun check(
        records: List<RecordSnapshot>,
        observations: List<ObservationSnapshot>,
        knownPlantIds: Set<Long>,
        allObservations: List<ObservationSnapshot> = observations,
        imageHealth: ImageHealth = ImageHealth.NONE,
        duplicateImageGroups: Map<String, List<String>> = emptyMap(),
        orphanImageCount: Int = 0,
        isAiJsonUsable: (String) -> Boolean = { true },
        now: Long = System.currentTimeMillis(),
    ): List<CleaningIssue> = buildList {
        addAll(checkMissingFields(records))
        addAll(checkOrphanObservations(allObservations, knownPlantIds))
        addAll(checkObservationsWithoutImage(observations))
        addAll(checkImageFiles(observations, imageHealth))
        addAll(checkTimestamps(observations, now))
        addAll(checkCoordinates(observations))
        addAll(checkConfidence(records))
        addAll(checkInvalidJson(observations, isAiJsonUsable))
        addAll(checkCategory(records))
        addAll(checkDuplicateImages(observations, duplicateImageGroups))
        addAll(checkOrphanImages(orphanImageCount))
    }

    // ---------------------------------------------------------------- 完整性

    /**
     * 关键字段为空。
     *
     * 一个字段一条问题（而不是「这株缺了 3 个字段」一条）——
     * 因为**用户能采取的动作不同**：补拉丁名要去查资料，
     * 补科属往往能靠识别重跑。混成一条，用户点「知道了」之后
     * 另外两个字段就永远没人管了。
     */
    fun checkMissingFields(records: List<RecordSnapshot>): List<CleaningIssue> = buildList {
        for (r in records) {
            if (r.name.isBlank()) {
                add(
                    issue(
                        CleaningIssueType.MISSING_FIELD, CleaningSeverity.HIGH, r.id,
                        discriminator = "name",
                        reason = "正式中文名称为空 —— 档案没有可识别的标题",
                    ),
                )
            }
            if (r.latinName.isNullOrBlank()) {
                add(
                    issue(
                        CleaningIssueType.MISSING_FIELD, CleaningSeverity.MEDIUM, r.id,
                        discriminator = "latinName",
                        reason = "缺少拉丁学名 —— 它是跨语言比对同种植物最可靠的依据",
                    ),
                )
            }
            if (r.family.isNullOrBlank()) {
                add(
                    issue(
                        CleaningIssueType.MISSING_FIELD, CleaningSeverity.LOW, r.id,
                        discriminator = "family",
                        reason = "缺少「科」",
                    ),
                )
            }
            if (r.genus.isNullOrBlank()) {
                add(
                    issue(
                        CleaningIssueType.MISSING_FIELD, CleaningSeverity.LOW, r.id,
                        discriminator = "genus",
                        reason = "缺少「属」",
                    ),
                )
            }
        }
    }

    /**
     * 观察记录指向了不存在的档案。
     *
     * ## 为什么用 [knownPlantIds] 而不是 `records`
     *
     * 软删除之后，**回收站里的档案仍然是合法的父记录**。若拿
     * 「未删除的档案」去判定，把一株植物移入回收站会让它名下的
     * 每一次观察都立刻变成「无主孤儿」—— 用户会看到几十条假的严重问题，
     * 而真相只是他删了一株植物。
     *
     * 所以判定基准必须是「**所有**档案 id」，含已删的。
     */
    fun checkOrphanObservations(
        observations: List<ObservationSnapshot>,
        knownPlantIds: Set<Long>,
    ): List<CleaningIssue> = observations
        .filter { it.plantId !in knownPlantIds }
        .map { obs ->
            issue(
                CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, obs.plantId,
                discriminator = "obs:${obs.id}",
                reason = "观察记录 #${obs.id} 指向的档案 #${obs.plantId} 不存在",
            )
        }

    /** 一次观察一张照片都没有 —— 识别无从谈起 */
    fun checkObservationsWithoutImage(
        observations: List<ObservationSnapshot>,
    ): List<CleaningIssue> = observations
        .filter { it.imagePaths.isEmpty() }
        .map { obs ->
            issue(
                CleaningIssueType.NO_IMAGE, CleaningSeverity.MEDIUM, obs.plantId,
                discriminator = "obs:${obs.id}",
                reason = "观察记录 #${obs.id} 没有照片",
            )
        }

    /**
     * 照片文件不见了 / 解不开。
     *
     * 逐张报（按「档案 + 丢失 vs 损坏」聚合），而不是逐株报 ——
     * 一株有 8 张照片、坏了 3 张，用户需要知道**是哪 3 张**。
     */
    fun checkImageFiles(
        observations: List<ObservationSnapshot>,
        imageHealth: ImageHealth,
    ): List<CleaningIssue> {
        if (imageHealth.isEmpty) return emptyList()

        val issues = mutableListOf<CleaningIssue>()
        val missByPlant = mutableMapOf<Long, MutableList<String>>()
        val brokenByPlant = mutableMapOf<Long, MutableList<String>>()

        for (obs in observations) {
            for (path in obs.imagePaths) {
                when {
                    path in imageHealth.missing -> missByPlant.getOrPut(obs.plantId) { mutableListOf() }.add(path)
                    path in imageHealth.broken -> brokenByPlant.getOrPut(obs.plantId) { mutableListOf() }.add(path)
                }
            }
        }

        missByPlant.forEach { (plantId, paths) ->
            issues += issue(
                CleaningIssueType.MISSING_IMAGE, CleaningSeverity.HIGH, plantId,
                discriminator = "missing",
                reason = "${paths.size} 张照片在磁盘上已不存在：${names(paths)}",
            )
        }
        brokenByPlant.forEach { (plantId, paths) ->
            issues += issue(
                CleaningIssueType.BROKEN_IMAGE, CleaningSeverity.HIGH, plantId,
                discriminator = "broken",
                reason = "${paths.size} 张照片文件无法解码（可能已损坏）：${names(paths)}",
            )
        }
        return issues
    }

    /**
     * 照片行的归属观察不存在。
     *
     * 编排层传的是**计数**而不是路径列表：这些行 JOIN 不到任何观察，
     * 所以它们根本不会出现在 [observations] 的快照里，
     * 只能靠「图片表总行数 − 快照里的路径数」发现。
     */
    fun checkOrphanImages(orphanImageCount: Int): List<CleaningIssue> =
        if (orphanImageCount <= 0) {
            emptyList()
        } else {
            listOf(
                issue(
                    CleaningIssueType.FOREIGN_KEY, CleaningSeverity.MEDIUM,
                    recordId = null,
                    discriminator = "image",
                    reason = "有 $orphanImageCount 张照片记录找不到所属的观察（属于数据库层面的残留）",
                ),
            )
        }

    // ---------------------------------------------------------------- 格式

    /** 观察时间越界：未来时间，或早于 2000 年 */
    fun checkTimestamps(
        observations: List<ObservationSnapshot>,
        now: Long = System.currentTimeMillis(),
    ): List<CleaningIssue> = observations
        .filter { it.timestamp > now + FUTURE_TOLERANCE_MS || it.timestamp < MIN_VALID_TIMESTAMP }
        .map { obs ->
            issue(
                CleaningIssueType.INVALID_TIMESTAMP, CleaningSeverity.MEDIUM, obs.plantId,
                discriminator = "obs:${obs.id}",
                reason = "观察记录 #${obs.id} 的时间戳为 ${obs.timestamp}，不在合理范围内",
            )
        }

    /**
     * 坐标非法。
     *
     * `(0, 0)` 单独判 —— 它在数学上完全合法（几内亚湾外海），
     * 但在这个应用里它几乎只有一个来源：定位失败后没拿到值、
     * 被默认写成了 0。设备真在几内亚湾的概率可以忽略。
     *
     * 只给了一个坐标（另一个为 null）也是异常：部分定位比没有定位更误导，
     * 用户会看到一个「有坐标」的标记却算不出真实位置。
     */
    fun checkCoordinates(observations: List<ObservationSnapshot>): List<CleaningIssue> =
        observations.mapNotNull { obs ->
            val lat = obs.latitude
            val lng = obs.longitude
            val reason = when {
                lat == null && lng == null -> return@mapNotNull null
                lat == null || lng == null ->
                    "观察记录 #${obs.id} 只记录了半个坐标（纬度=${lat ?: "空"}，经度=${lng ?: "空"}）"
                lat !in -90.0..90.0 -> "观察记录 #${obs.id} 的纬度 $lat 超出 ±90"
                lng !in -180.0..180.0 -> "观察记录 #${obs.id} 的经度 $lng 超出 ±180"
                lat == 0.0 && lng == 0.0 ->
                    "观察记录 #${obs.id} 的坐标为 (0, 0) —— 通常是定位失败被写成了默认值"
                else -> null
            } ?: return@mapNotNull null

            issue(
                CleaningIssueType.INVALID_COORDINATE, CleaningSeverity.MEDIUM, obs.plantId,
                discriminator = "obs:${obs.id}",
                reason = reason,
            )
        }

    /** 置信度越界（合法区间是 0.0–1.0，所以「未分析」的 0.0 不算异常） */
    fun checkConfidence(records: List<RecordSnapshot>): List<CleaningIssue> = records
        .filter { it.confidence !in 0.0..1.0 }
        .map { r ->
            issue(
                CleaningIssueType.INVALID_CONFIDENCE, CleaningSeverity.MEDIUM, r.id,
                discriminator = "confidence",
                reason = "识别置信度 ${r.confidence} 超出 0–1 的合法区间",
            )
        }

    /** AI 原始结果（可追溯性的最后一道保险）解析不了 */
    fun checkInvalidJson(
        observations: List<ObservationSnapshot>,
        isUsable: (String) -> Boolean,
    ): List<CleaningIssue> = observations.mapNotNull { obs ->
        val json = obs.aiResultJson
        if (json.isNullOrBlank() || isUsable(json)) return@mapNotNull null
        issue(
            CleaningIssueType.INVALID_JSON, CleaningSeverity.LOW, obs.plantId,
            discriminator = "obs:${obs.id}",
            reason = "观察记录 #${obs.id} 的 AI 原始结果无法解析（档案内容不受影响，仅失去可追溯性）",
        )
    }

    /**
     * 分类字段可疑。
     *
     * ## 为什么不是白名单校验
     *
     * 方案原本写的是「`checkCategory`（白名单）」。落地时改成「可疑值检测」：
     * 本项目的分类是**自由文本**（编辑页的提示语是「如 落叶灌木、一年生草本」），
     * 没有枚举约束。一旦按白名单判，大量「常绿乔木」「多年生水生草本」
     * 这类完全正确的写法都会被报成问题 —— 一个每天误报几十条的功能，
     * 用户三天就会关掉，连带真正的问题也一起被忽略。
     *
     * 所以只抓「明显不是短标签」的值：太长、带句读、像一整句话。
     */
    fun checkCategory(records: List<RecordSnapshot>): List<CleaningIssue> =
        records.mapNotNull { r ->
            val value = r.category?.trim().orEmpty()
            if (value.isEmpty()) return@mapNotNull null

            val suspicious = value.length > CATEGORY_MAX_LENGTH ||
                value.contains('\n') ||
                value.contains('。') ||
                value.contains('，') ||
                value.endsWith("的")

            if (!suspicious) return@mapNotNull null
            issue(
                CleaningIssueType.INVALID_CATEGORY, CleaningSeverity.LOW, r.id,
                discriminator = "category",
                reason = "「植物类型」不像一个短标签：${value.take(30)}",
            )
        }

    // ------------------------------------------------------------ 重复照片

    /**
     * 内容相同的照片。
     *
     * @param groups sha256 → 拥有该内容的照片路径（由 `ImageFingerprintDao` 驱动）
     *
     * 指纹用 **sha256** 而不是档案 id：判据是文件内容，路径与 id 都可能变
     * （备份恢复后路径会整体重写、合并后档案会消失），
     * 用 id 拼指纹会让同一条问题在两次扫描之间「换个身份」重新出现。
     */
    fun checkDuplicateImages(
        observations: List<ObservationSnapshot>,
        groups: Map<String, List<String>>,
    ): List<CleaningIssue> {
        if (groups.isEmpty()) return emptyList()

        // 路径 → 归属档案。一张图可能被多次观察引用，取全部
        val owners = mutableMapOf<String, MutableSet<Long>>()
        for (obs in observations) {
            for (path in obs.imagePaths) {
                owners.getOrPut(path) { mutableSetOf() }.add(obs.plantId)
            }
        }

        return groups.mapNotNull { (hash, paths) ->
            val plantIds = paths.flatMap { owners[it].orEmpty() }.distinct().sorted()
            if (plantIds.isEmpty()) return@mapNotNull null

            val crossPlant = plantIds.size > 1
            issue(
                type = CleaningIssueType.DUPLICATE_IMAGE,
                // 跨档案重复更值得看：它往往意味着两株其实是同一株
                severity = if (crossPlant) CleaningSeverity.MEDIUM else CleaningSeverity.LOW,
                recordIds = plantIds,
                discriminator = hash,
                reason = if (crossPlant) {
                    "${paths.size} 张内容完全相同的照片出现在 ${plantIds.size} 株植物下" +
                        "（可能是同一株被重复建档）"
                } else {
                    "同一株植物下有 ${paths.size} 张内容完全相同的照片"
                },
            )
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun issue(
        type: CleaningIssueType,
        severity: CleaningSeverity,
        recordId: Long?,
        discriminator: String,
        reason: String,
    ): CleaningIssue = issue(
        type = type,
        severity = severity,
        recordIds = listOfNotNull(recordId),
        discriminator = discriminator,
        reason = reason,
    )

    private fun issue(
        type: CleaningIssueType,
        severity: CleaningSeverity,
        recordIds: List<Long>,
        discriminator: String,
        reason: String,
    ): CleaningIssue = CleaningIssue(
        type = type,
        severity = severity,
        recordIds = recordIds,
        reason = reason,
        discriminator = discriminator,
    )

    /** 只取文件名，避免把整条长路径塞进给用户看的一句话 */
    private fun names(paths: List<String>): String =
        paths.take(3).joinToString("、") { it.substringAfterLast('/') } +
            if (paths.size > 3) " 等 ${paths.size} 张" else ""
}

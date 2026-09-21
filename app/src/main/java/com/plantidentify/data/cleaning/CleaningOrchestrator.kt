package com.plantidentify.data.cleaning

import com.plantidentify.data.ai.TolerantJsonParser
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.CleaningIssueEntity
import com.plantidentify.data.local.entity.CleaningStateEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.cleaning.CandidateSetBuilder
import com.plantidentify.domain.cleaning.CleaningIssue
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.CleaningIssueType
import com.plantidentify.domain.cleaning.CleaningSeverity
import com.plantidentify.domain.cleaning.DuplicateMatcher
import com.plantidentify.domain.cleaning.ImageHealth
import com.plantidentify.domain.cleaning.LocalRuleChecker
import com.plantidentify.domain.cleaning.MergePlan
import com.plantidentify.domain.cleaning.MergePlanner
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前清洗算法的版本。
 *
 * **改规则、改阈值、改 AI prompt 时必须 +1** ——
 * 增量扫描靠 `updatedAt > lastCheckedAt` 挑要检查的档案，
 * 而改算法不会让任何档案的 `updatedAt` 变化，于是新规则永远跑不起来，
 * 而且没有任何报错。版本号对不上就强制全扫一次。
 *
 * 这是一条**靠人记得**的约定。它比自动检测便宜得多（自动检测要么哈希
 * 整个规则源码，要么把阈值也塞进表里）—— 但也就意味着它必须写在这里，
 * 让下一个改阈值的人看得见。
 */
const val CLEANING_VERSION = 1

/** 一次扫描的结果概览（清洗中心页面的顶部数字） */
data class CleaningRunSummary(
    /** 本次检查了几条档案（增量时小于总数） */
    val checkedRecords: Int,

    /** 本库里一共有多少条档案 */
    val totalRecords: Int,

    /** 生成/比对了多少对候选 */
    val candidatePairs: Int,

    /** 当前待处理问题总数 */
    val openIssues: Int,

    /** 本次新发现的问题 */
    val newIssues: Int,

    /** 本次自动判定为「已解决」的问题（问题真的没了） */
    val resolvedIssues: Int,

    /** 等待 AI 复核的候选数（成本护栏的输入） */
    val aiPending: Int,

    /** 候选集被硬上限截断，本次只是部分扫描 */
    val partialScan: Boolean,

    val deepScan: Boolean,

    val durationMs: Long,
)

/**
 * 清洗编排：把 `domain/cleaning` 的纯算法接到真实的库与磁盘上。
 *
 * ## 全量与增量的分界（很重要）
 *
 * | 规则类型 | 何时跑 | 理由 |
 * |---|---|---|
 * | 单条档案的（字段为空、时间越界、坐标非法…） | **只跑变更过的档案** | 档案没变，结论必然不变；全跑纯属浪费 |
 * | 全局不变式（孤儿观察、孤儿照片、重复照片） | **每次都跑** | 它们由「库的整体状态」决定，不挂在任何一条档案的 `updatedAt` 上；不跑就会漏掉后来才产生的孤儿 |
 * | 重复候选 | 全量建索引，但**只报涉及变更档案的对** | 候选必须和全库比（新档案可能和任何一条重复），但已有档案之间的老候选没必要重复报 |
 *
 * 漏掉这个分界会出现两种都很难查的 bug：增量时孤儿永远不报，
 * 或者每次检查都把所有老问题重报一遍。
 */
class CleaningOrchestrator(
    private val database: PlantIdentifyDatabase,
    private val imageStore: ImageStore,
    private val fingerprinter: ImageFingerprinter,
) {

    private val recordDao = database.plantRecordDao()
    private val observationDao = database.plantObservationDao()
    private val imageDao = database.observationImageDao()
    private val issueDao = database.cleaningIssueDao()
    private val stateDao = database.cleaningStateDao()

    /**
     * 跑一次扫描。
     *
     * @param deep 用户点了「全库深度检查」——忽略游标，所有档案都查一遍
     */
    suspend fun run(deep: Boolean = false): CleaningRunSummary = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val state = stateDao.get()
        // 算法版本对不上 → 必须全扫，否则新规则在增量下永远不会触发
        val forceDeep = deep || state == null || state.cleaningVersion != CLEANING_VERSION
        val cursor = state?.lastCheckedAt ?: 0L

        // ---------------- 载入（一次性，后面全在内存里算）----------------
        val plants = recordDao.getAll()
        val knownPlantIds = plants.mapTo(HashSet()) { it.id }
        val alivePlants = plants.filter { it.deletedAt == null }

        val observations = observationDao.getAll()
        val images = imageDao.getAll()

        val imagesByObservation = images.groupBy { it.observationId }
        val observationsByPlant = observations.groupBy { it.plantId }

        fun snapshotOf(plant: PlantRecordEntity): RecordSnapshot {
            val own = observationsByPlant[plant.id].orEmpty()
            val imageCount = own.sumOf { imagesByObservation[it.id]?.size ?: 0 }
            return plant.toSnapshot(observationCount = own.size, imageCount = imageCount)
        }

        val allRecords = alivePlants.map(::snapshotOf)
        val allObservations = observations.map { obs ->
            obs.toSnapshot(imagesByObservation[obs.id].orEmpty().map { it.imagePath })
        }

        // 增量：只有「上次检查之后被改过」的档案才需要重新判定。
        // 用 started 而不是结束时间做下一次的游标，避免扫描期间被改的
        // 档案被跳过（那些改动会落进下一次扫描）
        val subjects = if (forceDeep) {
            allRecords
        } else {
            allRecords.filter { it.updatedAt > cursor }
        }

        if (subjects.isEmpty()) {
            stateDao.put(CleaningStateEntity(lastCheckedAt = started, cleaningVersion = CLEANING_VERSION))
            val open = issueDao.countOpen()
            return@withContext CleaningRunSummary(
                checkedRecords = 0,
                totalRecords = allRecords.size,
                candidatePairs = 0,
                openIssues = open,
                newIssues = 0,
                resolvedIssues = 0,
                aiPending = countAiPending(),
                partialScan = false,
                deepScan = forceDeep,
                durationMs = System.currentTimeMillis() - started,
            )
        }

        val subjectIds = subjects.mapTo(HashSet()) { it.id }
        val subjectObservations = allObservations.filter { it.plantId in subjectIds }

        // ---------------- 照片：存在性 / 可解码性 / 内容哈希 ----------------
        val report = fingerprinter.scan(
            allObservations.flatMap { it.imagePaths }.distinct(),
        )
        val orphanImageCount = images.count { it.observationId !in observations.mapTo(HashSet()) { o -> o.id } }

        // ---------------- 规则检查 ----------------
        val ruleIssues = LocalRuleChecker.check(
            records = subjects,
            observations = subjectObservations,
            knownPlantIds = knownPlantIds,
            allObservations = allObservations,
            imageHealth = ImageHealth(missing = report.missing, broken = report.broken),
            duplicateImageGroups = report.duplicateGroups(),
            orphanImageCount = orphanImageCount,
            isAiJsonUsable = { TolerantJsonParser.parse(it) !is TolerantJsonParser.ParseAttempt.Failed },
            now = started,
        )

        // ---------------- 疑似重复：候选集 + 六级判定 ----------------
        val byId = allRecords.associateBy { it.id }
        val candidateResult = CandidateSetBuilder.build(allRecords)
        val duplicateIssues = candidateResult.pairs.mapNotNull { pair ->
            // 增量：两只都不在本次的检查范围内，说明之前已经报过，
            // 再报一次只会让用户看到「同一件事」重复出现
            if (pair.aId !in subjectIds && pair.bId !in subjectIds) return@mapNotNull null
            val a = byId[pair.aId] ?: return@mapNotNull null
            val b = byId[pair.bId] ?: return@mapNotNull null

            val hit = DuplicateMatcher.match(a, b) ?: return@mapNotNull null
            CleaningIssue(
                type = CleaningIssueType.POSSIBLE_DUPLICATE,
                // 级 1、2 是本地可以拍板的证据，排前面
                severity = if (hit.isConclusive) CleaningSeverity.HIGH else CleaningSeverity.MEDIUM,
                recordIds = listOf(pair.aId, pair.bId),
                similarity = hit.similarity,
                reason = hit.reason,
                // 刻意**不带**等级：等级会随用户补全字段而变化
                // （补上拉丁名后从级 3 升到级 1），带进指纹会让同一条问题
                // 换个身份重生，「忽略」随即失效
                discriminator = null,
            )
        }

        val issues = ruleIssues + duplicateIssues

        // ---------------- 落库 ----------------
        val now = System.currentTimeMillis()
        val rowIds = issueDao.insertIgnore(
            issues.map { CleaningIssueEntity.fromDomain(it, now) },
        )
        val newIssues = rowIds.count { it != -1L }

        // ---------------- 自动解决：问题真的没了 ----------------
        //
        // 只处理**涉及本次检查对象**的问题。增量扫描时其他档案的问题
        // 根本没参与计算，把它们一并标成「已解决」会凭空清掉一批待办。
        val currentFingerprints = issues.mapTo(HashSet()) { it.fingerprint }
        var resolved = 0
        for (existing in issueDao.getOpen()) {
            if (existing.fingerprint in currentFingerprints) continue
            if (existing.recordIdList().none { it in subjectIds }) continue
            issueDao.setStatus(existing.id, CleaningIssueStatus.RESOLVED, now)
            resolved++
        }

        stateDao.put(CleaningStateEntity(lastCheckedAt = started, cleaningVersion = CLEANING_VERSION))

        CleaningRunSummary(
            checkedRecords = subjects.size,
            totalRecords = allRecords.size,
            candidatePairs = candidateResult.pairs.size,
            openIssues = issueDao.countOpen(),
            newIssues = newIssues,
            resolvedIssues = resolved,
            aiPending = countAiPending(),
            partialScan = candidateResult.partial,
            deepScan = forceDeep,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 给一条「疑似重复」问题生成合并方案。
     *
     * 保留哪一株由 [MergePlanner.defaultKeep] 决定（观察多的活），
     * 用户可以在预览页改。返回 null 表示这条问题不是两个档案的疑似重复
     * （比如它已经被删掉了一边）。
     */
    suspend fun loadMergePlan(issueId: Long): MergePlan? = withContext(Dispatchers.IO) {
        val issue = issueDao.getById(issueId) ?: return@withContext null
        if (issue.type != CleaningIssueType.POSSIBLE_DUPLICATE) return@withContext null

        val ids = issue.recordIdList()
        if (ids.size != 2) return@withContext null

        val plants = recordDao.getAll().filter { it.deletedAt == null }.associateBy { it.id }
        val observations = observationDao.getAll()
        val images = imageDao.getAll()
        val imagesByObservation = images.groupBy { it.observationId }

        fun snapshotOf(id: Long): RecordSnapshot? {
            val plant = plants[id] ?: return null
            val own = observations.filter { it.plantId == id }
            return plant.toSnapshot(
                observationCount = own.size,
                imageCount = own.sumOf { imagesByObservation[it.id]?.size ?: 0 },
            )
        }

        val first = snapshotOf(ids[0]) ?: return@withContext null
        val second = snapshotOf(ids[1]) ?: return@withContext null

        val keep = MergePlanner.defaultKeep(first, second)
        val drop = if (keep.id == first.id) second else first
        MergePlanner.plan(keep, drop)
    }

    /** 待 AI 复核的候选数（`POSSIBLE_DUPLICATE` 且还没过 AI） */
    private suspend fun countAiPending(): Int =
        issueDao.getOpen().count {
            it.type == CleaningIssueType.POSSIBLE_DUPLICATE && !it.aiUsed
        }

}

package com.plantidentify.data.recognition

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.plantidentify.data.local.dao.RecognitionTaskDao
import com.plantidentify.data.local.dao.RecognitionTaskImageDao
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.local.entity.RecognitionTaskEntity
import com.plantidentify.data.local.entity.RecognitionTaskStatus
import com.plantidentify.data.local.entity.RecognitionTaskImageEntity
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 与「添加植物」一致的每任务照片上限：超过 5 张，边际收益撑不起上传体积 */
const val MAX_IMAGES = 5

/**
 * 识别任务队列 —— 任务与 WorkManager 之间的唯一通道。
 *
 * ## 职责边界：DB 是真相，WorkManager 只是触发器
 *
 * 任务列表页**只读数据库**（`RecognitionTaskDao`），永远不读 WorkManager 的状态。
 * WorkManager 负责「什么时候跑、断网了等恢复再跑、进程死了帮我续」；
 * 任务行负责「这条任务是什么、跑到哪了、结果是什么」。
 *
 * 两边各持久化一份是刻意的设计：WorkManager 的 workdb 是它内部的实现细节，
 * 版本升级/清理时可能被重建；任务行属于用户数据，必须活在我们自己的库里。
 * 桥接两边的正是 [RecognitionWorker.uniqueName] 这个唯一名。
 *
 * ## 并发 1
 *
 * 由 `PlantIdentifyApplication.workManagerConfiguration` 的单线程 Executor 保证，
 * 不在这里做 —— 那样任何一处忘走队列就会破坏约束。
 */
class RecognitionQueue(
    private val workManager: WorkManager,
    private val taskDao: RecognitionTaskDao,
    private val taskImageDao: RecognitionTaskImageDao,
    private val imageStore: ImageStore,
    private val repository: com.plantidentify.data.repository.PlantRepository,
    private val applicationScope: CoroutineScope,
) {

    /** 任务列表（投影行，含封面与张数），界面唯一的数据源 */
    fun observeTaskCards() = taskDao.observeTaskCards()

    fun observeCountByStatus(status: RecognitionTaskStatus) = taskDao.observeCountByStatus(status)

    /**
     * 从系统相册建一条识别任务。
     *
     * 每张图先**导入应用私有目录**（[ImageStore.importFromUri]）再落行 ——
     * Photo Picker 授予的 URI 读取权限是一次性的，任务可能几小时后才跑，
     * 到时候 URI 早就读不了了。导入失败则整体失败并清理已导入的文件：
     * 一个只有 2 张图的任务跑出来的结果，比 5 张图的任务少 3 个视角，
     * 识别质量悄悄下降却无人知晓 —— 宁可失败让用户重选。
     */
    suspend fun createTask(imageUris: List<Uri>): Result<Long> = runCatching {
        require(imageUris.isNotEmpty()) { "请先选择至少一张照片" }
        require(imageUris.size <= MAX_IMAGES) { "一次最多加入 $MAX_IMAGES 张照片" }

        val paths = mutableListOf<String>()
        try {
            for (uri in imageUris) {
                imageStore.importFromUri(uri).getOrThrow().let(paths::add)
            }
        } catch (error: Throwable) {
            imageStore.deleteAll(paths)
            throw IllegalStateException("照片导入失败，请重试", error)
        }

        val taskId = taskDao.insert(
            RecognitionTaskEntity(createdAt = System.currentTimeMillis()),
        )
        taskImageDao.insertAll(
            paths.mapIndexed { index, path ->
                RecognitionTaskImageEntity(
                    taskId = taskId,
                    imagePath = path,
                    role = ImageRole.UNKNOWN,
                    sortOrder = index,
                )
            },
        )
        enqueue(taskId)
        taskId
    }

    /**
     * 处理「后台自动挂靠」的用户裁决（方案 §6.2）。
     *
     * @param keepAsNew true = 这其实是新植物 → 拆成独立档案；
     *                  false = 确实是同一种 → 观察留在目标档案下
     */
    suspend fun resolveMerge(taskId: Long, keepAsNew: Boolean): Result<Unit> {
        val task = taskDao.getById(taskId)
            ?: return Result.failure(IllegalStateException("任务不存在"))
        val observationId = task.resultObservationId
            ?: return Result.failure(IllegalStateException("该任务还没有产出观察记录"))
        if (task.pendingMergePlantId == null) {
            return Result.failure(IllegalStateException("该任务没有待裁决的归并候选"))
        }

        val outcome = if (keepAsNew) {
            repository.splitTaskToNewPlant(observationId)
        } else {
            repository.confirmTaskMerge(
                observationId = observationId,
                plantId = task.pendingMergePlantId,
            )
        }
        return outcome.map {
            // 裁决完成：清掉待裁决三字段，列表上的提示条随之消失
            taskDao.setPendingMerge(taskId, plantId = null, level = null, reason = null)
        }
    }

    /**
     * 把一条 PENDING 任务提交给 WorkManager。
     *
     * `ExistingWorkPolicy.KEEP`：同 id 已有在途/待跑的 work 时不重复入队。
     * 重复入队的来源是真实的 —— 应用启动恢复（[recoverOnStartup]）与
     * 用户在列表页手动重试都可能触发，KEEP 让它们天然幂等。
     */
    fun enqueue(taskId: Long) {
        val request = OneTimeWorkRequestBuilder<RecognitionWorker>()
            // 只放 id，不放凭据 —— inputData 会被 WorkManager 明文落盘（§5.7）
            .setInputData(workDataOf(RecognitionWorker.KEY_TASK_ID to taskId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // 指数退避从 30 秒起：连续失败时给服务端留喘息，也给用户留出看失败原因的时间
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(RecognitionWorker.TAG)
            .addTag(RecognitionWorker.tagOf(taskId))
            .build()

        workManager.enqueueUniqueWork(
            RecognitionWorker.uniqueName(taskId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 手动重试（任务列表上的按钮）。
     *
     * 与 [enqueue] 的差别：用 [ExistingWorkPolicy.REPLACE] 顶掉旧的 work
     * （KEEP 会因为旧 work 已 ENQUEUED 而什么都不做），并把行状态拨回 QUEUED。
     * `retryCount` **不清零** —— 用户有权知道这条任务已经失败过几次。
     */
    suspend fun retryNow(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.status.isTerminal && task.status != RecognitionTaskStatus.FAILED) return
        taskDao.setStatus(taskId, RecognitionTaskStatus.QUEUED)
        val request = OneTimeWorkRequestBuilder<RecognitionWorker>()
            .setInputData(workDataOf(RecognitionWorker.KEY_TASK_ID to taskId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(RecognitionWorker.TAG)
            .addTag(RecognitionWorker.tagOf(taskId))
            .build()
        workManager.enqueueUniqueWork(
            RecognitionWorker.uniqueName(taskId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * 取消任务。
     *
     * 行状态立刻置 CANCELLED（列表马上反映），work 靠唯一名取消。
     * 若 Worker 已经开跑，cancel 只能「尽力而为」——
     * 但 `runTask` 的幂等检查会在它下一次写状态前看到 isTerminal 而收手，
     * 最坏情况是多跑一次网络请求，**不会**产出重复档案。
     */
    suspend fun cancel(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.status.isTerminal) return
        taskDao.markFinishedWithError(
            taskId,
            RecognitionTaskStatus.CANCELLED,
            System.currentTimeMillis(),
            message = null,
            retryCount = task.retryCount,
        )
        workManager.cancelUniqueWork(RecognitionWorker.uniqueName(taskId))
    }

    /**
     * 应用启动时的僵尸状态自愈（方案 §5.6）。
     *
     * WorkManager 自己会把未完成的 work 持久化、重启后继续；
     * 但**进程被杀在 doWork 中途**时，会留下「行状态 PROCESSING、
     * 而 work 已经不在了」的僵尸 —— 没有任何机制会再来推进它。
     *
     * 判定僵尸**必须问 WorkManager**（WorkInfo 是否已结束），
     * 只看行状态会把「正在跑」的正常任务误判成僵尸、被重复调度。
     *
     * 在 `Application.onCreate` 末尾用应用级作用域调起，不阻塞启动。
     */
    fun recoverOnStartup() {
        applicationScope.launch {
            for (task in taskDao.getInFlight()) {
                // 用 Flow 取 WorkInfo（WorkManager 2.9+ 自带，无需 play-services 的 await 扩展）
                val infos = workManager
                    .getWorkInfosForUniqueWorkFlow(RecognitionWorker.uniqueName(task.id))
                    .first()
                // ★ 只要有一个未结束（ENQUEUED/RUNNING），WorkManager 会自己续上，不动
                if (infos.any { !it.state.isFinished }) continue

                when (task.status) {
                    // 从没真正开跑（断网攒下的）→ 原样再入队
                    RecognitionTaskStatus.QUEUED -> enqueue(task.id)

                    // 跑到一半进程被杀 → 打回 PENDING 重新排队，并记一次重试
                    RecognitionTaskStatus.PROCESSING, RecognitionTaskStatus.ANALYZING -> {
                        taskDao.update(
                            task.copy(
                                status = RecognitionTaskStatus.PENDING,
                                startedAt = null,
                                retryCount = task.retryCount + 1,
                            ),
                        )
                        enqueue(task.id)
                    }

                    else -> Unit
                }
            }
        }
    }
}

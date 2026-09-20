package com.plantidentify.data.recognition

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.plantidentify.data.local.dao.RecognitionTaskDao
import com.plantidentify.data.local.entity.RecognitionTaskEntity
import com.plantidentify.data.local.entity.RecognitionTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
    private val applicationScope: CoroutineScope,
) {

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

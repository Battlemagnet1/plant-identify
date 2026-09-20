package com.plantidentify.data.recognition

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plantidentify.PlantIdentifyApplication

/**
 * 后台识别任务的 Worker。
 *
 * ## 它有多薄
 *
 * 所有逻辑都在 [RecognitionExecutor.runTask] —— 这个类只负责两件事：
 * 从 `inputData` 取出任务 id、从 Application 拿到执行体。
 * 逻辑放 Worker 里的问题是它没法被单测（CoroutineWorker 绑着 Android 运行时），
 * 而执行体是普通类。
 *
 * ## 安全边界（方案 §5.7）
 *
 * WorkManager 会把 `inputData` **明文**写进它自己的 workdb 数据库。
 * 所以这里**只放 taskId**，绝不放 API Key、Base URL 之类的凭据 ——
 * 凭据由执行体在运行时经 `aiSettingsStore.current()` 现取
 * （Keystore 解密、只存在于内存），跑完即丢。
 */
class RecognitionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0) {
            // 调度参数坏了 —— 不是应用数据的问题，重试没有意义
            return androidx.work.ListenableWorker.Result.failure()
        }
        val container = (applicationContext as PlantIdentifyApplication).container
        return container.recognitionExecutor.runTask(taskId, runAttemptCount)
    }

    companion object {
        const val KEY_TASK_ID = "taskId"

        /** 所有任务共用的标签：想一次看/取消全部时用它 */
        const val TAG = "recognition-task"

        /**
         * 每个任务一个唯一名。
         *
         * 用唯一名而不是链（方案 §5.5 的备选被否了）：
         * 链里一次 `retry()` 会卡住整条队列，网络抖动时所有任务一起停滞；
         * 唯一名 + 并发 1 的 Executor，效果相同且互不拖累。
         */
        fun uniqueName(taskId: Long) = "recognition-task-$taskId"

        fun tagOf(taskId: Long) = uniqueName(taskId)
    }
}

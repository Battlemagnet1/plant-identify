package com.plantidentify

import android.content.Context
import android.os.Build
import android.os.Process
import com.plantidentify.data.ai.AiFailure
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局未捕获异常的处理。
 *
 * ## 只记录，不掩盖
 *
 * 这里绝不做「捕获后继续跑」。进程若带着已经损坏的状态往下运行，
 * 用户看到的是「点了没反应」「数据不对」这类更难查的现象，而且日志里
 * 什么都不会留下 —— 那是比崩溃更糟的结局。所以本类的做法是
 * **留下现场，然后把异常原样交回给系统**：该崩还是崩，
 * 只是崩之前多一份可查的堆栈。
 *
 * ## 落点与份数
 *
 * `filesDir/crash/`，只保留最近 [MAX_FILES] 份。反复崩溃时不限份数
 * 会把用户存储写满 —— 那等于把一个 bug 升级成「应用再也存不下照片」。
 *
 * ## 脱敏
 *
 * 落盘前过一遍 [AiFailure.redact]。堆栈里原则上不会出现 API Key，
 * 但「原则上」不能当验收标准，而项目的红线是
 * 「API Key 不出现在**任何**日志中」，崩溃日志也是日志。
 */
object CrashLogger {

    private const val DIR = "crash"
    private const val MAX_FILES = 5

    /** 崩溃日志目录，将来「设置」页要做一键导出/清理时从这里取 */
    fun crashDir(context: Context): File = File(context.filesDir, DIR)

    /**
     * 装入处理器。
     *
     * 必须在 Application.onCreate 里调，且只调一次 ——
     * 重复调用只是在链上再套一层，每崩一次就多写一份同样的日志。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录本身失败也不能影响后续流程 —— 它只是辅助手段，
            // 绝不因为写日志出错而改变崩溃本身的行为
            runCatching { write(appContext, thread, throwable) }

            if (previous != null) {
                // 交回系统默认处理器：进程照常终止，系统照常提示「应用已停止」
                previous.uncaughtException(thread, throwable)
            } else {
                // 极端情况下没有前任。此时必须自己收尾 ——
                // 否则主线程死在半路、消息循环停摆，界面会僵在那里
                // 既不响应也不退出，用户只能强杀
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists() && !dir.mkdirs()) return

        // 先腾位置，再写新的 —— 顺序反过来的话，正好在写满时崩溃就永远留不下记录
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES - 1)
            ?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val raw = buildString {
            append("时间：").append(stamp).append('\n')
            append("版本：").append(BuildConfig.VERSION_NAME)
            append("（").append(if (BuildConfig.FULL_EDITION) "完整版" else "基础版").append("）\n")
            append("Android：").append(Build.VERSION.SDK_INT).append('\n')
            append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("线程：").append(thread.name).append("\n\n")
            append(throwable.stackTraceToString())
        }
        File(dir, "crash_$stamp.txt").writeText(AiFailure.redact(raw))
    }
}

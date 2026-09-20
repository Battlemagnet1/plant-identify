package com.plantidentify

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.ChatCompletionsClient
import com.plantidentify.data.ai.OpenAICompatibleTextProvider
import com.plantidentify.data.ai.OpenAICompatibleVisionProvider
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.backup.BackupManager
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.export.DataExporter
import com.plantidentify.data.export.ReportThumbnailer
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.local.Migrations
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.recognition.AnalysisRunner
import com.plantidentify.data.recognition.RecognitionExecutor
import com.plantidentify.data.recognition.RecognitionQueue
import com.plantidentify.data.location.LocationProvider
import com.plantidentify.data.location.LocationSettingsStore
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.data.storage.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.Executors

/**
 * 应用入口。
 *
 * Phase 1 采用轻量手写容器注入依赖，不引入 Hilt：
 * 当前依赖只有「数据库 + 一个仓库」，引入注解处理框架的收益远小于
 * 它带来的构建复杂度（尤其在 AGP 9 + KSP2 这套较新的工具链上）。
 * 若后续 Repository 数量增长到需要管理生命周期的作用域，再评估引入。
 */
class PlantIdentifyApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 全局未捕获异常：落一份堆栈到 filesDir/crash/，然后把异常交回系统。
        // **不吞异常** —— 详见 CrashLogger 的注释。放在容器之后，
        // 这样崩溃日志里能带上版本号与版本类型
        CrashLogger.install(this)

        // 僵尸任务自愈（方案 §5.6）。内部自己用应用级作用域跑，不阻塞启动；
        // WorkManager 尚未初始化也没关系 —— 首次 getInstance 触发的正是
        // 下面这个 workManagerConfiguration
        container.recognitionQueue.recoverOnStartup()
        // 终态超过一天的识别任务自动清掉（用户要求：完成任务不用手动清）
        container.recognitionQueue.purgeOldFinished()
    }

    /**
     * WorkManager 自定义配置：**单线程 Executor = 识别任务并发 1**。
     *
     * 同一时刻只跑一个识别任务。理由：
     *  1. 每个任务都是一次带图的网络请求，并发会把内存与带宽同时吃满；
     *  2. 逐个跑让「第 N 个任务失败」的定位简单得多；
     *  3. 服务端限流（429）在串行下几乎不会触发。
     *
     * ## 时序注意（方案 §5.6）
     *
     * `androidx.startup` 的 InitializationProvider 是 ContentProvider，
     * 它的 onCreate 跑在 Application.onCreate **之前** —— 也就是说
     * WorkManager 可能在 [container] 赋值之前就要读这个 getter。
     * 所以 getter 里**只允许**创建 Executor 这种无副作用的东西，
     * 绝不能碰 AppContainer（它的成员全是 by lazy，碰了就等于在
     * ContentProvider 阶段触发整条依赖链的初始化）。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(
                Executors.newSingleThreadExecutor { r -> Thread(r, "recognition-queue") },
            )
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()
}

/** 应用的依赖容器 —— 单例持有，生命周期与 Application 一致 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val database: PlantIdentifyDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            PlantIdentifyDatabase::class.java,
            PlantIdentifyDatabase.NAME,
        )
            // 刻意不调用 fallbackToDestructiveMigration()：
            // 用户档案是长期资产，升级时宁可构建失败暴露问题，也不能静默清空数据。
            .addMigrations(*Migrations.ALL)
            .build()
    }

    /** 原图存储：写入 filesDir，对外只给相对路径 */
    val imageStore: ImageStore by lazy { ImageStore(appContext) }

    /** AI 上传副本压缩器：派生数据写入 cacheDir，可按需重建 */
    val imageCompressor: ImageCompressor by lazy { ImageCompressor(appContext) }

    /** 「添加植物」流程的拍摄草稿（DataStore） */
    val captureDraftStore: CaptureDraftStore by lazy { CaptureDraftStore(appContext) }

    /** AI 服务配置（DataStore + Keystore 加密存放 API Key） */
    val aiSettingsStore: AiSettingsStore by lazy { AiSettingsStore(appContext) }

    /** 位置功能：用户的开关与「是否已询问过」（DataStore） */
    val locationSettingsStore: LocationSettingsStore by lazy { LocationSettingsStore(appContext) }

    /**
     * 取坐标与反向地理编码。
     *
     * 全程可失败且失败只返回 null —— 位置是可选功能，
     * 拒绝授权或解析不出地名都不能影响识别与档案（规格书第十八节）。
     */
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    /** 把私有目录里的照片导出到系统相册（API 26–28 需先申请存储权限） */
    val mediaSaver: MediaSaver by lazy { MediaSaver(appContext) }

    /** 报告缩略图（1024px / JPEG 75，派生数据放 cacheDir，可重建） */
    private val reportThumbnailer: ReportThumbnailer by lazy { ReportThumbnailer(appContext) }

    /** HTML 导出（规格书第二十一节，三级体积策略） */
    val dataExporter: DataExporter by lazy {
        DataExporter(
            context = appContext,
            imageStore = imageStore,
            thumbnailer = reportThumbnailer,
        )
    }

    /**
     * 数据备份与恢复（规格书第二十二节）。
     *
     * 需要数据库本体而不只是仓库 —— 恢复要整体替换三张表，
     * 并把三张表的清空 + 写入放进同一个事务里。
     */
    val backupManager: BackupManager by lazy {
        BackupManager(
            context = appContext,
            database = database,
            imageStore = imageStore,
        )
    }

    /**
     * 共享的 HTTP 客户端。
     *
     * 视觉与文字两条通道共用同一个实例：连接池、线程池、DNS 缓存都可复用。
     * 如果各自新建，用户连续识别多株植物时会出现线程数悄悄翻倍的情况。
     */
    private val httpClient: OkHttpClient by lazy { ChatCompletionsClient.defaultClient() }

    private val chatClient: ChatCompletionsClient by lazy { ChatCompletionsClient(httpClient) }

    /** 视觉识别通道 —— 全局单例，避免每次识别都重建客户端 */
    val visionProvider: VisionProvider by lazy { OpenAICompatibleVisionProvider(chatClient) }

    /**
     * 文字分析通道。
     *
     * 可失败，且失败不影响识别结果落库（规格书第三十节）。
     */
    val textProvider: TextProvider by lazy { OpenAICompatibleTextProvider(chatClient) }

    /**
     * 植物档案仓库 —— UI 层访问数据的唯一入口。
     *
     * 注入 [captureDraftStore] 是因为「保存识别结果」这个动作天然是跨存储介质的：
     * 三张表要写入 Room，而草稿在 DataStore 里、需要同时清掉。
     * 把清草稿留在 ViewModel 做会让两次写入之间存在不一致窗口
     * （库里有档案、草稿还在，用户再进来会看到重复的照片）。
     */
    val plantRepository: PlantRepository by lazy {
        PlantRepository(
            database = database,
            draftStore = captureDraftStore,
            // 删除档案时要显式删图片文件 —— Room 级联只管数据库行
            imageStore = imageStore,
        )
    }

    /**
     * 植物百科（文字分析）的执行体。
     *
     * 抽出来是为了让「识别页保存后异步跑」与「详情页重新生成」
     * 走同一条实现，Phase 2 的任务队列也能直接复用 ——
     * 不会出现「前台一套、后台一套」的分叉。
     */
    val analysisRunner: AnalysisRunner by lazy {
        AnalysisRunner(
            aiSettingsStore = aiSettingsStore,
            textProvider = textProvider,
            repository = plantRepository,
        )
    }

    /**
     * 识别流程的共享执行体（压缩 → 视觉 → 落库）。
     *
     * 「识别页立即识别」与「Phase 2 的后台任务」都调它。
     * 它不依赖任何 UI，所以 Worker 也能直接用 —— 这是整套任务队列的前提：
     * 如果后台另写一份，两份 prompt 与两份压缩参数迟早会分叉。
     */
    val recognitionExecutor: RecognitionExecutor by lazy {
        RecognitionExecutor(
            aiSettingsStore = aiSettingsStore,
            visionProvider = visionProvider,
            imageCompressor = imageCompressor,
            imageStore = imageStore,
            repository = plantRepository,
            locationSettingsStore = locationSettingsStore,
            database = database,
            analysisRunner = analysisRunner,
        )
    }

    /**
     * 识别任务队列 —— 任务行与 WorkManager 之间的唯一通道。
     *
     * 入队 / 重试 / 取消 / 启动自愈都从这走；界面状态一律读任务表。
     */
    val recognitionQueue: RecognitionQueue by lazy {
        RecognitionQueue(
            workManager = WorkManager.getInstance(appContext),
            database = database,
            imageStore = imageStore,
            repository = plantRepository,
            applicationScope = applicationScope,
        )
    }

    /**
     * 与进程同生命周期的协程作用域。
     *
     * 用于「必须执行完、不能因为界面被关闭而取消」的收尾工作。
     * 典型场景：用户点「放弃」后立刻返回，若删除原图的协程挂在
     * ViewModel 作用域上，会随导航条目一起被取消 —— 结果是留下一堆
     * 无人引用的孤儿图片，或者更糟的半删除状态。
     *
     * **植物百科也走这里**：保存成功后识别页会被 popUpTo 移除，
     * 挂 viewModelScope 会让分析在导航那一瞬间被静默取消。
     *
     * 触达文件系统与网络的写操作走这里，读操作与 UI 状态仍走各自的作用域。
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

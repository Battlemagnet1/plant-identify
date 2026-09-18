package com.plantidentify

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.ChatCompletionsClient
import com.plantidentify.data.ai.OpenAICompatibleTextProvider
import com.plantidentify.data.ai.OpenAICompatibleVisionProvider
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * 应用入口。
 *
 * Phase 1 采用轻量手写容器注入依赖，不引入 Hilt：
 * 当前依赖只有「数据库 + 一个仓库」，引入注解处理框架的收益远小于
 * 它带来的构建复杂度（尤其在 AGP 9 + KSP2 这套较新的工具链上）。
 * 若后续 Repository 数量增长到需要管理生命周期的作用域，再评估引入。
 */
class PlantIdentifyApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
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
     * 触达文件系统的写操作走这里，读操作与 UI 状态仍走各自的作用域。
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

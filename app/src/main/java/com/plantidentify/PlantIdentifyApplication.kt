package com.plantidentify

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.OpenAICompatibleVisionProvider
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val plantRepository: PlantRepository by lazy {
        PlantRepository(
            plantRecordDao = database.plantRecordDao(),
            observationDao = database.plantObservationDao(),
            imageDao = database.observationImageDao(),
        )
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
     * 视觉识别通道。
     *
     * 全局单例持有一个 OkHttpClient：连接池与线程池可复用，
     * 每次识别都新建客户端会让「连续识别多株植物」的场景凭空多出若干线程。
     */
    val visionProvider: VisionProvider by lazy { OpenAICompatibleVisionProvider() }

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

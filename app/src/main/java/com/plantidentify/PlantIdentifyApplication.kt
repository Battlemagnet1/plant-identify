package com.plantidentify

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.repository.PlantRepository

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
}

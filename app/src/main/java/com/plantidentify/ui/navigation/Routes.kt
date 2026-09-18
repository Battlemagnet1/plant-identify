package com.plantidentify.ui.navigation

/**
 * 全部路由（规格书第二十七节的页面结构）。
 *
 * 采用字符串路由而非类型安全路由，是为了让页面参数（plantId / observationId）
 * 在深链接与 HTML 导出回跳场景下更容易手写与调试。
 */
object Routes {

    const val HOME = "home"

    /** 添加植物：拍照 / 相册 / 多图管理（Phase 2） */
    const val ADD_PLANT = "add_plant"

    /** 识别进行中 / 识别结果（Phase 3） */
    const val RECOGNITION = "recognition"

    /** 搜索与筛选（Phase 5） */
    const val SEARCH = "search"

    /** 设置（Phase 3 起承载 AI 服务配置） */
    const val SETTINGS = "settings"

    private const val ARG_PLANT_ID = "plantId"
    private const val ARG_OBSERVATION_ID = "observationId"

    /** 植物详情 */
    const val PLANT_DETAIL = "plant_detail/{$ARG_PLANT_ID}"

    /** 单次观察详情 */
    const val OBSERVATION = "observation/{$ARG_OBSERVATION_ID}"

    fun plantDetail(plantId: Long): String = "plant_detail/$plantId"

    fun observation(observationId: Long): String = "observation/$observationId"

    const val KEY_PLANT_ID = ARG_PLANT_ID
    const val KEY_OBSERVATION_ID = ARG_OBSERVATION_ID
}

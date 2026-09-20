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

    /** 相机拍照页（Phase 2） */
    const val CAMERA = "camera"

    /**
     * 相机拍完后回传的临时文件路径。
     *
     * 通过上一个导航条目的 savedStateHandle 从相机页传回「添加植物」页，
     * 由后者导入应用私有目录 —— 相机页只负责拍，不直接写档案，职责单一。
     */
    const val KEY_CAPTURED_TEMP_PATH = "captured_temp_path"

    /** 识别进行中 / 识别结果（Phase 3） */
    const val RECOGNITION = "recognition"

    /** 搜索与筛选（Phase 5） */
    const val SEARCH = "search"

    /** 设置（Phase 3 起承载 AI 服务配置；Phase 6 起承载数据管理） */
    const val SETTINGS = "settings"

    /** 植物统计（Phase 6，规格书第二十节） */
    const val STATS = "stats"

    /** 数据管理：位置记录 / 导出 HTML / 备份与恢复（Phase 6，规格书第二十一、二十二节） */
    const val DATA_MANAGEMENT = "data_management"

    /**
     * 识别任务列表（Phase 8，完整版专属入口）。
     *
     * 按方案 §9.4：**路由本身不做版本判断** —— base 版没有入口所以不可达，
     * 业务逻辑与数据库两版完全一致（备份包可互相迁移）。
     */
    const val TASK_LIST = "tasks"

    private const val ARG_PLANT_ID = "plantId"
    private const val ARG_OBSERVATION_ID = "observationId"

    /** 植物详情 */
    const val PLANT_DETAIL = "plant_detail/{$ARG_PLANT_ID}"

    /** 编辑植物档案（Phase 5）：只开放人工可编辑字段 */
    const val PLANT_EDIT = "plant_edit/{$ARG_PLANT_ID}"

    /** 单次观察详情 */
    const val OBSERVATION = "observation/{$ARG_OBSERVATION_ID}"

    /** 某株植物的全部观察（Phase 5） */
    const val PLANT_OBSERVATIONS = "plant_observations/{$ARG_PLANT_ID}"

    fun plantDetail(plantId: Long): String = "plant_detail/$plantId"

    fun plantEdit(plantId: Long): String = "plant_edit/$plantId"

    fun plantObservations(plantId: Long): String = "plant_observations/$plantId"

    fun observation(observationId: Long): String = "observation/$observationId"

    const val KEY_PLANT_ID = ARG_PLANT_ID
    const val KEY_OBSERVATION_ID = ARG_OBSERVATION_ID
}

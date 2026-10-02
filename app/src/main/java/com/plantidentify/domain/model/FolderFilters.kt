package com.plantidentify.domain.model

import com.plantidentify.data.local.entity.FolderType

/**
 * 文件夹列表的筛选条件（v1.0.2 Phase 1）。
 *
 * 与 [PlantFilters] 同一套路：留空表示该条件不生效，由 Repository 一次性
 * 映射成 SQL 参数 —— 不让调用方拼 SQL，也就不会出现「漏拼或条件叠加错误」。
 */
data class FolderFilter(
    /** 类型筛选；null = 全部 */
    val type: FolderType? = null,

    /** 关键词：匹配文件夹名称与描述 */
    val keyword: String = "",
) {
    val isEmpty: Boolean
        get() = type == null && keyword.isBlank()

    /**
     * 生效中的筛选项数量，用于界面上提示「已筛选 N 项」。
     *
     * **关键词不计入** —— 与 [PlantFilters.activeCount] 同一口径：
     * 搜索是常态，把它算进去会让每次搜索都顶着「已筛选 1 项」。
     */
    val activeCount: Int
        get() = if (type == null) 0 else 1

    val onlyKeyword: Boolean
        get() = keyword.isNotBlank() && activeCount == 0
}

/**
 * 文件夹列表的排序方式。
 *
 * ⚠️ **枚举名会被直接当作 SQL 的排序键** —— 见
 * `FolderDao.observeFolderCards` 里的 `CASE WHEN :sort = 'UPDATED' ...`。
 * 改名必须同步改 SQL，否则排序会**静默失效**（不报错，只是顺序不对）。
 * `FolderFiltersTest` 里有用例把这一点钉住。
 */
enum class FolderSort {
    /** 最近更新（默认）—— 用户最关心「我最近动过哪个」 */
    UPDATED,

    /** 创建时间 */
    CREATED,

    /** 名称 */
    NAME,
    ;

    companion object {
        val DEFAULT = UPDATED
    }
}

/**
 * 文件夹内植物列表的排序方式。
 *
 * 同样：**枚举名即 SQL 排序键**（`FolderPlantDao.observeFolderPlantCards`）。
 */
enum class FolderPlantSort {
    /** 最近加入（默认）—— 刚加进来的在最上面，符合「整理」时的心智 */
    JOINED,

    /** 植物档案最近更新 */
    UPDATED,

    /** 植物名称 */
    NAME,
    ;

    companion object {
        val DEFAULT = JOINED
    }
}

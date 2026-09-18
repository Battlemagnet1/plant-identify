package com.plantidentify.domain.model

/**
 * 搜索与筛选条件（规格书第十九节）。
 *
 * 每个字段留空表示该条件不生效。用空值而不是让调用方拼 SQL，
 * 是因为筛选组合有十几种（名称 + 科 + 属 + 日期 + 地点），
 * 逐个拼条件必然出现漏拼或条件叠加错误。
 */
data class PlantFilters(
    /** 关键词：匹配中文名 / 拉丁学名 / 科 / 属 / 植物类型 / 备注 */
    val keyword: String = "",

    /** 科（精确匹配） */
    val family: String = "",

    /** 属（精确匹配） */
    val genus: String = "",

    /** 观察日期下界（毫秒时间戳，null 表示不限） */
    val fromDate: Long? = null,

    /** 观察日期上界 */
    val toDate: Long? = null,

    /** 观察地点（模糊匹配） */
    val place: String = "",
) {
    val isEmpty: Boolean
        get() = keyword.isBlank() && family.isBlank() && genus.isBlank() &&
            fromDate == null && toDate == null && place.isBlank()

    /** 生效中的筛选项数量，用于在界面上提示「已筛选 N 项」 */
    val activeCount: Int
        get() = listOf(
            family.isNotBlank(),
            genus.isNotBlank(),
            fromDate != null || toDate != null,
            place.isNotBlank(),
        ).count { it }

    val onlyKeyword: Boolean
        get() = keyword.isNotBlank() && activeCount == 0
}

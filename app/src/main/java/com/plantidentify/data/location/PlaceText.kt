package com.plantidentify.data.location

/**
 * 把「地点三件套」渲染成给人看的一行字。
 *
 * ## 为什么要有这么小一个函数
 *
 * 这条口径原来散在三处各写了一遍：
 *   - 观察记录页：只认 `locationName`，为空就整行不显示
 *   - HTML 导出：地名优先、缺了就退回经纬度
 *   - 添加植物页：自己拼了个 `"%.4f, %.4f"`
 *
 * 结果就是**同一份数据在不同页面表现不一致** —— 详情页说「未记录」、
 * 导出的报告里却有坐标。用户会以为数据坏了，其实只是三份实现各自为政。
 *
 * ## 口径
 *
 * 1. 有地名 → 显示地名（人看得懂，这是首选）
 * 2. 没地名但有坐标 → 退回经纬度。国内很多 ROM 的 `Geocoder` 直接返回 null，
 *    而坐标是完好的；这时显示 `31.2304, 121.4737` 远比「未记录」有用 ——
 *    用户复制到地图里就能找到那个地方
 * 3. 什么都没有 → null，由调用方决定说什么（「未记录」/ 整行不显示）
 *
 * 精度取 4 位小数（约 11 米），够定位到一株植物，又不会显得像在炫耀精度。
 */
fun placeText(locationName: String?, latitude: Double?, longitude: Double?): String? {
    locationName?.takeIf { it.isNotBlank() }?.let { return it }
    if (latitude == null || longitude == null) return null
    return "%.4f, %.4f".format(latitude, longitude)
}

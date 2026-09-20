package com.plantidentify.data.draft

import com.plantidentify.data.local.entity.ImageRole

/**
 * 草稿中的一张照片。
 *
 * 只记录**相对路径**（见 [com.plantidentify.data.storage.ImageStore] 的说明），
 * 绝对路径在需要时由 ImageStore 还原。
 */
data class DraftImage(
    val relativePath: String,
    val role: ImageRole = ImageRole.UNKNOWN,
) {
    /** 用于 Compose 列表的稳定 key，同时避免重排时错位 */
    val key: String get() = relativePath
}

/**
 * 添加植物流程的拍摄草稿。
 *
 * ## 为什么需要一个「草稿」概念
 *
 * 规格书第三十节的识别流程是：
 * ```
 * 添加 1–5 张图 → 用户确认 → 保存原图 → 生成 AI 压缩图 → 视觉识别
 *              → 用户确认 → 保存 PlantRecord / PlantObservation / 图片
 * ```
 * 也就是说：**原图在识别之前就已落盘**，但数据库里的档案记录要等识别结果
 * 经用户确认之后才创建。Phase 2 还没有识别能力，因此这段时间的中间状态
 * 需要一个地方存放 —— 这就是草稿。
 *
 * 草稿单独持久化（DataStore），不写进 Room 的三张表：
 * 它是**流程中的临时状态**，不是用户档案的一部分。Phase 3 接上识别后，
 * 草稿会在用户确认结果时转成真正的档案记录并被清空。
 */
data class CaptureDraft(
    val images: List<DraftImage> = emptyList(),

    /**
     * 本轮照片要写回的**已有观察** id（仅「对已有观察补图并重新识别」时非空）。
     *
     * 规格书第十四点五节把两种情况分得很清楚：
     * - 新的识别 → 新建 Observation（或归并到已有植物下新建 Observation）
     * - 对**当前观察**补图重新识别 → **更新**这条 Observation，不新建
     *
     * 用草稿携带这个 id，而不是走导航参数，理由是：它是「这批照片属于谁」的属性，
     * 与照片同生共死。走导航参数的话，一旦用户中途离开再回来（或进程被回收），
     * 「这批照片要写回哪里」就丢了，只能再问用户一遍。
     */
    val targetObservationId: Long? = null,

    /**
     * 拍摄地点的经纬度（规格书第十八节，Phase 6）。
     *
     * ## 为什么在「添加植物」时就取，而不是保存档案时再取
     *
     * 用户点进「添加植物」页时人就在植物跟前，这一刻的坐标才是这株植物的位置。
     * 等到保存档案时再取，中间隔着识别与文字分析（可能几十秒，也可能用户
     * 先走了两步），拿到的是「保存那一刻在哪」而不是「拍摄时在哪」。
     *
     * 另外这样也把等待藏起来了：定位是异步的（最长几秒），放在保存路径上会
     * 让「保存到档案」这个动作莫名变慢，而放在进入页面时，用户拍照的功夫
     * 早就取完了。
     *
     * 三个字段都可空：拒绝授权、定位失败、用户关掉开关时就是空的，
     * 此时档案照常保存，只是不记录地点。
     */
    val latitude: Double? = null,

    val longitude: Double? = null,

    /** 反向地理编码结果，可能为 null（国内 ROM 上失败很常见） */
    val locationName: String? = null,

    /**
     * 地点是否为**用户手动填写**。
     *
     * 手动值是用户明确给出的信息，自动定位不得静默覆盖它 ——
     * 「重新定位」是用户主动点的按钮，点了才允许覆盖（并重置本标记）。
     * 手动填写时坐标为 null：用户给的是地名，不是坐标，
     * 观察行照样保存（三个字段都可空），只是没有经纬度可记。
     */
    val locationManual: Boolean = false,
) {
    val count: Int get() = images.size

    val isEmpty: Boolean get() = images.isEmpty()

    val canAddMore: Boolean get() = count < MAX_IMAGES

    /** 还能再加几张 */
    val remainingSlots: Int get() = (MAX_IMAGES - count).coerceAtLeast(0)

    /** 本轮是否在给已有观察补图 */
    val isReanalysis: Boolean get() = targetObservationId != null

    companion object {
        /**
         * 默认允许 1–5 张（规格书第三节）。
         * 上限定为 5 是产品决策：多图联合识别的收益在 5 张之后快速衰减，
         * 而请求体积与费用线性增长。未来要扩展只需改这一个常量。
         */
        const val MAX_IMAGES = 5

        val EMPTY = CaptureDraft()
    }
}

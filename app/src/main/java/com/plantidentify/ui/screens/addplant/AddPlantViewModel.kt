package com.plantidentify.ui.screens.addplant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.draft.CaptureDraft
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.draft.DraftImage
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.location.LocationProvider
import com.plantidentify.data.location.LocationSettingsStore
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「添加植物」页的 ViewModel。
 *
 * 职责：管理草稿照片的增删改排、把用户选中的图片导入应用私有目录、
 * 以及把失败原因转成可理解的中文提示（规格书第二十四节：
 * 不给用户看 NullPointerException 这类开发者信息）。
 *
 * ## 为什么写操作走 [externalScope] 而不是 viewModelScope
 *
 * 本 ViewModel 挂在「添加植物」这个导航条目上，用户一返回它就被清理、
 * viewModelScope 随即取消。而以下动作**必须执行完**：
 * - 导入图片：中途取消会留下写了一半的文件
 * - 删除图片：中途取消会留下无人引用的孤儿文件
 * - 放弃草稿：先删文件再清草稿，顺序被打断会不一致
 *
 * 因此触达文件系统的写操作统一发到进程级作用域，读与 UI 状态仍留在
 * viewModelScope（[draft] 的订阅需要随界面存活）。
 */
class AddPlantViewModel(
    private val imageStore: ImageStore,
    private val draftStore: CaptureDraftStore,
    private val imageCompressor: ImageCompressor,
    private val locationSettingsStore: LocationSettingsStore,
    private val locationProvider: LocationProvider,
    private val externalScope: CoroutineScope,
) : ViewModel() {

    val draft: StateFlow<CaptureDraft> = draftStore.draft
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CaptureDraft.EMPTY,
        )

    /**
     * 上传体积预估。
     *
     * 规格书第三十节的流程是「保存原图 → 生成 AI 压缩图片」，
     * 因此压缩副本在图片一落盘后就可以生成。这里把它做成随草稿变化的派生状态，
     * 顺带让用户提前看到本次识别大概要传多少数据 —— 对应分析报告 R10
     * 「API 成本失控」这条风险，把成本可见化。
     *
     * 压缩结果落在 cacheDir 并带参数命名，未改动的图会被复用，
     * 因此重排 / 改标注不会重复压缩。
     */
    val uploadPlan: StateFlow<UploadPlan?> = draftStore.draft
        .map { draft -> buildUploadPlan(draft) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    /** 一次性提示消息，UI 消费后需调用 [consumeMessage] 清空 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 正在导入图片（复制 + 解码可能耗时） */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    // ---------------- 观察地点（规格书第十八节） ----------------

    /** 是否要弹「是否记录植物观察地点？」——只在该问的时候为 true */
    private val _askLocation = MutableStateFlow(false)
    val askLocation: StateFlow<Boolean> = _askLocation.asStateFlow()

    /**
     * 已取到的地点，供页面显示。取不到就是 null，UI 什么都不显示。
     *
     * 直接由草稿派生而不是另存一份状态：地点是草稿的一部分，
     * 两处各存一份迟早会不一致（比如用户放弃了草稿，标签却还挂着上次的地点）。
     * 只有草稿里已经有照片时才显示 —— 空草稿下挂个地名没有意义。
     */
    val locationSummary: StateFlow<String?> = draftStore.draft
        .map { draft ->
            if (draft.isEmpty) return@map null
            draft.locationName?.takeIf { it.isNotBlank() }
                ?: draft.latitude?.let { formatCoordinate(it, draft.longitude) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    init {
        // 首次进来问一次；已经同意过就直接取，不再打扰
        viewModelScope.launch {
            val choice = locationSettingsStore.current()
            when {
                !choice.asked -> _askLocation.value = true
                choice.enabled -> captureLocation()
                else -> Unit
            }
        }
    }

    /** 用户点了「允许」。系统权限申请由 UI 发起，授权后再调 [captureLocation] */
    fun onLocationAllowed() {
        _askLocation.value = false
        externalScope.launch { locationSettingsStore.setEnabled(true) }
    }

    /**
     * 用户点了「暂不允许」。
     *
     * 只记「问过了」，不动开关 —— 用户将来想开可以去设置页，
     * 而不是被反复弹窗逼着同意。
     */
    fun onLocationDenied() {
        _askLocation.value = false
        externalScope.launch { locationSettingsStore.markAsked() }
    }

    /**
     * 取一次坐标并写进草稿。
     *
     * 任何一步失败都只是「这次没有地点」，不影响照片与后续识别 ——
     * 位置是可选功能，不能因为它失败而让主流程停下来。
     */
    fun captureLocation() {
        if (!locationProvider.hasPermission()) return
        externalScope.launch {
            val coordinate = locationProvider.currentCoordinate() ?: return@launch
            // 地理编码可能失败（国内 ROM 上很常见），失败就不写地名，只留坐标
            val name = locationProvider.reverseGeocode(coordinate)
            draftStore.update { draft ->
                draft.copy(
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    locationName = name,
                )
            }
        }
    }

    /** 无法解析出地名时退化为经纬度显示（规格书：优先地名，但不强制） */
    private fun formatCoordinate(latitude: Double, longitude: Double?): String {
        val lng = longitude ?: return "%.4f".format(latitude)
        return "%.4f, %.4f".format(latitude, lng)
    }

    /** 从相册选择结果导入 */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        externalScope.launch {
            _importing.value = true
            try {
                val slots = draftStore.current().remainingSlots
                if (slots <= 0) {
                    _message.value = "最多添加 ${CaptureDraft.MAX_IMAGES} 张照片"
                    return@launch
                }

                val accepted = uris.take(slots)
                val added = mutableListOf<DraftImage>()
                var failedCount = 0

                accepted.forEach { uri ->
                    imageStore.importFromUri(uri)
                        .onSuccess { relativePath -> added += DraftImage(relativePath) }
                        .onFailure { failedCount++ }
                }

                if (added.isNotEmpty()) {
                    draftStore.update { it.copy(images = it.images + added) }
                }

                _message.value = buildFailureMessage(
                    failedCount = failedCount,
                    trimmedCount = uris.size - accepted.size,
                )
            } finally {
                _importing.value = false
            }
        }
    }

    /** 从相机拍摄结果导入（CameraX 写在 cacheDir 的临时文件） */
    fun importCapture(tempFile: File) {
        externalScope.launch {
            _importing.value = true
            try {
                if (!draft.value.canAddMore) {
                    tempFile.delete()
                    _message.value = "最多添加 ${CaptureDraft.MAX_IMAGES} 张照片"
                    return@launch
                }

                imageStore.importCapturedTemp(tempFile)
                    .onSuccess { relativePath ->
                        draftStore.update { it.copy(images = it.images + DraftImage(relativePath)) }
                    }
                    .onFailure { error ->
                        tempFile.delete()
                        _message.value = "照片保存失败：${error.message ?: "未知原因"}"
                    }
            } finally {
                _importing.value = false
            }
        }
    }

    /** 删除一张照片：草稿记录、原图、以及它对应的压缩副本一并清理 */
    fun remove(image: DraftImage) {
        externalScope.launch {
            draftStore.update { draft ->
                draft.copy(images = draft.images.filterNot { it.key == image.key })
            }
            val original = imageStore.resolve(image.relativePath)
            imageCompressor.evictCacheFor(original)
            imageStore.delete(image.relativePath)
        }
    }

    /** 调整顺序（图序会写入识别 prompt，因此顺序是有语义的） */
    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        externalScope.launch {
            draftStore.update { draft ->
                val list = draft.images.toMutableList()
                if (fromIndex !in list.indices || toIndex !in list.indices) return@update draft
                list.add(toIndex, list.removeAt(fromIndex))
                draft.copy(images = list)
            }
        }
    }

    fun moveEarlier(image: DraftImage) {
        val index = draft.value.images.indexOfFirst { it.key == image.key }
        if (index > 0) move(index, index - 1)
    }

    fun moveLater(image: DraftImage) {
        val index = draft.value.images.indexOfFirst { it.key == image.key }
        if (index in 0 until draft.value.count - 1) move(index, index + 1)
    }

    /** 标注拍摄部位（会写入识别 prompt，用于对冲多图退化风险） */
    fun setRole(image: DraftImage, role: ImageRole) {
        externalScope.launch {
            draftStore.update { draft ->
                draft.copy(
                    images = draft.images.map { item ->
                        if (item.key == image.key) item.copy(role = role) else item
                    },
                )
            }
        }
    }

    /** 放弃整个草稿：原图与对应的压缩副本一并删除，然后清空草稿 */
    fun discardAll() {
        externalScope.launch {
            val current = draftStore.current()
            current.images.forEach { image ->
                val original = imageStore.resolve(image.relativePath)
                imageCompressor.evictCacheFor(original)
            }
            imageStore.deleteAll(current.images.map { it.relativePath })
            draftStore.clear()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun buildFailureMessage(failedCount: Int, trimmedCount: Int): String? = when {
        failedCount > 0 && trimmedCount > 0 ->
            "有 $failedCount 张图片读取失败，另有 $trimmedCount 张因超出上限未添加"
        failedCount > 0 -> "有 $failedCount 张图片读取失败，请换一张试试"
        trimmedCount > 0 -> "已达 ${CaptureDraft.MAX_IMAGES} 张上限，只添加了前 $trimmedCount 张"
        else -> null
    }

    /** 逐张生成（或复用）上传副本并累计体积 */
    private suspend fun buildUploadPlan(draft: CaptureDraft): UploadPlan? {
        if (draft.isEmpty) return null
        var totalBytes = 0L
        var failedCount = 0
        draft.images.forEach { image ->
            imageCompressor.compressedFor(imageStore.resolve(image.relativePath))
                .onSuccess { compressed -> totalBytes += compressed.length() }
                .onFailure { failedCount++ }
        }
        return UploadPlan(
            imageCount = draft.count,
            totalBytes = totalBytes,
            failedCount = failedCount,
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            imageStore: ImageStore,
            draftStore: CaptureDraftStore,
            imageCompressor: ImageCompressor,
            locationSettingsStore: LocationSettingsStore,
            locationProvider: LocationProvider,
            externalScope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddPlantViewModel(
                    imageStore,
                    draftStore,
                    imageCompressor,
                    locationSettingsStore,
                    locationProvider,
                    externalScope,
                )
            }
        }
    }
}

/**
 * 本次识别的上传计划。
 *
 * [totalBytes] 是 base64 编码**之前**的 JPEG 体积。实际请求体约为它的 1.33 倍
 * （base64 膨胀），UI 上按此提示。
 */
data class UploadPlan(
    val imageCount: Int,
    val totalBytes: Long,
    val failedCount: Int,
) {
    val estimatedRequestBodyBytes: Long get() = (totalBytes * 4 / 3)

    val hasFailure: Boolean get() = failedCount > 0
}

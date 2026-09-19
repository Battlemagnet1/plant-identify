package com.plantidentify.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 经纬度。刻意不用 `android.location.Location` 往外传 —— 那个类带着速度、方位、
 *  精度等一堆本应用用不到也不需要持久化的字段。 */
data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * 取坐标 + 反向地理编码（规格书第十八节）。
 *
 * ## 全链路可失败，且失败一律返回 null
 *
 * 位置是**可选功能**。规格书明确要求：用户拒绝授权时应用仍必须正常工作；
 * 报告缺口 5 也指出反向地理编码在国内的可用性因 ROM 而异
 * （系统 `Geocoder` 由厂商实现，国内多是高德/百度，有的机型直接返回 null）。
 * 因此这里所有对外方法都不抛异常，拿不到就返回 null，由 UI 决定降级显示什么。
 *
 * ## 为什么不用 Google Play Services 的 FusedLocationProvider
 *
 * 它会引入 play-services-location 依赖，而这个项目从 Phase 3 起就坚持
 * 「只用 OkHttp，不引入额外的大块依赖」。为了一个可选功能拉进一整套
 * Google 服务框架，与那个取舍不一致。
 *
 * ## 分级降级
 *
 * 1. 有权限 → API 30+ 请求一次「当前位置」（带超时，室内可能等不到回调）
 * 2. 拿不到 → 退到各 provider 里最新的「最后已知位置」
 * 3. 再拿不到 → 返回 null，本次观察不记录坐标
 */
class LocationProvider(private val context: Context) {

    /** 定位权限是否已授予。用 COARSE 判断 —— 地名精度不需要 FINE */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 取一个坐标。拿不到返回 null，绝不抛异常。
     *
     * 调用点必须能接受 null —— 这是「拒绝授权后全流程仍可完成」的结构性保证。
     */
    @SuppressLint("MissingPermission")
    suspend fun currentCoordinate(): Coordinate? = runCatching {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        if (!anyProviderEnabled(manager)) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) { requestCurrent(manager) }
            if (fresh != null) return Coordinate(fresh.latitude, fresh.longitude)
        }

        // API < 30 没有 getCurrentLocation，只能拿最后已知位置。
        // 可能有点旧，但对「在哪片区域拍的」这个用途足够。
        lastKnown(manager)?.let { Coordinate(it.latitude, it.longitude) }
    }.getOrElse { error ->
        Log.w(TAG, "取坐标失败，本次不记录地点", error)
        null
    }

    /**
     * 反向地理编码：坐标 → 短地名。
     *
     * 失败返回 null（调用方降级为显示经纬度）。**结果会被缓存在观察记录里**，
     * 所以这里不做本地缓存 —— 一次观察只解析一次，没必要再叠一层。
     */
    suspend fun reverseGeocode(coordinate: Coordinate): String? = runCatching {
        if (!isGeocoderAvailable()) return null
        val geocoder = Geocoder(context, Locale.getDefault())

        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33 起必须用异步回调版本（同步版本已被移除）
            withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { awaitAddress(geocoder, coordinate) }
        } else {
            withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder
                        .getFromLocation(coordinate.latitude, coordinate.longitude, 1)
                        ?.firstOrNull()
                }
            }
        }

        address?.let { shorten(it) }?.takeIf { it.isNotBlank() }
    }.getOrElse { error ->
        // 国内 ROM 上 Geocoder 抛 IOException / IllegalArgumentException 都属常见，
        // 不是缺陷，只是这个能力不可用
        Log.w(TAG, "反向地理编码失败，降级为显示坐标", error)
        null
    }

    // ---------------- 内部 ----------------

    /**
     * 请求一次「当前位置」。
     *
     * `getCurrentLocation` 是 API 30 才有的 —— 调用方已经用
     * `Build.VERSION.SDK_INT >= R` 把住了入口，这里再标注一次契约：
     * 否则 lint 只看得到这个函数里的调用，会按 minSdk 26 报 NewApi 错误，
     * 而「上层判断过了」这件事它读不出来。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(
                    // 优先用网络定位：地名精度用不上 GPS，而 GPS 在室内要等很久
                    preferredProvider(manager),
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (error: Exception) {
                Log.w(TAG, "请求当前位置失败", error)
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** 各 provider 里最新的那个「最后已知位置」 */
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        PROVIDERS.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider)
                else null
            }.getOrNull()
        }.maxByOrNull { it.time }

    private fun anyProviderEnabled(manager: LocationManager): Boolean =
        PROVIDERS.any { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

    private fun preferredProvider(manager: LocationManager): String =
        when {
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                .getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                .getOrDefault(false) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun awaitAddress(
        geocoder: Geocoder,
        coordinate: Coordinate,
    ): Address? = suspendCancellableCoroutine { continuation ->
        try {
            geocoder.getFromLocation(
                coordinate.latitude,
                coordinate.longitude,
                1,
            ) { addresses ->
                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
            }
        } catch (error: Exception) {
            Log.w(TAG, "异步地理编码失败", error)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun isGeocoderAvailable(): Boolean =
        runCatching { Geocoder.isPresent() }.getOrDefault(false)

    /**
     * 从一串地址字段里拼一个**短**地名。
     *
     * 规格书要求显示「📍 某校园」而不是「中国XX省XX市XX区XX路123号」——
     * 后者在列表里会把卡片撑爆，也不是用户想看的粒度。
     * 顺序是按「离用户最近」排的：先景点/校园名，再街道，再市区。
     */
    private fun shorten(address: Address): String {
        val candidates = listOfNotNull(
            // featureName 常是校园/公园/大厦名，正合规格书里「某校园」的例子；
            // 但它有时是一串门牌号，那就没意义了
            address.featureName?.takeIf { name -> name.any { !it.isDigit() } },
            address.subLocality,
            address.locality,
            address.subAdminArea,
            address.adminArea,
        )
        return candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("")
            .take(MAX_NAME_LENGTH)
    }

    private companion object {
        const val TAG = "LocationProvider"

        /** 等「当前位置」的上限。室内/无信号时回调可能永远不来 */
        const val FRESH_TIMEOUT_MS = 6_000L

        /** 地理编码的上限。厂商实现可能一直不回调 */
        const val GEOCODE_TIMEOUT_MS = 6_000L

        /** 地名长度上限 —— 再长在列表和卡片里都放不下 */
        const val MAX_NAME_LENGTH = 24

        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}

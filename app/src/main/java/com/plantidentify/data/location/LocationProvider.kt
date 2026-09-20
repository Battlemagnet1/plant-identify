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

    /**
     * 是否有任何定位权限。
     *
     * 用 COARSE 判断即可 —— 在 Android 的权限模型里，授予 FINE 时
     * COARSE 必然同时被授予（FINE 是它的超集），所以这一个检查
     * 覆盖了「精确」与「大致」两种情况。
     * 要不要用精确能力另看 [hasFinePermission]。
     */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 是否拿到了**精确**定位权限。
     *
     * Android 12 起权限框提供「大致位置」与「精确位置」两个档，
     * 用户选了前者时只有 COARSE 被授予。这个区别决定了能不能用 GPS：
     * 用 COARSE 硬等 GPS 既拿不到更准的坐标（系统会降级），又白等一截时间。
     */
    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
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
     * 反向地理编码：坐标 → 地点名。
     *
     * 失败返回 null（调用方降级为显示经纬度）。**结果会被缓存在观察记录里**，
     * 所以这里不做本地缓存 —— 一次观察只解析一次，没必要再叠一层。
     *
     * ## 为什么要试两次区域
     *
     * 国内 ROM 的 Geocoder 由厂商实现（高德/百度等），个别机型只在
     * 区域为中文时才返回条目 —— 系统区域是 en-US 时就什么都拿不到，
     * 而那正是「用户明明有坐标、界面却只显示经纬度」的常见成因。
     * 先按系统区域试、再按中文区域试，两次都空才降级。
     */
    suspend fun reverseGeocode(coordinate: Coordinate): String? = runCatching {
        if (!isGeocoderAvailable()) return null

        for (locale in geocodeLocales()) {
            val name = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                fetchAddress(coordinate, locale)?.let { shorten(it) }
            }
            if (!name.isNullOrBlank()) return@runCatching name
        }
        null
    }.getOrElse { error ->
        // 国内 ROM 上 Geocoder 抛 IOException / IllegalArgumentException 都属常见，
        // 不是缺陷，只是这个能力不可用
        Log.w(TAG, "反向地理编码失败，降级为显示坐标", error)
        null
    }

    /** 取一次地址条目。API 33 起同步版本被移除，必须走回调版 */
    private suspend fun fetchAddress(coordinate: Coordinate, locale: Locale): Address? {
        val geocoder = Geocoder(context, locale)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            awaitAddress(geocoder, coordinate)
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder
                    .getFromLocation(coordinate.latitude, coordinate.longitude, GEOCODE_MAX_RESULTS)
                    ?.firstOrNull()
            }
        }
    }

    /**
     * 要尝试的区域，按优先级排列。
     *
     * 每次调用重新读 `Locale.getDefault()` —— 缓存在静态字段里的话，
     * 用户在系统里切换语言后这里仍会拿着旧值。
     */
    private fun geocodeLocales(): List<Locale> =
        listOf(Locale.getDefault(), Locale.CHINA).distinct()

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
                    // 有精确权限时优先 GPS —— 详见 preferredProvider 的说明
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

    /**
     * 请求当前位置时优先用哪个 provider。
     *
     * ## 为什么改成 GPS 优先
     *
     * 原来优先 NETWORK —— 理由是「地名精度用不上 GPS，而 GPS 在室内要等很久」。
     * 那个判断在**只要一个大致区域**时成立，但实际需求是「精确到地点名」：
     * 网络定位的误差常在几百米到一两公里，反查出来的地址自然只到区；
     * GPS 的几十米误差才可能落到「某路 / 某号 / 某小区」这一级。
     *
     * 代价是室内可能要等到超时（见 [FRESH_TIMEOUT_MS]）。这个代价可以接受：
     * 位置是在「添加植物」页异步取的，界面先给「定位中」再回填，不挡拍照；
     * 而且等到超时后会退回最后已知位置，不会一直空着。
     *
     * **只有拿到精确权限时才优先 GPS** —— 用户选的是「大致位置」的话，
     * 系统本来就不会把 GPS 的精确结果给出来，白等一截没有意义。
     */
    private fun preferredProvider(manager: LocationManager): String {
        fun enabled(provider: String) =
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

        val gps = LocationManager.GPS_PROVIDER
        val network = LocationManager.NETWORK_PROVIDER

        return when {
            hasFinePermission() && enabled(gps) -> gps
            enabled(network) -> network
            enabled(gps) -> gps
            else -> LocationManager.PASSIVE_PROVIDER
        }
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
     * 从一串地址字段里拼一个**够精确又够短**的地名。
     *
     * ## 两段式，而不是把各级拼起来
     *
     * 原实现是「featureName + subLocality + locality + ...」全拼再截断到 24 字。
     * 那有个致命问题：**截断会把最有信息量的那一级砍掉**。
     * 「中国浙江省杭州市西湖区北山街123号」截到 24 字还剩「中国浙江省杭州市西湖区北山街1」——
     * 看着挺长，其实最重要的门牌号已经被切了，而且前面那一长串省市名
     * 对用户毫无价值（他当然知道自己在哪个省）。
     *
     * 现在改成「**最细一级 + 上一级行政区**」：
     * 先按 [premises]/[featureName]/路+门牌 找出最具体的地点，
     * 前面挂一个区级行政区做定位。既精确，又不会长到撑爆卡片。
     *
     * ## 为什么用「最细一级」而不是依次降级
     *
     * `premises`（小区/大厦）比 `thoroughfare`（路）更有辨识度 ——
     * 「万科城市花园」比「某某路」更能说明人在哪。所以按辨识度排序取第一个有值的，
     * 而不是按行政层级从大到小拼。
     */
    private fun shorten(address: Address): String {
        // 最具体的那一级。按「辨识度」从高到低试
        val finest = listOfNotNull(
            address.premises?.trim()?.takeIf { it.isNotEmpty() },
            // featureName 常是校园/公园/大厦/景点名，正合规格书里「某校园」的例子；
            // 但它有时只是一串门牌号，那就不如后面的「路 + 门牌」有信息量
            address.featureName
                ?.trim()
                ?.takeIf { name -> name.isNotEmpty() && name.any { !it.isDigit() } },
            listOfNotNull(address.thoroughfare, address.subThoroughfare)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")
                .takeIf { it.isNotEmpty() },
        ).firstOrNull()

        // 上级行政区，只取一级 —— 取多了就把长度预算吃光
        val area = listOfNotNull(address.subLocality, address.locality, address.subAdminArea)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .firstOrNull()

        return when {
            finest != null && area != null -> "$area·$finest"
            finest != null -> finest
            area != null -> area
            else -> address.adminArea?.trim().orEmpty()
        }.take(MAX_NAME_LENGTH)
    }

    private companion object {
        const val TAG = "LocationProvider"

        /**
         * 等「当前位置」的上限。
         *
         * 从 6 秒放宽到 10 秒：改用 GPS 优先后，冷启动（尤其在室内靠
         * 卫星补星）常常超过 6 秒。位置是在「添加植物」页异步取的、
         * 不挡拍照，多等这几秒换一个能精确到路名的坐标是划算的。
         * 超时后仍会退回最后已知位置。
         */
        const val FRESH_TIMEOUT_MS = 10_000L

        /** 地理编码的上限。厂商实现可能一直不回调 */
        const val GEOCODE_TIMEOUT_MS = 6_000L

        /**
         * 一次取几条地址候选。
         *
         * 取 3 条而不是 1 条：系统的地理编码器在边界处常会给出
         * 几个不同粒度的结果，多拿两条能提高「至少有一条带门牌」的概率。
         * [shorten] 只用第一条，所以多取不会让地名变长。
         */
        const val GEOCODE_MAX_RESULTS = 3

        /** 地名长度上限 —— 再长在列表和卡片里都放不下 */
        const val MAX_NAME_LENGTH = 24

        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}

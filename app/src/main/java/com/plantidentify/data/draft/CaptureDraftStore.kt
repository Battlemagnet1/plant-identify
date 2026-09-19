package com.plantidentify.data.draft

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.plantidentify.data.local.entity.ImageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.draftDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "capture_draft")

/**
 * 拍摄草稿的持久化（DataStore + JSON）。
 *
 * 选择 DataStore 而非新增一张 Room 表的原因：
 * 草稿是**流程中的临时状态**，生命周期只有「添加植物」这一屏；为它建表意味着
 * 数据库要升版本、写迁移，而 Phase 3 接上识别后这张表又会变成死表。
 *
 * 选择手写 JSON（org.json）而非引入 kotlinx.serialization 的原因：
 * 结构极简（一个数组、两个字段），而引入序列化插件在 AGP 9 内置 Kotlin
 * 这套工具链上属于不必要的额外风险面。
 */
class CaptureDraftStore(private val context: Context) {

    private val jsonKey = stringPreferencesKey(KEY_JSON)

    /** 当前草稿。数据损坏时降级为空草稿，不抛给调用方 */
    val draft: Flow<CaptureDraft> = context.draftDataStore.data
        .map { prefs -> decode(prefs[jsonKey]) }
        .distinctUntilChanged()

    /** 读取一次当前值（用于非响应式场景，例如校验剩余可添加张数） */
    suspend fun current(): CaptureDraft = decode(context.draftDataStore.data.first()[jsonKey])

    /** 以原子方式更新草稿 */
    suspend fun update(transform: (CaptureDraft) -> CaptureDraft) {
        context.draftDataStore.edit { prefs ->
            val updated = transform(decode(prefs[jsonKey]))
            prefs[jsonKey] = encode(updated)
        }
    }

    suspend fun clear() {
        context.draftDataStore.edit { prefs -> prefs.remove(jsonKey) }
    }

    /**
     * 用一批已有照片重建草稿，并把它指向某个已有观察。
     *
     * 用于「对已有观察补图并重新识别」：用户进到这条观察的补图流程时，
     * 草稿先装入该观察现有的照片（规格书第十四点五节要求
     * 「原有照片 + 新增照片」一起参与重新识别），用户在添加页继续加图，
     * 识别完成后写回**同一条** Observation。
     */
    suspend fun seedForObservation(observationId: Long, images: List<DraftImage>) {
        context.draftDataStore.edit { prefs ->
            prefs[jsonKey] = encode(
                CaptureDraft(
                    images = images.take(CaptureDraft.MAX_IMAGES),
                    targetObservationId = observationId,
                ),
            )
        }
    }

    // ---------------- 序列化 ----------------

    private fun encode(draft: CaptureDraft): String {
        val array = JSONArray()
        draft.images.forEach { image ->
            array.put(
                JSONObject()
                    .put(FIELD_PATH, image.relativePath)
                    .put(FIELD_ROLE, image.role.name),
            )
        }
        return JSONObject()
            .put(FIELD_IMAGES, array)
            .apply {
                // 只在需要时写这个字段，让「普通新建」的草稿保持原来的形状
                draft.targetObservationId?.let { put(FIELD_TARGET_OBSERVATION, it) }
                // 位置三件套：拿不到就一个都不写（而不是写 null），
                // 这样「没有位置」的草稿与 Phase 6 之前的草稿形状完全一致
                if (draft.latitude != null && draft.longitude != null) {
                    put(FIELD_LATITUDE, draft.latitude)
                    put(FIELD_LONGITUDE, draft.longitude)
                }
                draft.locationName?.let { put(FIELD_LOCATION_NAME, it) }
            }
            .toString()
    }

    private fun decode(raw: String?): CaptureDraft {
        if (raw.isNullOrBlank()) return CaptureDraft.EMPTY
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray(FIELD_IMAGES) ?: JSONArray()
            val images = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val path = item.optString(FIELD_PATH).takeIf { it.isNotBlank() } ?: continue
                    add(
                        DraftImage(
                            relativePath = path,
                            role = parseRole(item.optString(FIELD_ROLE)),
                        ),
                    )
                }
            }
            CaptureDraft(
                images = images.take(CaptureDraft.MAX_IMAGES),
                targetObservationId = root.optLong(FIELD_TARGET_OBSERVATION)
                    .takeIf { root.has(FIELD_TARGET_OBSERVATION) && it > 0L },
                // 经纬度必须成对出现：只有一个的话宁可都不要，
                // 半个坐标既显示不出来也没法用来定位
                latitude = root.optDouble(FIELD_LATITUDE)
                    .takeIf { root.has(FIELD_LATITUDE) && !it.isNaN() },
                longitude = root.optDouble(FIELD_LONGITUDE)
                    .takeIf { root.has(FIELD_LONGITUDE) && !it.isNaN() },
                locationName = root.optString(FIELD_LOCATION_NAME).takeIf { it.isNotBlank() },
            )
        }.getOrElse { error ->
            // 草稿损坏（例如升级过程中断导致写了一半）不应让应用崩溃
            Log.w(TAG, "草稿数据解析失败，已重置为空草稿", error)
            CaptureDraft.EMPTY
        }
    }

    private fun parseRole(name: String): ImageRole =
        runCatching { ImageRole.valueOf(name) }.getOrDefault(ImageRole.UNKNOWN)

    private companion object {
        const val TAG = "CaptureDraftStore"
        const val KEY_JSON = "draft_json"
        const val FIELD_IMAGES = "images"
        const val FIELD_PATH = "path"
        const val FIELD_ROLE = "role"
        const val FIELD_TARGET_OBSERVATION = "targetObservationId"
        const val FIELD_LATITUDE = "latitude"
        const val FIELD_LONGITUDE = "longitude"
        const val FIELD_LOCATION_NAME = "locationName"
    }
}

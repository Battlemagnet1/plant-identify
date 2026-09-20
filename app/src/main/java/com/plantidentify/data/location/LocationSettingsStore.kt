package com.plantidentify.data.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.locationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "location_settings")

/** 用户对「是否记录观察地点」的选择 */
data class LocationChoice(
    /** 是否已经问过。没问过才弹首次询问对话框 */
    val asked: Boolean = false,
    /** 用户是否同意记录地点 */
    val enabled: Boolean = false,
    /**
     * 是否允许把地点**随识别请求发给 AI 服务**作为辅助线索。
     *
     * 默认 `false`，且与 [enabled] 是**两件事**：
     *  - `enabled` 管的是「要不要在本机记下地点」，地点不出设备
     *  - `shareWithAi` 管的是「要不要把这个地点发出去」
     *
     * 分开是刻意的。合在一起就等于「想记地点就必须同意上传」，
     * 而这两件事的隐私含义差得很远 —— 前者只是本地存个字符串，
     * 后者是把用户的行踪交给第三方服务。
     */
    val shareWithAi: Boolean = false,
)

/**
 * 位置功能的用户选择（规格书第十八节）。
 *
 * ## 为什么 asked 和 enabled 要分开存
 *
 * 「有没有问过」和「当前开没开」是两件事。合成一个三态枚举（未询问/已同意/已拒绝）
 * 看着更简洁，但用户拒绝之后想再打开就只能去设置页 —— 而如果他后来想开了，
 * 我们在「添加植物」页其实可以什么都不做，也可以在设置里改。
 *
 * 拆成两个布尔值后，行为是明确的：
 * - 首次进入添加植物页 → 弹询问框；无论选哪个都置 asked=true
 * - 之后不再弹框（重复弹权限框是最招人烦的一类交互）
 * - 设置页的开关直接改 enabled，与 asked 无关
 */
class LocationSettingsStore(private val context: Context) {

    val choice: Flow<LocationChoice> = context.locationDataStore.data.map { prefs ->
        LocationChoice(
            asked = prefs[KEY_ASKED] ?: false,
            enabled = prefs[KEY_ENABLED] ?: false,
            shareWithAi = prefs[KEY_SHARE_WITH_AI] ?: false,
        )
    }

    suspend fun current(): LocationChoice = choice.first()

    /** 设置开关。用户主动在设置页改动，等同于「已经问过了」 */
    suspend fun setEnabled(enabled: Boolean) {
        context.locationDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
            prefs[KEY_ASKED] = true
            // 关掉地点记录时，顺手把「允许上传」也关掉 ——
            // 否则会留下一个「不上传任何地点，但已授权上传」的矛盾状态，
            // 用户下次打开开关时会莫名其妙地把行踪发出去
            if (!enabled) prefs[KEY_SHARE_WITH_AI] = false
        }
    }

    /**
     * 设置「允许把地点发给 AI」。
     *
     * 只在 [LocationChoice.enabled] 为真时有意义 —— 没在记地点就没有地点可发。
     * 这里不做强制校验（避免把状态搞成不可达的角落），由界面只在开关打开时
     * 才显示这一项。
     */
    suspend fun setShareWithAi(share: Boolean) {
        context.locationDataStore.edit { prefs -> prefs[KEY_SHARE_WITH_AI] = share }
    }

    /** 只记「问过了」，不改开关状态（用户点「暂不允许」时用） */
    suspend fun markAsked() {
        context.locationDataStore.edit { prefs -> prefs[KEY_ASKED] = true }
    }

    private companion object {
        val KEY_ASKED = booleanPreferencesKey("location_prompt_asked")
        val KEY_ENABLED = booleanPreferencesKey("location_enabled")
        val KEY_SHARE_WITH_AI = booleanPreferencesKey("share_location_with_ai")
    }
}

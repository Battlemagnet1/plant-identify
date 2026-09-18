package com.plantidentify.data.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.aiConfigDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "ai_config")

/**
 * AI 服务配置的持久化。
 *
 * ## 密钥的处理方式（关键设计）
 *
 * 配置分成两部分存：
 * - **非敏感字段**（预设、Base URL、模型名）→ 明文 JSON，存 Preferences
 * - **API Key** → 用 [SecretCipher] 经 Android Keystore 加密后的密文，单独存一个 key
 *
 * 两者分开而不是把 Key 塞进 JSON 的原因：JSON 会被整体读写与调试打印，
 * 混在一起迟早会有人在排查问题时把整段配置打到日志里。
 * 分开之后，[AiEndpointConfig] 的内存明文与磁盘密文之间只在这一层转换。
 *
 * ## 日志红线（验收标准 ④）
 *
 * 「API Key 不出现在任何日志中」——因此本类**任何情况下都不打印 Key 或密文**。
 * 出错时只打印字段名，不打印值。
 */
class AiSettingsStore(
    context: Context,
    private val cipher: SecretCipher = SecretCipher(),
) {

    private val appContext = context.applicationContext

    private val jsonKey = stringPreferencesKey(KEY_JSON)
    private val visionSecretKey = stringPreferencesKey(KEY_VISION_SECRET)
    private val textSecretKey = stringPreferencesKey(KEY_TEXT_SECRET)

    /** 当前配置（API Key 已解密回填） */
    val config: Flow<AiConfig> = appContext.aiConfigDataStore.data
        .map { prefs -> decode(prefs) }
        .distinctUntilChanged()

    /** 读取一次当前值 */
    suspend fun current(): AiConfig = decode(appContext.aiConfigDataStore.data.first())

    /**
     * 保存整套配置。
     *
     * 视觉与文字两套配置的 Key 各自独立加密存放；文字配置为 null
     * （表示复用视觉配置）时会清掉文字侧的密文，避免留下无主的旧密钥。
     */
    suspend fun save(config: AiConfig): Result<Unit> {
        // 加密放在写事务之外：Keystore 操作可能失败，
        // 需要在动 DataStore 之前就拿到结果，保证「要么整体写成功，要么整体不写」
        val visionSecret = cipher.encrypt(config.vision.apiKey)
        if (config.vision.apiKey.isNotEmpty() && visionSecret == null) {
            return Result.failure(IllegalStateException(ENCRYPT_FAILED))
        }

        val textSecret = config.text?.let { text ->
            cipher.encrypt(text.apiKey).also { secret ->
                if (text.apiKey.isNotEmpty() && secret == null) {
                    return Result.failure(IllegalStateException(ENCRYPT_FAILED))
                }
            }
        }

        return runCatching {
            appContext.aiConfigDataStore.edit { prefs ->
                prefs[jsonKey] = encodeJson(config)

                if (visionSecret.isNullOrEmpty()) {
                    prefs.remove(visionSecretKey)
                } else {
                    prefs[visionSecretKey] = visionSecret
                }

                if (config.text == null || textSecret.isNullOrEmpty()) {
                    prefs.remove(textSecretKey)
                } else {
                    prefs[textSecretKey] = textSecret
                }
            }
            Unit
        }.onFailure { error ->
            // 只记异常类型，不记内容
            Log.w(TAG, "保存 AI 配置失败：${error.javaClass.simpleName}")
        }
    }

    /** 清空全部 AI 配置（设置页的「清除配置」） */
    suspend fun clear() {
        runCatching {
            appContext.aiConfigDataStore.edit { prefs -> prefs.clear() }
            cipher.resetKey()
        }.onFailure { error ->
            Log.w(TAG, "清空 AI 配置失败：${error.javaClass.simpleName}")
        }
    }

    /** 是否已有可用的 API Key（供 UI 显示「已保存」而不显示明文） */
    suspend fun hasApiKey(vision: Boolean = true): Boolean {
        val prefs = appContext.aiConfigDataStore.data.first()
        val secret = prefs[if (vision) visionSecretKey else textSecretKey]
        return !cipher.decrypt(secret).isNullOrEmpty()
    }

    // ---------------- 序列化 ----------------

    /**
     * 只序列化非敏感字段。
     *
     * 注意这里**刻意不含 apiKey** —— 密钥永远不进 JSON。
     */
    private fun encodeJson(config: AiConfig): String = JSONObject().apply {
        put(FIELD_VISION, encodeEndpoint(config.vision))
        put(FIELD_STRATEGY, config.promptStrategy.name)
        config.text?.let { put(FIELD_TEXT, encodeEndpoint(it)) }
    }.toString()

    private fun encodeEndpoint(endpoint: AiEndpointConfig): JSONObject = JSONObject()
        .put(FIELD_PRESET, endpoint.preset.name)
        .put(FIELD_BASE_URL, endpoint.baseUrl)
        .put(FIELD_MODEL, endpoint.model)

    private fun decode(prefs: Preferences): AiConfig {
        val raw = prefs[jsonKey]
        if (raw.isNullOrBlank()) {
            return AiConfig(
                vision = AiConfig.defaultVision(),
                text = null,
            )
        }

        return runCatching {
            val root = JSONObject(raw)

            val vision = decodeEndpoint(root.optJSONObject(FIELD_VISION))
                ?: AiConfig.defaultVision()
            val text = decodeEndpoint(root.optJSONObject(FIELD_TEXT))

            AiConfig(
                vision = vision.copy(apiKey = cipher.decrypt(prefs[visionSecretKey]).orEmpty()),
                text = text?.copy(apiKey = cipher.decrypt(prefs[textSecretKey]).orEmpty()),
                promptStrategy = PromptStrategy.fromName(
                    root.optString(FIELD_STRATEGY).takeIf { it.isNotBlank() },
                ),
            )
        }.getOrElse { error ->
            // 配置损坏不应导致启动崩溃，降级为默认配置
            Log.w(TAG, "AI 配置解析失败，已回退到默认配置：${error.javaClass.simpleName}")
            AiConfig(vision = AiConfig.defaultVision(), text = null)
        }
    }

    private fun decodeEndpoint(json: JSONObject?): AiEndpointConfig? {
        if (json == null) return null
        val preset = AiPreset.fromName(json.optString(FIELD_PRESET).takeIf { it.isNotBlank() })
        return AiEndpointConfig(
            preset = preset,
            baseUrl = json.optString(FIELD_BASE_URL),
            model = json.optString(FIELD_MODEL),
        )
    }

    private companion object {
        const val TAG = "AiSettingsStore"
        const val KEY_JSON = "ai_config_json"
        const val KEY_VISION_SECRET = "vision_api_key_cipher"
        const val KEY_TEXT_SECRET = "text_api_key_cipher"

        const val FIELD_VISION = "vision"
        const val FIELD_STRATEGY = "promptStrategy"
        const val FIELD_TEXT = "text"
        const val FIELD_PRESET = "preset"
        const val FIELD_BASE_URL = "baseUrl"
        const val FIELD_MODEL = "model"

        const val ENCRYPT_FAILED = "API Key 加密失败，配置未保存"
    }
}

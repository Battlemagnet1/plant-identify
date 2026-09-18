package com.plantidentify.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的本地加密（规格书第二十三节）。
 *
 * ## 为什么用 Android Keystore 而不是 EncryptedSharedPreferences
 *
 * `androidx.security:security-crypto` 提供的 `EncryptedSharedPreferences`
 * 已被官方标记弃用（其依赖的 Tink 与主库均不再维护），新项目不应采用。
 *
 * 本实现直接使用 Keystore：
 * - 密钥由系统安全硬件（若有 TEE/StrongBox）生成与保管，**任何进程都无法导出明文密钥**
 * - 应用只能请求它做加解密，密钥本身不进入应用内存
 * - 采用 AES-256-GCM（带认证的加密），密文被篡改会直接解密失败而不是返回垃圾数据
 *
 * 密文格式：`v1:<base64(iv || ciphertext)>`
 * 带版本前缀是为了将来换算法时能识别旧数据并妥善处理。
 *
 * ## 一个必须处理的失败场景
 *
 * Keystore 里的密钥可能消失：用户清除应用数据、系统重装、或者系统在
 * 密钥失效时主动销毁。此时解密会抛异常。**这不能导致崩溃** ——
 * 后果只是「已保存的 Key 读不出来了」，应当返回 null 让 UI 提示重新填写，
 * 而不是让应用在启动读取设置时闪退。
 */
class SecretCipher {

    /**
     * 加密。
     *
     * @return 失败时返回 null（例如设备 Keystore 不可用）。
     *         调用方应把 null 视为「未能保存」，明确告知用户，
     *         **绝不降级为明文存储**。
     */
    fun encrypt(plain: String): String? = runCatching {
        if (plain.isEmpty()) return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        // iv 长度对 GCM 固定为 12 字节，但仍按实际值拼接，不写死
        val payload = ByteArray(iv.size + cipherBytes.size).also { out ->
            iv.copyInto(out, 0)
            cipherBytes.copyInto(out, iv.size)
        }
        "$PREFIX${Base64.encodeToString(payload, Base64.NO_WRAP)}"
    }.getOrElse { error ->
        Log.w(TAG, "API Key 加密失败，将不会保存本次输入", error)
        null
    }

    /**
     * 解密。
     *
     * @return 无法解密时返回 null。可能的原因：密钥已被系统销毁、
     *         数据来自另一台设备、密文损坏。调用方应提示用户重新填写 Key。
     */
    fun decrypt(token: String?): String? {
        if (token.isNullOrBlank()) return null
        if (!token.startsWith(PREFIX)) {
            // 不是本格式的密文：可能是手工改过数据，或是未来版本写的
            Log.w(TAG, "API Key 密文格式无法识别，将忽略")
            return null
        }

        return runCatching {
            val payload = Base64.decode(token.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return null

            val iv = payload.copyOfRange(0, IV_LENGTH)
            val cipherBytes = payload.copyOfRange(IV_LENGTH, payload.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrElse { error ->
            Log.w(TAG, "API Key 解密失败，需要用户重新填写", error)
            null
        }
    }

    /** 删除 Keystore 中的密钥（用户清空全部配置时调用） */
    fun resetKey(): Unit = runCatching {
        keyStore().apply { if (containsAlias(ALIAS)) deleteEntry(ALIAS) }
        Unit
    }.getOrElse { error ->
        Log.w(TAG, "删除 Keystore 密钥失败", error)
    }

    // ---------------- 内部 ----------------

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** 取密钥；不存在则生成。生成的密钥**永不出密钥库** */
    private fun obtainKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 刻意不要求用户认证：本应用无需解锁即可在后台完成识别，
                // 且密钥本身已受系统隔离保护，不再叠加生物识别。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretCipher"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "plant_identify_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256

        /** GCM 推荐认证标签长度 */
        const val TAG_BITS = 128

        /** GCM 推荐 IV 长度 */
        const val IV_LENGTH = 12

        const val PREFIX = "v1:"
    }
}

package app.manman.teacher.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("manman_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "manman_session_key_v1"

    val deviceId: String
        get() = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("device_id", it).commit())
        }

    @Synchronized
    fun save(session: AuthSession) {
        val json = JSONObject()
            .put("accountId", session.accountId)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAtMillis", session.expiresAtMillis)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("session", stored).commit())
    }

    @Synchronized
    fun load(): AuthSession? {
        val stored = preferences.getString("session", null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 12) { "登录凭证无法读取。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return AuthSession(
            accountId = json.getString("accountId"),
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtMillis = json.getLong("expiresAtMillis"),
        )
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove("session").commit())
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}

package app.hermes.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.hermes.mobile.pairing.DeviceConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DeviceCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("hermes_connection", Context.MODE_PRIVATE)

    @OptIn(ExperimentalEncodingApi::class)
    fun save(connection: DeviceConnection) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(connection.deviceSecret.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("base_url", connection.baseUrl.toString())
            .putString("iv", Base64.encode(cipher.iv))
            .putString("secret", Base64.encode(encrypted))
            .apply()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun read(): DeviceConnection? {
        val baseUrl = preferences.getString("base_url", null)?.toHttpUrlOrNull() ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        val encrypted = preferences.getString("secret", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv)),
            )
            DeviceConnection(
                baseUrl,
                cipher.doFinal(Base64.decode(encrypted)).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "hermes-mobile-device-secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package app.hermes.mobile.debug

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.hermes.mobile.MainActivity
import app.hermes.mobile.pairing.PairingException
import app.hermes.mobile.pairing.PairingPayload
import app.hermes.mobile.pairing.PairingRepository
import app.hermes.mobile.pairing.PairingTransportValidator
import app.hermes.mobile.security.DeviceCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.Proxy

/** Debug APK entry point for pairing an AVD through the host loopback alias. */
class DebugPairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
        val payload = Json.encodeToString(PairingPayload(1, baseUrl, token))
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PairingRepository(
                        client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                            chain.proceed(chain.request()).also { response ->
                                Log.i(TAG, "${chain.request().url.encodedPath} -> ${response.code}")
                            }
                        }.build(),
                        json = Json,
                        transportValidator = localEmulatorTransport,
                        save = DeviceCredentialStore(this@DebugPairingActivity)::save,
                    ).complete(payload, Build.MODEL.ifBlank { "Android Emulator" }, replace)
                }
            }.onFailure { error ->
                Log.e(TAG, "Debug pairing failed", error)
            }
            startActivity(Intent(this@DebugPairingActivity, MainActivity::class.java))
            finish()
        }
    }

    companion object {
        private const val TAG = "HermesDebugPairing"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_REPLACE = "replace"
    }
}

private val localEmulatorTransport = PairingTransportValidator { url ->
    val allowed =
        !url.isHttps &&
            url.host == "10.0.2.2" &&
            url.port == 8788 &&
            url.encodedPath == "/"
    if (!allowed) throw PairingException("调试连接必须使用本机模拟器地址")
}

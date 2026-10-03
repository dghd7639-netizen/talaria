package app.hermes.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hermes.mobile.pairing.PairingConflictException
import app.hermes.mobile.pairing.PairingException
import app.hermes.mobile.pairing.DeviceConnection
import app.hermes.mobile.pairing.PairingRepository
import app.hermes.mobile.pairing.QrScannerScreen
import app.hermes.mobile.security.DeviceCredentialStore
import app.hermes.mobile.threads.ThreadExperience
import app.hermes.mobile.design.HermesTheme
import app.hermes.mobile.design.ThemeMode
import app.hermes.mobile.design.ThemePreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose owns every inset (status/nav bars, keyboard) so the keyboard is applied once.
        enableEdgeToEdge()
        setContent {
            val store = remember { DeviceCredentialStore(this) }
            val appearance = remember { ThemePreferenceStore(this) }
            var connection by remember { mutableStateOf(store.read()) }
            var themeMode by remember { mutableStateOf(appearance.read()) }
            HermesApp(
                AppState(isPaired = connection != null, lastThreadId = null),
                connection = connection,
                onPaired = { connection = store.read() },
                onAuthenticationExpired = {
                    runOnUiThread {
                        store.clear()
                        connection = null
                    }
                },
                themeMode = themeMode,
                onThemeMode = { themeMode = it; appearance.write(it) },
            )
        }
    }
}

@Composable
fun HermesApp(
    state: AppState,
    connection: DeviceConnection? = null,
    onPaired: () -> Unit = {},
    onAuthenticationExpired: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
) {
    HermesTheme(themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val destination = state.startDestination) {
                RootDestination.Pairing -> PairingScreen(onPaired)
                is RootDestination.Thread -> connection?.let {
                    ThreadExperience(it, themeMode, onThemeMode, onAuthenticationExpired)
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var scannerVisible by remember { mutableStateOf(false) }
    // Pairing payload held while the user decides whether to replace the Mac's paired device.
    var replaceCandidate by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scannerVisible = true
        else error = "需要相机权限才能扫描二维码"
    }

    fun completePairing(rawPayload: String, replace: Boolean = false) {
        loading = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PairingRepository(OkHttpClient(), Json) {
                        DeviceCredentialStore(context).save(it)
                    }.complete(rawPayload, Build.MODEL.ifBlank { "Android" }, replace)
                }
                onPaired()
            } catch (failure: PairingConflictException) {
                replaceCandidate = rawPayload
            } catch (failure: PairingException) {
                error = failure.message
            } finally {
                loading = false
            }
        }
    }

    replaceCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { replaceCandidate = null },
            title = { Text("替换已配对的设备？") },
            text = { Text("这台 Mac 已经配对了另一台设备。替换后，那台设备会断开，需要重新扫码才能使用。") },
            confirmButton = {
                Button(onClick = {
                    replaceCandidate = null
                    completePairing(candidate, replace = true)
                }) { Text("替换") }
            },
            dismissButton = {
                OutlinedButton(onClick = { replaceCandidate = null }) { Text("取消") }
            },
        )
    }

    if (scannerVisible) {
        QrScannerScreen(
            onResult = {
                payload = it
                scannerVisible = false
                completePairing(it)
            },
            onClose = { scannerVisible = false },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Talaria", style = MaterialTheme.typography.headlineMedium)
        Text("MOBILE REMOTE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text("连接你的桌面 Hermes", style = MaterialTheme.typography.titleLarge)
        Text("扫描二维码，或粘贴 Mac 上显示的配对内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对内容") },
            minLines = 4,
            enabled = !loading,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        OutlinedButton(
            enabled = !loading,
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    scannerVisible = true
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
        ) {
            Text("扫描二维码")
        }
        Button(
            enabled = payload.isNotBlank() && !loading,
            onClick = {
                completePairing(payload)
            },
        ) {
            if (loading) CircularProgressIndicator()
            else Text("连接")
        }
    }
}

package app.hermes.mobile.debug

import app.hermes.mobile.HermesMobileApp
import app.hermes.mobile.pairing.DeviceConnection
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

class DebugHermesMobileApp : HermesMobileApp() {
    override fun createHttpClient(connection: DeviceConnection): OkHttpClient {
        val localAvdBridge =
            connection.baseUrl.host == "10.0.2.2" && connection.baseUrl.port == 8788
        return if (localAvdBridge) {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        } else {
            super.createHttpClient(connection)
        }
    }
}

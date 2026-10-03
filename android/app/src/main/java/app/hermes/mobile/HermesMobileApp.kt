package app.hermes.mobile

import android.app.Application
import app.hermes.mobile.pairing.DeviceConnection
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

open class HermesMobileApp : Application() {
    open fun createHttpClient(connection: DeviceConnection): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
}

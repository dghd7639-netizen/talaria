package app.hermes.mobile.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeEventSocket

class ThreadViewModelFactory(
    private val api: BridgeApi,
    private val events: BridgeEventSocket,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ThreadViewModel(api, events) as T
}

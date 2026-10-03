package app.hermes.mobile

sealed interface RootDestination {
    data object Pairing : RootDestination
    data class Thread(val id: String?) : RootDestination
}

data class AppState(
    val isPaired: Boolean,
    val lastThreadId: String?,
) {
    val startDestination: RootDestination
        get() = if (isPaired) RootDestination.Thread(lastThreadId) else RootDestination.Pairing
}

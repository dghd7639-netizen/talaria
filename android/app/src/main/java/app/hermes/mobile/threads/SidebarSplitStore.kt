package app.hermes.mobile.threads

import android.content.Context

internal const val DEFAULT_SIDEBAR_RATIO = 0.6f

internal class SidebarSplitStore(context: Context) {
    private val preferences = context.getSharedPreferences("sidebar_layout", Context.MODE_PRIVATE)

    fun read(): Float = sanitizeSidebarRatio(
        preferences.getFloat("recent_ratio", DEFAULT_SIDEBAR_RATIO),
    )

    fun write(value: Float) {
        preferences.edit().putFloat("recent_ratio", sanitizeSidebarRatio(value)).apply()
    }
}

internal fun sanitizeSidebarRatio(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.2f, 0.8f) else DEFAULT_SIDEBAR_RATIO

internal fun ratioAfterDrag(current: Float, deltaPx: Float, availablePx: Float): Float =
    if (availablePx > 0f) sanitizeSidebarRatio(current + deltaPx / availablePx)
    else sanitizeSidebarRatio(current)

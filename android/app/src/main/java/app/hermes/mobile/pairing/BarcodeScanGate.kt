package app.hermes.mobile.pairing

class BarcodeScanGate {
    private var accepted = false

    @Synchronized
    fun accept(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (accepted || value.isEmpty()) return null
        accepted = true
        return value
    }
}

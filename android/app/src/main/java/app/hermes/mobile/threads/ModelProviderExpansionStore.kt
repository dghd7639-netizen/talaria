package app.hermes.mobile.threads

import android.content.Context

internal class ModelProviderExpansionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): Set<String>? = if (preferences.getBoolean(HAS_PREFERENCE, false)) {
        preferences.getStringSet(EXPANDED_PROVIDERS, emptySet()).orEmpty().toSet()
    } else {
        null
    }

    fun write(providers: Set<String>) {
        preferences.edit()
            .putBoolean(HAS_PREFERENCE, true)
            .putStringSet(EXPANDED_PROVIDERS, providers.toSet())
            .apply()
    }

    private companion object {
        const val PREFERENCES = "hermes_model_picker"
        const val HAS_PREFERENCE = "has_expansion_preference"
        const val EXPANDED_PROVIDERS = "expanded_providers"
    }
}

internal fun initialExpandedProviders(
    stored: Set<String>?,
    selectedProvider: String?,
): Set<String> = stored ?: selectedProvider?.takeIf(String::isNotBlank)?.let(::setOf).orEmpty()

internal fun toggleProvider(expanded: Set<String>, provider: String): Set<String> =
    if (provider in expanded) expanded - provider else expanded + provider

internal fun groupModelOptions(options: List<ModelOption>): Map<String, List<ModelOption>> =
    options.groupBy(ModelOption::provider)

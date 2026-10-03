package app.hermes.mobile.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelProviderExpansionStoreTest {
    @Test
    fun storedExpansionWinsIncludingAnExplicitlyEmptySet() {
        assertEquals(setOf("provider-a"), initialExpandedProviders(setOf("provider-a"), "provider-b"))
        assertEquals(emptySet<String>(), initialExpandedProviders(emptySet(), "provider-b"))
    }

    @Test
    fun selectedProviderExpandsBeforeTheFirstPreference() {
        assertEquals(setOf("provider-b"), initialExpandedProviders(null, "provider-b"))
        assertEquals(emptySet<String>(), initialExpandedProviders(null, null))
    }

    @Test
    fun togglingReturnsANewSet() {
        val original = setOf("provider-a")

        assertEquals(setOf("provider-a", "provider-b"), toggleProvider(original, "provider-b"))
        assertEquals(emptySet<String>(), toggleProvider(original, "provider-a"))
        assertEquals(setOf("provider-a"), original)
    }

    @Test
    fun groupingPreservesProviderAndModelOrder() {
        val grouped = groupModelOptions(listOf(
            ModelOption("a-1", "provider-a"),
            ModelOption("b-1", "provider-b"),
            ModelOption("a-2", "provider-a"),
        ))

        assertEquals(listOf("provider-a", "provider-b"), grouped.keys.toList())
        assertEquals(listOf("a-1", "a-2"), grouped.getValue("provider-a").map(ModelOption::model))
    }
}

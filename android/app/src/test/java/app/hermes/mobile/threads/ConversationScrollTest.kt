package app.hermes.mobile.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationScrollTest {
    @Test
    fun latestIndexIncludesTheTrailingConversationItem() {
        assertEquals(0, latestConversationItemIndex(0, false, false, false, false))
        assertEquals(3, latestConversationItemIndex(3, false, false, false, false))
        assertEquals(4, latestConversationItemIndex(3, true, false, false, false))
        assertEquals(4, latestConversationItemIndex(3, false, true, false, false))
        assertEquals(5, latestConversationItemIndex(3, true, false, true, false))
    }

    @Test
    fun scrollbarTrackMapsToStableItemTargets() {
        assertEquals(0, conversationTargetIndex(0f, 10, 3))
        assertEquals(4, conversationTargetIndex(0.5f, 10, 3))
        assertEquals(9, conversationTargetIndex(1f, 10, 3))
    }

    @Test
    fun autoScrollRunsOnceAfterSelectedThreadFinishesLoading() {
        assertEquals(true, shouldAutoScrollToLatest(null, "thread-1", false, 3))
        assertEquals(false, shouldAutoScrollToLatest("thread-1", "thread-1", false, 3))
        assertEquals(false, shouldAutoScrollToLatest(null, "thread-1", true, 3))
        assertEquals(false, shouldAutoScrollToLatest(null, "thread-1", false, 0))
    }
}

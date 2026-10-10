package app.rev.core.ui.state

import app.rev.domain.model.Page
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateHelpersTest {

    @Test
    fun `empty cache is not reported as an empty library while refreshing or after failure`() = runTest {
        val cache = MutableStateFlow(emptyList<String>())
        val gate = CompletableDeferred<Result<Unit, LibraryError>>()
        val refresher = Refresher(backgroundScope) { gate.await() }
        val state = backgroundScope.cacheBacked(cache, refresher) { it.isEmpty() }
        backgroundScope.launch { state.collect {} }

        refresher.refresh()
        runCurrent()
        assertNull(state.value.content)
        assertTrue(state.value.isRefreshing)

        gate.complete(Result.Err(LibraryError.Unreachable))
        runCurrent()
        assertNull(state.value.content)
        assertEquals(LibraryError.Unreachable, state.value.error)
    }

    @Test
    fun `cached content is shown alongside a refresh error`() = runTest {
        val cache = MutableStateFlow(listOf("Lateralus"))
        val refresher = Refresher(backgroundScope) { Result.Err(LibraryError.Unreachable) }
        val state = backgroundScope.cacheBacked(cache, refresher) { it.isEmpty() }
        backgroundScope.launch { state.collect {} }
        refresher.refresh()
        runCurrent()
        assertEquals(listOf("Lateralus"), state.value.content)
        assertEquals(LibraryError.Unreachable, state.value.error)
    }

    @Test
    fun `an empty library after a successful refresh is reported as empty`() = runTest {
        val refresher = Refresher(backgroundScope) { Result.Ok(Unit) }
        val state = backgroundScope.cacheBacked(MutableStateFlow(emptyList<String>()), refresher) { it.isEmpty() }
        backgroundScope.launch { state.collect {} }
        refresher.refresh()
        runCurrent()
        assertEquals(emptyList<String>(), state.value.content)
    }

    @Test
    fun `paged list appends pages until the end and recovers from errors`() = runTest {
        var fail = false
        val calls = mutableListOf<Int>()
        val paged = PagedList(backgroundScope, pageSize = 2) { offset, size ->
            calls += offset
            if (fail) {
                Result.Err(LibraryError.Unreachable)
            } else {
                val items = (offset until minOf(offset + size, 3)).toList()
                Result.Ok(Page(items, offset, hasMore = offset + size < 3))
            }
        }
        paged.loadMore()
        runCurrent()
        assertEquals(listOf(0, 1), paged.state.value.items)

        fail = true
        paged.loadMore()
        runCurrent()
        assertEquals(LibraryError.Unreachable, paged.state.value.error)

        fail = false
        paged.loadMore()
        runCurrent()
        assertEquals(listOf(0, 1, 2), paged.state.value.items)
        assertTrue(paged.state.value.endReached)

        paged.loadMore()
        runCurrent()
        assertEquals(listOf(0, 2, 2), calls)
        assertFalse(paged.state.value.isLoading)
    }
}

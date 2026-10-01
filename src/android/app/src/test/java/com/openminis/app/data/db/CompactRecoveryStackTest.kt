package com.openminis.app.data.db

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Invoke production default DAO methods; the fake implements only primitive storage calls. */
class CompactRecoveryStackTest {
    private class Store {
        val markers = linkedMapOf<String, CompactMarkerEntity>()
        val recovery = linkedMapOf<String, CompactRecoveryEntity>()
        var anchorExists = true
        val dao: ChatDao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { proxy, method, args ->
            when (method.name) {
                "latestCompactMarker" -> markers.values.filter { it.sessionId == args!![0] }.maxByOrNull { it.createdAt }
                "latestCompactRecovery" -> recovery.values.filter { it.sessionId == args!![0] }.maxByOrNull { it.sequence }
                "insertCompactMarker" -> { val marker = args!![0] as CompactMarkerEntity; check(marker.id !in markers); markers[marker.id] = marker; Unit }
                "insertCompactRecovery" -> { val entry = args!![0] as CompactRecoveryEntity; recovery[entry.id] = entry; Unit }
                "deleteCompactMarker" -> if (markers.remove(args!![0]) != null) 1 else 0
                "deleteCompactRecovery" -> { recovery.remove(args!![0]); Unit }
                "deleteCompactRecoveries" -> { recovery.entries.removeAll { it.value.sessionId == args!![0] }; Unit }
                "compactAnchorExists" -> anchorExists
                else -> {
                    val defaults = Class.forName("com.openminis.app.data.db.ChatDao\u0024DefaultImpls")
                    val implementation = defaults.getMethod(method.name, ChatDao::class.java, *method.parameterTypes)
                    try { implementation.invoke(null, proxy, *(args ?: emptyArray())) }
                    catch (e: InvocationTargetException) { throw e.targetException }
                }
            }
        } as ChatDao
    }

    private fun marker(id: String, time: Long, session: String = "session") = CompactMarkerEntity(
        id, session, "summary $id", Int.MAX_VALUE, 2, time,
        lastCompactedMessageId = "anchor-$id", version = 2,
    )

    @Test fun consecutiveUndoAndRedoRestoreTheSameMarkersInOrder() = runTest {
        val store = Store()
        val first = marker("first", 1)
        val second = marker("second", 2)
        store.dao.insertCompactMarker(first)
        store.dao.insertCompactMarker(second)
        assertEquals(second, store.dao.undoCompact("session"))
        assertEquals(first, store.dao.latestCompactMarker("session"))
        assertEquals(first, store.dao.undoCompact("session"))
        assertNull(store.dao.latestCompactMarker("session"))
        assertEquals(first, store.dao.redoCompact("session"))
        assertEquals(second, store.dao.redoCompact("session"))
        assertEquals(second, store.dao.latestCompactMarker("session"))
        assertNull(store.dao.latestCompactRecovery("session"))
    }

    @Test fun newCompactionClearsOnlyItsOwnRedoBranch() = runTest {
        val store = Store()
        store.dao.insertCompactMarker(marker("a", 1))
        store.dao.insertCompactMarker(marker("b", 2, "other"))
        store.dao.undoCompact("session")
        store.dao.undoCompact("other")
        store.dao.commitCompact(marker("new", 3))
        assertNull(store.dao.latestCompactRecovery("session"))
        assertNotNull(store.dao.latestCompactRecovery("other"))
    }

    @Test fun deletedAnchorDoesNotConsumeRecoveryOrRestoreAStaleSummary() = runTest {
        val store = Store()
        store.dao.insertCompactMarker(marker("first", 1))
        store.dao.undoCompact("session")
        store.anchorExists = false
        try { store.dao.redoCompact("session"); fail("Removed anchor must reject restoration") }
        catch (_: IllegalArgumentException) { }
        assertNull(store.dao.latestCompactMarker("session"))
        assertNotNull(store.dao.latestCompactRecovery("session"))
    }

    @Test fun missingUndoAndRedoLeaveOtherSessionsUntouched() = runTest {
        val store = Store()
        val original = marker("other", 1, "other")
        store.dao.insertCompactMarker(original)
        assertNull(store.dao.undoCompact("missing"))
        assertNull(store.dao.redoCompact("missing"))
        assertEquals(original, store.dao.latestCompactMarker("other"))
    }
}

package com.openminis.app.ui.chat

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ShellOutputPreviewTest {
    @Test fun chunkMarkersAtEveryBoundary() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            for (terminator in listOf("\u0007", "\u001b\\")) {
                val marker = "\u001b]1337;MinisOpenURL=https://example.test/path" + terminator
                for (split in 0..marker.length) {
                    val urls = mutableListOf<String>()
                    val p = ShellOutputPreview(this, dispatcher, offerUrl = { urls.add(it) }) {}
                    p.appendChunk("before")
                    p.appendChunk(marker.take(split))
                    assertEquals("before", p.snapshot())
                    p.appendChunk(marker.drop(split))
                    assertEquals(listOf("https://example.test/path"), urls)
                    p.appendChunk("after\rprogress")
                    assertEquals("beforeafter\rprogress", p.snapshot())
                    p.flush()
                    p.stop()
                }
                val urls = mutableListOf<String>()
                val p = ShellOutputPreview(this, dispatcher, offerUrl = { urls.add(it) }) {}
                ("A" + marker + marker + "B").forEach { p.appendChunk(it.toString()) }
                assertEquals("AB", p.snapshot())
                assertEquals(2, urls.size)
                p.stop()
            }
        } finally { dispatcher.close() }
    }

    @Test fun incompleteMarkerFlushAndChunkBounds() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val p = ShellOutputPreview(this, dispatcher) {}
            p.appendChunk("printf tail")
            assertEquals("printf tail", p.snapshot())
            p.appendChunk("\u001b]1337;MinisOpenURL=unfinished")
            assertEquals("printf tail", p.snapshot())
            p.flush()
            assertEquals("printf tail\u001b]1337;MinisOpenURL=unfinished", p.snapshot())
            p.appendChunk("x".repeat(1_000_000) + "TAIL")
            assertEquals(16 * 1024, p.snapshot().length)
            assertTrue(p.snapshot().endsWith("TAIL"))
            p.appendChunk((0..100).joinToString("\n"))
            assertEquals((51..100).joinToString("\n"), p.snapshot())
            p.stop()
            p.appendChunk("late")
            p.flush()
            assertTrue(p.snapshot().endsWith("100"))
        } finally { dispatcher.close() }
    }

    @Test fun boundedBulkAndLongLines() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val p = ShellOutputPreview(this, dispatcher) {}
            repeat(100_000) { p.onLine("line-$it") }
            assertEquals((99_950..99_999).joinToString("\n") { "line-$it" }, p.snapshot())
            p.onLine("x".repeat(1_000_000) + "TAIL")
            assertEquals(16 * 1024, p.snapshot().length)
            assertTrue(p.snapshot().endsWith("TAIL"))
            repeat(100) { p.onLine("") }
            assertEquals(49, p.snapshot().length)
            p.stop()
        } finally { dispatcher.close() }
    }

    @Test fun firstAndTailAndNoResurrection() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val first = CompletableDeferred<String>()
            val tail = CompletableDeferred<Unit>()
            val outputs = mutableListOf<String>()
            val p = ShellOutputPreview(this, dispatcher) {
                outputs.add(it)
                first.complete(it)
                if (it.endsWith("tail")) tail.complete(Unit)
            }
            p.appendChunk("first")
            assertEquals("first", withTimeout(2000) { first.await() })
            repeat(10_000) { p.appendChunk("\n$it") }
            p.appendChunk("\ntail")
            withTimeout(2000) { tail.await() }
            p.stop()
            val count = outputs.size
            p.appendChunk("late")
            delay(120)
            assertEquals(count, outputs.size)
            assertTrue(outputs.size < 20)
        } finally { dispatcher.close() }
    }

    @Test fun blockedUiDoesNotBlockReaderOrOverwriteFinal() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            val blocker = launch(dispatcher) { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            var result = ""
            val p = ShellOutputPreview(this, dispatcher) { result = it }
            withTimeout(2000) { withContext(Dispatchers.Default) { repeat(100_000) { p.appendChunk("$it\n") } } }
            val stopping = async { p.stop() }
            yield()
            release.countDown()
            stopping.await()
            blocker.join()
            result = "complete final result"
            p.appendChunk("late")
            delay(100)
            assertEquals("complete final result", result)
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun concurrentToolsPublishOnOneUiThread() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }.asCoroutineDispatcher()
        try {
            val uiThread = withContext(dispatcher) { Thread.currentThread() }
            val contents = mutableMapOf<Int, String>()
            val tails = List(8) { CompletableDeferred<Unit>() }
            val previews = List(8) { id ->
                ShellOutputPreview(this, dispatcher) {
                    assertSame(uiThread, Thread.currentThread())
                    contents[id] = it
                    if (it.endsWith("tail-$id")) tails[id].complete(Unit)
                }
            }
            previews.mapIndexed { id, p -> async(Dispatchers.Default) {
                repeat(10_000) { p.appendChunk("$id:$it\n") }
                p.appendChunk("tail-$id")
            } }.awaitAll()
            withTimeout(3000) { tails.forEach { it.await() } }
            previews.forEach { it.stop() }
            assertEquals(8, contents.size)
            contents.forEach { (id, text) -> assertTrue(text.endsWith("tail-$id")) }
        } finally { dispatcher.close() }
    }
    @Test fun cancelledParentBeforeFirstUpdaterCannotPublish() = runBlocking {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val parent = Job()
        val outputs = mutableListOf<String>()
        val p = ShellOutputPreview(CoroutineScope(parent), dispatcher) { outputs.add(it) }
        p.appendChunk("stale running")
        parent.cancel()
        while (true) (queued.poll() ?: break).run()
        p.stop()
        assertEquals(emptyList<String>(), outputs)
    }

    @Test fun cancelledParentDuringUpdaterDelayCannotPublishAgain() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val parent = Job()
            val first = CompletableDeferred<Unit>()
            val outputs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val p = ShellOutputPreview(CoroutineScope(parent), dispatcher, intervalMillis = 200) {
                outputs.add(it); first.complete(Unit)
            }
            p.appendChunk("first")
            withTimeout(2000) { first.await() }
            parent.cancelAndJoin()
            p.appendChunk("late")
            p.stop()
            assertEquals(listOf("first"), outputs.toList())
        } finally { dispatcher.close() }
    }

    @Test fun publishPolicyRejectsEveryStaleTarget() {
        for (bits in 0..15) {
            assertEquals(bits == 15, ShellPreviewPublishPolicy.mayPublish(
                bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0,
            ))
        }
    }

    @Test fun concurrentFinishesPreserveAllFinalStatusesAndFullOutput() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        data class Block(val content: String, val running: Boolean)
        try {
            val blocks = MutableList(8) { Block("", true) }
            val finals = List(8) { id -> "final-$id\n" + "x".repeat(32_000) }
            val previews = List(8) { id -> ShellOutputPreview(this, dispatcher) { text ->
                val b = blocks[id]
                if (ShellPreviewPublishPolicy.mayPublish(true, true, true, b.running))
                    blocks[id] = b.copy(content = text)
            } }
            previews.mapIndexed { id, p -> async(Dispatchers.Default) {
                repeat(1000) { p.appendChunk("$id:$it\n") }
                p.stop()
                withContext(dispatcher) { blocks[id] = Block(finals[id], false) }
                p.appendChunk("late")
            } }.awaitAll()
            withContext(dispatcher) {
                assertEquals(finals.map { Block(it, false) }, blocks.toList())
            }
        } finally { dispatcher.close() }
    }

    @Test fun previewBypassesLongTextThrottleWithoutChangingTokenFlushes() {
        for (throttle in listOf(200L, 300L, 500L, 1000L, 1500L, 2000L)) {
            assertFalse(ShellPreviewPublishPolicy.shouldFlush(false, 50, throttle, false))
            assertTrue(ShellPreviewPublishPolicy.shouldFlush(false, 50, throttle, false, true))
            assertTrue(ShellPreviewPublishPolicy.shouldFlush(false, throttle, throttle, false))
            assertTrue(ShellPreviewPublishPolicy.shouldFlush(true, 0, throttle, false))
            assertTrue(ShellPreviewPublishPolicy.shouldFlush(false, 0, throttle, true))
        }
    }

}

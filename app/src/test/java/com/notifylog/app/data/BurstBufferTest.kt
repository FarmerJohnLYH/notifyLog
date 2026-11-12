package com.notifylog.app.data

import org.junit.Assert.*
import org.junit.Test

class BurstBufferTest {
    @Test fun burstUsesOneSignalAndPreservesEveryRow() {
        val queue = BurstBuffer<Int>(5000)
        assertTrue(queue.offer(0))
        assertTrue(queue.signal.tryReceive().isSuccess) // writer starts its flush delay
        for (i in 1 until 1000) assertTrue(queue.offer(i))
        assertTrue(queue.signal.tryReceive().isFailure) // no redundant second delay
        assertEquals((0 until 1000).toList(), queue.drain())
        assertTrue(queue.offer(1000))
        assertTrue(queue.signal.tryReceive().isSuccess) // next burst cannot be stranded
        assertEquals(listOf(1000), queue.drain())
    }

    @Test fun fullBufferRejectsOnlyNewRows() {
        val queue = BurstBuffer<Int>(2)
        assertTrue(queue.offer(1)); assertTrue(queue.offer(2)); assertFalse(queue.offer(3))
        assertEquals(listOf(1, 2), queue.drain())
    }

    @Test fun timedOutFlushRestoresBeforeNewArrivalsAndResignals() {
        val queue = BurstBuffer<Int>(2)
        queue.offer(1); queue.signal.tryReceive()
        val inFlight = queue.drain()
        queue.offer(2); queue.offer(3); queue.signal.tryReceive()
        queue.restore(inFlight)
        assertTrue(queue.signal.tryReceive().isSuccess)
        assertEquals(listOf(1, 2, 3), queue.drain())
        assertTrue(queue.isEmpty())
    }

    @Test fun concurrentProducersDoNotLoseRows() {
        val queue = BurstBuffer<Int>(4000)
        val threads = (0 until 4).map { producer -> Thread {
            repeat(1000) { check(queue.offer(producer * 1000 + it)) }
        }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals((0 until 4000).toSet(), queue.drain().toSet())
    }
}

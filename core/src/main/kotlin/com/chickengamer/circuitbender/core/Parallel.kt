package com.chickengamer.circuitbender.core

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Splits per-row work across a few daemon threads. Every caller computes each
 * row independently of the others, so the result is the same whatever the
 * split: the thread count changes speed, never pixels.
 */
internal object Parallel {
    // Three helpers at most, at low priority: lighting up every core buys
    // little on memory-bound loops but costs a phone heat, and the UI thread
    // must never have to wait behind the effects.
    private val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(0, 3)
    private val pool = if (workers == 0) {
        null
    } else {
        Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "circuitbender-worker").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
            }
        }
    }

    /** Run [block] over row ranges [from, to) covering 0 until [rows]. */
    fun rows(rows: Int, block: (from: Int, to: Int) -> Unit) {
        val p = pool
        if (p == null || rows < 48) {
            block(0, rows)
            return
        }
        val chunks = workers + 1
        val step = (rows + chunks - 1) / chunks
        val pending = ArrayList<Future<*>>(workers)
        var from = step
        while (from < rows) {
            val a = from
            val b = minOf(rows, a + step)
            pending += p.submit { block(a, b) }
            from = b
        }
        block(0, minOf(rows, step))
        for (f in pending) f.get()
    }
}

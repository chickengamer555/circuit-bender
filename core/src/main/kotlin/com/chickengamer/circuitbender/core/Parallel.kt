package com.chickengamer.circuitbender.core

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Splits per-row work across a few daemon threads. Every caller computes each
 * row independently of the others, so the result is the same whatever the
 * split: the thread count changes speed, never pixels.
 */
internal object Parallel {
    // Three helpers at most: lighting up every core buys little on
    // memory-bound loops but costs a phone heat. They run at normal priority:
    // Android parks low-priority threads on slow cores and schedules them
    // late, and every frame waits on them about ten times.
    private val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(0, 3)
    private val pool = if (workers == 0) {
        null
    } else {
        Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "circuitbender-worker").apply {
                isDaemon = true
            }
        }
    }

    private const val MIN_ROWS = 48

    /**
     * Off runs everything on the calling thread. The Android app turns it
     * off: measured on a phone (Benchmark.kt), handing rows to other cores
     * cost more than it saved at every frame size - a 640x480 dither-plus-tape
     * frame took 43 ms threaded and 33 ms on one thread - and one busy core
     * runs far cooler than four.
     */
    @Volatile var enabled = true

    /** Run [block] over row ranges [from, to) covering 0 until [rows]. */
    fun rows(rows: Int, block: (from: Int, to: Int) -> Unit) {
        val p = pool
        if (p == null || !enabled || rows < MIN_ROWS) {
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

package com.chickengamer.circuitbender.core

/** Public switch for the on-device benchmark: run effects single-threaded. */
object ParallelSwitch {
    fun set(on: Boolean) {
        Parallel.enabled = on
    }
}

package com.nesemu.net

import com.nesemu.core.Console

/** Advances a [Console] in lockstep with the remote player. Call only from the emulation thread. */
class NetplayDriver(private val console: Console, val net: Netplay) {
    var frame = 0
        private set
    private var lastEpoch = -1

    /** Host: start or restart the shared game from the current console state. */
    fun hostRestart(name: String) {
        net.hostStart(console.saveState(), console.cart.crc, name)
        frame = 0
        lastEpoch = net.epoch
    }

    /**
     * Schedules [localButtons] and emulates one frame if both players' inputs are known.
     * Returns false while waiting for the other player.
     */
    fun step(localButtons: Int): Boolean {
        if (!net.isHost) {
            net.takePendingState()?.let {
                console.loadState(it)
                frame = 0
                lastEpoch = net.epoch
            }
        }
        if (net.epoch != lastEpoch) {
            lastEpoch = net.epoch
            frame = 0
        }
        val target = frame + net.delay
        if (!net.hasLocal(target)) net.pushLocal(target, localButtons and 0xFF)
        val inputs = net.inputs(frame)
        if (inputs < 0) return false
        console.buttons1 = inputs and 0xFF
        console.buttons2 = (inputs shr 8) and 0xFF
        console.stepFrame()
        frame++
        if (frame % Netplay.HASH_INTERVAL == 0) net.reportHash(frame, console.stateHash())
        return true
    }
}

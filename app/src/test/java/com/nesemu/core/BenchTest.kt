package com.nesemu.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Rough emulation speed on the desktop JVM. */
class BenchTest {
    @Test
    fun framesPerSecond() {
        val f = File("src/test/resources/roms/nestest.nes")
        assumeTrue(f.exists())
        val nes = Console(f.readBytes())
        repeat(300) { nes.stepFrame() }
        val t = System.nanoTime()
        repeat(1200) { nes.stepFrame() }
        println("FPS=" + 1200 / ((System.nanoTime() - t) / 1e9))
    }
}

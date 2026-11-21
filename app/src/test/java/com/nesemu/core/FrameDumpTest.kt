package com.nesemu.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the screen of every test ROM to build/frames/ as PPM images for manual
 * inspection. Opt-in: set NES_DUMP_FRAMES=1.
 */
class FrameDumpTest {
    @Test
    fun dumpFrames() {
        assumeTrue(System.getenv("NES_DUMP_FRAMES") != null)
        val dir = File("src/test/resources/roms")
        val out = File("build/frames").apply { mkdirs() }
        val roms = dir.listFiles { f -> f.extension == "nes" }?.toList().orEmpty()
        assumeTrue(roms.isNotEmpty())
        for (f in roms) {
            val nes = Console(f.readBytes())
            repeat(System.getProperty("frames")?.toInt() ?: 600) { nes.stepFrame() }
            // Binary PPM (convert with any image tool).
            val header = "P6\n256 240\n255\n".toByteArray()
            val px = ByteArray(256 * 240 * 3)
            for (i in 0 until 256 * 240) {
                val c = nes.ppu.frame[i]
                px[i * 3] = (c shr 16).toByte(); px[i * 3 + 1] = (c shr 8).toByte(); px[i * 3 + 2] = c.toByte()
            }
            File(out, f.nameWithoutExtension + ".ppm").writeBytes(header + px)
        }
    }
}

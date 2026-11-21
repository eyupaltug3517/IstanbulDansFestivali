package com.nesemu.tools

import com.nesemu.core.Buttons
import com.nesemu.core.Console
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool: drives a ROM with a tiny input script and saves screenshots.
 * Env: PROBE_ROM, PROBE_OUT, PROBE_SCRIPT. Script tokens (space separated):
 *   w<N>            wait N frames
 *   p:<BTN>         tap a button (4 frames down, 8 up)
 *   h:<BTN>:<N>     hold for N frames
 *   s:<name>        screenshot (PPM)
 *   save:<file> / load:<file>   save state
 *   nt:<name>       dump the 4 nametables (hex)
 */
class RomProbe {
    private val names = mapOf(
        "A" to Buttons.A, "B" to Buttons.B, "SELECT" to Buttons.SELECT, "START" to Buttons.START,
        "UP" to Buttons.UP, "DOWN" to Buttons.DOWN, "LEFT" to Buttons.LEFT, "RIGHT" to Buttons.RIGHT
    )

    @Test
    fun run() {
        val romPath = System.getenv("PROBE_ROM")
        assumeTrue(romPath != null)
        val out = File(System.getenv("PROBE_OUT") ?: "build/probe").apply { mkdirs() }
        val nes = Console(File(romPath).readBytes())
        fun frames(n: Int, buttons: Int = 0) { repeat(n) { nes.buttons1 = buttons; nes.stepFrame() } }
        fun btn(s: String) = s.split('+').sumOf { names[it] ?: error("button $it") }
        for (tok in (System.getenv("PROBE_SCRIPT") ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val p = tok.split(':')
            when {
                tok.startsWith("w") && p.size == 1 -> frames(tok.substring(1).toInt())
                p[0] == "p" -> { frames(4, btn(p[1])); frames(8) }
                p[0] == "h" -> frames(p[2].toInt(), btn(p[1]))
                p[0] == "s" -> {
                    val px = ByteArray(256 * 240 * 3)
                    for (i in 0 until 256 * 240) {
                        val c = nes.ppu.frame[i]
                        px[i * 3] = (c shr 16).toByte(); px[i * 3 + 1] = (c shr 8).toByte(); px[i * 3 + 2] = c.toByte()
                    }
                    File(out, p[1] + ".ppm").writeBytes("P6\n256 240\n255\n".toByteArray() + px)
                }
                p[0] == "save" -> File(p[1]).writeBytes(nes.saveState())
                p[0] == "load" -> nes.loadState(File(p[1]).readBytes())
                p[0] == "nt" -> {
                    val sb = StringBuilder()
                    for (t in 0 until 4) for (row in 0 until 30) {
                        sb.append("%d %02d: ".format(t, row))
                        for (col in 0 until 32) sb.append("%02X ".format(nes.ppu.debugRead(0x2000 + t * 0x400 + row * 32 + col)))
                        sb.append('\n')
                    }
                    File(out, p[1] + ".nt.txt").writeText(sb.toString())
                }
                else -> error("bad token $tok")
            }
        }
    }
}

package com.nesemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs public NES test ROMs. Fetch them first with `scripts/fetch-test-roms.sh`;
 * tests whose ROM is missing are skipped.
 */
class ConsoleTest {
    private val romDir = File("src/test/resources/roms")

    private fun rom(name: String): ByteArray? {
        val f = File(romDir, name)
        return if (f.exists()) f.readBytes() else null
    }

    @Test
    fun nestestMatchesReferenceLog() {
        val rom = rom("nestest.nes")
        val log = File(romDir, "nestest.log")
        assumeTrue(rom != null && log.exists())
        val nes = Console(rom!!)
        nes.cpu.pc = 0xC000
        nes.cpu.p = 0x24
        nes.cpu.cycles = 7
        val re = Regex("""^([0-9A-F]{4}).*A:([0-9A-F]{2}) X:([0-9A-F]{2}) Y:([0-9A-F]{2}) P:([0-9A-F]{2}) SP:([0-9A-F]{2}).*CYC:(\d+)""")
        var line = 0
        for (entry in log.readLines()) {
            line++
            val m = re.find(entry) ?: continue
            val (pc, a, x, y, p, sp, cyc) = m.destructured
            val c = nes.cpu
            val actual = "%04X A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d".format(c.pc, c.a, c.x, c.y, c.p, c.sp, c.cycles)
            val expected = "$pc A:$a X:$x Y:$y P:$p SP:$sp CYC:$cyc"
            assertEquals("nestest.log line $line", expected, actual)
            nes.stepInstruction()
        }
        assertEquals(0, nes.read(0x02))
        assertEquals(0, nes.read(0x03))
    }

    /** blargg's test protocol: status at $6000, signature DE B0 61 at $6001, text from $6004. */
    private fun runBlargg(name: String, maxFrames: Int = 3000): Pair<Int, String> {
        val rom = rom(name)
        assumeTrue("$name not downloaded", rom != null)
        val nes = Console(rom!!)
        var resetAt = -1
        for (frame in 0 until maxFrames) {
            nes.stepFrame()
            val started = nes.read(0x6001) == 0xDE && nes.read(0x6002) == 0xB0 && nes.read(0x6003) == 0x61
            if (!started) continue
            val status = nes.read(0x6000)
            if (status == 0x81 && resetAt < 0) resetAt = frame + 10
            if (frame == resetAt) nes.reset()
            if (status < 0x80) return status to text(nes)
        }
        return -1 to text(nes)
    }

    private fun text(nes: Console): String {
        val out = ByteArrayOutputStream()
        var a = 0x6004
        while (a < 0x7000) {
            val b = nes.read(a)
            if (b == 0) break
            out.write(b); a++
        }
        return out.toString()
    }

    private fun assertBlargg(name: String) {
        val (status, msg) = runBlargg(name)
        println("== $name: status=$status\n$msg")
        assertTrue("$name failed ($status): $msg", status == 0)
    }

    @Test fun instrTestOfficial() = assertBlargg("official_only.nes")
    @Test fun instrTestAll() = assertBlargg("all_instrs.nes")
    @Test fun instrTiming() = assertBlargg("instr_timing.nes")
    @Test fun mmc3Clocking() = assertBlargg("1-clocking.nes")
    @Test fun mmc3Details() = assertBlargg("2-details.nes")
    @Test fun mmc3A12() = assertBlargg("3-A12_clocking.nes")
    @Test fun vblBasics() = assertBlargg("01-vbl_basics.nes")
    @Test fun vblClearTime() = assertBlargg("03-vbl_clear_time.nes")
    @Test fun apuLength() = assertBlargg("1-len_ctr.nes")
    @Test fun apuLengthTable() = assertBlargg("2-len_table.nes")
    @Test fun apuIrqFlag() = assertBlargg("3-irq_flag.nes")
    @Test fun oamRead() = assertBlargg("oam_read.nes")

    @Test
    fun saveStateRoundTrip() {
        val rom = rom("official_only.nes")
        assumeTrue(rom != null)
        val a = Console(rom!!)
        repeat(30) { a.stepFrame() }
        val state = a.saveState()
        val b = Console(rom)
        b.loadState(state)
        repeat(30) { a.stepFrame(); b.stepFrame() }
        assertTrue(a.ppu.frame.contentEquals(b.ppu.frame))
        assertEquals(a.cpu.pc, b.cpu.pc)
        assertEquals(a.cpu.cycles, b.cpu.cycles)
    }
}

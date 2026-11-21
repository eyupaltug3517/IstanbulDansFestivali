package com.nesemu.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object Buttons {
    const val A = 1
    const val B = 2
    const val SELECT = 4
    const val START = 8
    const val UP = 16
    const val DOWN = 32
    const val LEFT = 64
    const val RIGHT = 128
}

/** The whole NES: CPU bus, PPU, APU, cartridge and controllers. */
class Console(romData: ByteArray, sampleRate: Int = 44100) : CpuBus {
    val cart = Cartridge(romData)
    val mapper = Mapper.create(cart)
    val cpu = Cpu(this)
    val ppu = Ppu(mapper) { cpu.nmiPending = true }
    val apu = Apu({ read(it) }, { cpu.stall += it }, sampleRate)
    private val ram = ByteArray(0x800)

    /** Pressed buttons for player 1, see [Buttons]. Written from the UI thread. */
    @Volatile var buttons1 = 0
    @Volatile var buttons2 = 0
    private var strobe = false
    private var shift1 = 0
    private var shift2 = 0
    private val dmaBuffer = IntArray(256)

    init { cpu.powerOn() }

    fun reset() {
        cpu.reset()
        ppu.reset()
        apu.writeRegister(0x4015, 0)
    }

    // Cheats: ROM patches (Game Genie) are looked up per address; RAM codes are applied every frame.
    private val romPatchValue = IntArray(0x8000) { -1 }
    private val romPatchCompare = IntArray(0x8000) { -1 }
    private var romPatches = 0
    private var ramCheats: List<Cheat> = emptyList()

    fun setCheats(cheats: List<Cheat>) {
        romPatchValue.fill(-1)
        romPatchCompare.fill(-1)
        romPatches = 0
        for (c in cheats.filter { !it.ram }) {
            romPatchValue[c.address - 0x8000] = c.value
            romPatchCompare[c.address - 0x8000] = c.compare
            romPatches++
        }
        ramCheats = cheats.filter { it.ram }
    }

    /** Runs the emulation until the PPU has produced a full frame. */
    fun stepFrame() {
        for (c in ramCheats) write(c.address, c.value)
        ppu.frameComplete = false
        while (!ppu.frameComplete) stepInstruction()
    }

    fun stepInstruction(): Int {
        val c = cpu.step()
        for (i in 0 until c * 3) ppu.step()
        for (i in 0 until c) apu.step()
        return c
    }

    override fun irqLine(): Boolean = apu.irq || mapper.irq

    override fun read(addr: Int): Int = when {
        addr < 0x2000 -> ram[addr and 0x7FF].toInt() and 0xFF
        addr < 0x4000 -> ppu.readRegister(addr and 7)
        addr == 0x4015 -> apu.readStatus()
        addr == 0x4016 -> readController(1)
        addr == 0x4017 -> readController(2)
        addr < 0x4020 -> 0
        addr >= 0x8000 && romPatches > 0 -> patchedRead(addr)
        else -> mapper.cpuRead(addr)
    }

    private fun patchedRead(addr: Int): Int {
        val original = mapper.cpuRead(addr)
        val patch = romPatchValue[addr - 0x8000]
        if (patch < 0) return original
        val compare = romPatchCompare[addr - 0x8000]
        return if (compare < 0 || compare == original) patch else original
    }

    override fun write(addr: Int, value: Int) {
        when {
            addr < 0x2000 -> ram[addr and 0x7FF] = value.toByte()
            addr < 0x4000 -> ppu.writeRegister(addr and 7, value)
            addr == 0x4014 -> {
                val base = value shl 8
                for (i in 0 until 256) dmaBuffer[i] = read(base + i)
                ppu.writeDma(dmaBuffer)
                cpu.stall += 513 + (cpu.cycles and 1L).toInt()
            }
            addr == 0x4016 -> {
                strobe = value and 1 != 0
                if (strobe) { shift1 = buttons1; shift2 = buttons2 }
            }
            addr < 0x4018 -> apu.writeRegister(addr, value)
            addr < 0x4020 -> {}
            else -> mapper.cpuWrite(addr, value)
        }
    }

    private fun readController(port: Int): Int {
        if (strobe) {
            shift1 = buttons1; shift2 = buttons2
        }
        val bit: Int
        if (port == 1) {
            bit = shift1 and 1
            if (!strobe) shift1 = (shift1 shr 1) or 0x80
        } else {
            bit = shift2 and 1
            if (!strobe) shift2 = (shift2 shr 1) or 0x80
        }
        return bit or 0x40
    }

    /** Cheap fingerprint of the machine state, used to detect netplay desyncs. */
    fun stateHash(): Int {
        val crc = java.util.zip.CRC32()
        crc.update(ram)
        crc.update(cpu.a); crc.update(cpu.x); crc.update(cpu.y); crc.update(cpu.sp)
        crc.update(cpu.pc and 0xFF); crc.update(cpu.pc shr 8)
        return crc.value.toInt()
    }

    // ---- Battery-backed RAM ----

    val hasBattery get() = cart.hasBattery
    fun batteryRam(): ByteArray = cart.prgRam.copyOf()
    fun loadBatteryRam(data: ByteArray) {
        if (data.size == cart.prgRam.size) System.arraycopy(data, 0, cart.prgRam, 0, data.size)
    }

    // ---- Save states ----

    fun saveState(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(cart.crc)
            cpu.save(out)
            out.writeBytes(ram)
            ppu.save(out)
            apu.save(out)
            mapper.save(out)
            out.writeBoolean(strobe); out.writeInt(shift1); out.writeInt(shift2)
        }
        return bytes.toByteArray()
    }

    fun loadState(data: ByteArray) {
        // Validate against a backup so a broken file never leaves the console half-loaded.
        val backup = saveState()
        try {
            applyState(data)
        } catch (e: Exception) {
            applyState(backup)
            throw e
        }
    }

    private fun applyState(data: ByteArray) {
        DataInputStream(ByteArrayInputStream(data)).use { inp ->
            if (inp.readInt() != MAGIC) throw IllegalStateException("Geçersiz kayıt dosyası")
            if (inp.readInt() != VERSION) throw IllegalStateException("Kayıt dosyası farklı bir sürüme ait")
            if (inp.readLong() != cart.crc) throw IllegalStateException("Bu kayıt başka bir oyuna ait")
            cpu.load(inp)
            inp.readBytesInto(ram)
            ppu.load(inp)
            apu.load(inp)
            mapper.load(inp)
            strobe = inp.readBoolean(); shift1 = inp.readInt(); shift2 = inp.readInt()
        }
    }

    companion object {
        private const val MAGIC = 0x4E455353 // "NESS"
        private const val VERSION = 1
    }
}

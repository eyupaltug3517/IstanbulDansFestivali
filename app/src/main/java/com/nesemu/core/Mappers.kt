package com.nesemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Base mapper with generic banking: PRG is mapped in four 8 KB slots
 * ($8000-$FFFF), CHR in eight 1 KB slots ($0000-$1FFF).
 */
abstract class Mapper(val cart: Cartridge) : Stateful {
    protected val prg = cart.prg
    protected val chr = cart.chr
    private val prgRam = cart.prgRam
    private val prgOff = IntArray(4)
    private val chrOff = IntArray(8)
    open var mirroring: Mirroring = cart.mirroring
    /** Level-triggered IRQ line driven by the mapper. */
    var irq = false

    private val prg8kCount = prg.size / 0x2000
    private val chr1kCount = chr.size / 0x400

    private fun wrap(bank: Int, count: Int): Int = ((bank % count) + count) % count

    protected fun setPrg8k(slot: Int, bank: Int) {
        prgOff[slot] = wrap(bank, prg8kCount) * 0x2000
    }

    protected fun setPrg16k(slot: Int, bank: Int) {
        val count16 = maxOf(1, prg8kCount / 2)
        val b = wrap(bank, count16)
        setPrg8k(slot * 2, b * 2)
        setPrg8k(slot * 2 + 1, b * 2 + 1)
    }

    protected fun setPrg32k(bank: Int) {
        val count32 = maxOf(1, prg8kCount / 4)
        val b = wrap(bank, count32)
        for (i in 0 until 4) setPrg8k(i, b * 4 + i)
    }

    protected fun setChr1k(slot: Int, bank: Int) {
        chrOff[slot] = wrap(bank, chr1kCount) * 0x400
    }

    protected fun setChr2k(slot: Int, bank: Int) {
        setChr1k(slot * 2, bank * 2); setChr1k(slot * 2 + 1, bank * 2 + 1)
    }

    protected fun setChr4k(slot: Int, bank: Int) {
        for (i in 0 until 4) setChr1k(slot * 4 + i, bank * 4 + i)
    }

    protected fun setChr8k(bank: Int) {
        for (i in 0 until 8) setChr1k(i, bank * 8 + i)
    }

    open fun cpuRead(addr: Int): Int = when {
        addr >= 0x8000 -> prg[prgOff[(addr - 0x8000) shr 13] + (addr and 0x1FFF)].toInt() and 0xFF
        addr >= 0x6000 -> prgRam[addr - 0x6000].toInt() and 0xFF
        else -> addr shr 8 // open bus approximation
    }

    open fun cpuWrite(addr: Int, value: Int) {
        if (addr >= 0x8000) writeRegister(addr, value)
        else if (addr >= 0x6000) prgRam[addr - 0x6000] = value.toByte()
    }

    protected abstract fun writeRegister(addr: Int, value: Int)

    fun ppuRead(addr: Int): Int = chr[chrOff[addr shr 10] + (addr and 0x3FF)].toInt() and 0xFF

    fun ppuWrite(addr: Int, value: Int) {
        if (cart.chrIsRam) chr[chrOff[addr shr 10] + (addr and 0x3FF)] = value.toByte()
    }

    /** Notified whenever the PPU puts [addr] on its address bus; [dot] is a running PPU clock. */
    open fun ppuAddress(addr: Int, dot: Long) {}

    protected open fun saveExtra(out: DataOutputStream) {}
    protected open fun loadExtra(inp: DataInputStream) {}

    final override fun save(out: DataOutputStream) {
        out.writeInts(prgOff); out.writeInts(chrOff)
        out.writeInt(mirroring.ordinal); out.writeBoolean(irq)
        out.writeBytes(prgRam)
        if (cart.chrIsRam) out.writeBytes(chr)
        saveExtra(out)
    }

    final override fun load(inp: DataInputStream) {
        inp.readIntsInto(prgOff); inp.readIntsInto(chrOff)
        mirroring = Mirroring.entries[inp.readInt()]; irq = inp.readBoolean()
        inp.readBytesInto(prgRam)
        if (cart.chrIsRam) inp.readBytesInto(chr)
        loadExtra(inp)
    }

    companion object {
        fun create(cart: Cartridge): Mapper = when (cart.mapperId) {
            0 -> Nrom(cart)
            1 -> Mmc1(cart)
            2 -> UxRom(cart)
            3 -> CnRom(cart)
            4 -> Mmc3(cart)
            7 -> AxRom(cart)
            11 -> ColorDreams(cart)
            66 -> GxRom(cart)
            else -> throw RomException("Bu oyunun mapper'ı (${cart.mapperId}) henüz desteklenmiyor")
        }
    }
}

class Nrom(cart: Cartridge) : Mapper(cart) {
    init { setPrg16k(0, 0); setPrg16k(1, -1); setChr8k(0) }
    override fun writeRegister(addr: Int, value: Int) {}
}

class UxRom(cart: Cartridge) : Mapper(cart) {
    init { setPrg16k(0, 0); setPrg16k(1, -1); setChr8k(0) }
    override fun writeRegister(addr: Int, value: Int) = setPrg16k(0, value)
}

class CnRom(cart: Cartridge) : Mapper(cart) {
    init { setPrg16k(0, 0); setPrg16k(1, -1); setChr8k(0) }
    override fun writeRegister(addr: Int, value: Int) = setChr8k(value)
}

class AxRom(cart: Cartridge) : Mapper(cart) {
    init { setPrg32k(0); setChr8k(0); mirroring = Mirroring.SINGLE0 }
    override fun writeRegister(addr: Int, value: Int) {
        setPrg32k(value and 0x07)
        mirroring = if (value and 0x10 != 0) Mirroring.SINGLE1 else Mirroring.SINGLE0
    }
}

class ColorDreams(cart: Cartridge) : Mapper(cart) {
    init { setPrg32k(0); setChr8k(0) }
    override fun writeRegister(addr: Int, value: Int) {
        setPrg32k(value and 0x03); setChr8k(value shr 4)
    }
}

class GxRom(cart: Cartridge) : Mapper(cart) {
    init { setPrg32k(0); setChr8k(0) }
    override fun writeRegister(addr: Int, value: Int) {
        setPrg32k((value shr 4) and 0x03); setChr8k(value and 0x03)
    }
}

class Mmc1(cart: Cartridge) : Mapper(cart) {
    private var shift = 0x10
    private var control = 0x0C
    private var chr0 = 0
    private var chr1 = 0
    private var prgReg = 0

    init { update() }

    override fun writeRegister(addr: Int, value: Int) {
        if (value and 0x80 != 0) {
            shift = 0x10
            control = control or 0x0C
            update()
            return
        }
        val complete = shift and 1 != 0
        shift = (shift shr 1) or ((value and 1) shl 4)
        if (complete) {
            when ((addr shr 13) and 3) {
                0 -> control = shift
                1 -> chr0 = shift
                2 -> chr1 = shift
                3 -> prgReg = shift
            }
            shift = 0x10
            update()
        }
    }

    private fun update() {
        mirroring = when (control and 3) {
            0 -> Mirroring.SINGLE0
            1 -> Mirroring.SINGLE1
            2 -> Mirroring.VERTICAL
            else -> Mirroring.HORIZONTAL
        }
        // SUROM (512 KB PRG): CHR register bit 4 selects the 256 KB half.
        val outer = if (prg.size > 0x40000) (chr0 and 0x10) else 0
        val bank = (prgReg and 0x0F) or outer
        when ((control shr 2) and 3) {
            0, 1 -> setPrg32k(bank shr 1)
            2 -> { setPrg16k(0, outer); setPrg16k(1, bank) }
            else -> { setPrg16k(0, bank); setPrg16k(1, outer or 0x0F) }
        }
        if (control and 0x10 == 0) {
            setChr4k(0, chr0 and 0x1E); setChr4k(1, (chr0 and 0x1E) + 1)
        } else {
            setChr4k(0, chr0); setChr4k(1, chr1)
        }
    }

    override fun saveExtra(out: DataOutputStream) {
        out.writeInt(shift); out.writeInt(control); out.writeInt(chr0); out.writeInt(chr1); out.writeInt(prgReg)
    }

    override fun loadExtra(inp: DataInputStream) {
        shift = inp.readInt(); control = inp.readInt(); chr0 = inp.readInt(); chr1 = inp.readInt(); prgReg = inp.readInt()
    }
}

class Mmc3(cart: Cartridge) : Mapper(cart) {
    private val regs = IntArray(8)
    private var bankSelect = 0
    private var irqLatch = 0
    private var irqCounter = 0
    private var irqReload = false
    private var irqEnabled = false

    init {
        regs[6] = 0; regs[7] = 1
        update()
    }

    override fun writeRegister(addr: Int, value: Int) {
        val even = addr and 1 == 0
        when (addr and 0xE000) {
            0x8000 -> if (even) { bankSelect = value; update() } else { regs[bankSelect and 7] = value; update() }
            0xA000 -> if (even && cart.mirroring != Mirroring.FOUR_SCREEN) {
                mirroring = if (value and 1 != 0) Mirroring.HORIZONTAL else Mirroring.VERTICAL
            }
            0xC000 -> if (even) irqLatch = value else { irqCounter = 0; irqReload = true }
            0xE000 -> if (even) { irqEnabled = false; irq = false } else irqEnabled = true
        }
    }

    private fun update() {
        if (bankSelect and 0x40 == 0) {
            setPrg8k(0, regs[6]); setPrg8k(2, -2)
        } else {
            setPrg8k(0, -2); setPrg8k(2, regs[6])
        }
        setPrg8k(1, regs[7]); setPrg8k(3, -1)
        val inv = if (bankSelect and 0x80 != 0) 4 else 0
        setChr1k(0 xor inv, regs[0] and 0xFE); setChr1k(1 xor inv, regs[0] or 1)
        setChr1k(2 xor inv, regs[1] and 0xFE); setChr1k(3 xor inv, regs[1] or 1)
        setChr1k(4 xor inv, regs[2]); setChr1k(5 xor inv, regs[3])
        setChr1k(6 xor inv, regs[4]); setChr1k(7 xor inv, regs[5])
    }

    private var lastA12 = false
    private var a12LowSince = 0L

    override fun ppuAddress(addr: Int, dot: Long) {
        if (addr and 0x1000 != 0) {
            // Rising edge of A12, filtered like the real chip (ignores short low pulses).
            if (!lastA12 && dot - a12LowSince >= 10) clockCounter()
            lastA12 = true
        } else {
            if (lastA12) a12LowSince = dot
            lastA12 = false
        }
    }

    private fun clockCounter() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch
            irqReload = false
        } else {
            irqCounter--
        }
        if (irqCounter == 0 && irqEnabled) irq = true
    }

    override fun saveExtra(out: DataOutputStream) {
        out.writeInts(regs); out.writeInt(bankSelect); out.writeInt(irqLatch); out.writeInt(irqCounter)
        out.writeBoolean(irqReload); out.writeBoolean(irqEnabled)
        out.writeBoolean(lastA12); out.writeLong(a12LowSince)
    }

    override fun loadExtra(inp: DataInputStream) {
        inp.readIntsInto(regs); bankSelect = inp.readInt(); irqLatch = inp.readInt(); irqCounter = inp.readInt()
        irqReload = inp.readBoolean(); irqEnabled = inp.readBoolean()
        lastA12 = inp.readBoolean(); a12LowSince = inp.readLong()
    }
}

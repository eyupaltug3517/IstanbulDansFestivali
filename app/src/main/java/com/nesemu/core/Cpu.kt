package com.nesemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

interface CpuBus {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
    fun irqLine(): Boolean
}

/** MOS 6502 (Ricoh 2A03, no decimal mode) with official and common unofficial opcodes. */
class Cpu(private val bus: CpuBus) : Stateful {
    var a = 0; var x = 0; var y = 0
    var sp = 0xFD
    var pc = 0
    private var fc = 0; private var fz = 0; private var fi = 1; private var fd = 0
    private var fv = 0; private var fn = 0
    var cycles = 0L
    var stall = 0
    var nmiPending = false
    private var pageCrossed = false
    private var extraCycles = 0

    var p: Int
        get() = fc or (fz shl 1) or (fi shl 2) or (fd shl 3) or 0x20 or (fv shl 6) or (fn shl 7)
        set(value) {
            fc = value and 1; fz = (value shr 1) and 1; fi = (value shr 2) and 1
            fd = (value shr 3) and 1; fv = (value shr 6) and 1; fn = (value shr 7) and 1
        }

    fun reset() {
        pc = read16(0xFFFC)
        sp = (sp - 3) and 0xFF
        fi = 1
        cycles = 7
    }

    fun powerOn() {
        a = 0; x = 0; y = 0; sp = 0xFD; p = 0x24
        pc = read16(0xFFFC)
        cycles = 7
    }

    private fun read(addr: Int) = bus.read(addr)
    private fun write(addr: Int, v: Int) = bus.write(addr, v and 0xFF)
    private fun read16(addr: Int) = read(addr) or (read((addr + 1) and 0xFFFF) shl 8)

    private fun push(v: Int) { write(0x100 or sp, v); sp = (sp - 1) and 0xFF }
    private fun pull(): Int { sp = (sp + 1) and 0xFF; return read(0x100 or sp) }
    private fun push16(v: Int) { push(v shr 8); push(v and 0xFF) }
    private fun pull16(): Int { val lo = pull(); return lo or (pull() shl 8) }

    private fun setZN(v: Int) { fz = if (v and 0xFF == 0) 1 else 0; fn = (v shr 7) and 1 }

    private fun interrupt(vector: Int) {
        push16(pc)
        push(p and 0xEF)
        fi = 1
        pc = read16(vector)
    }

    /** Executes one instruction (or interrupt / stall) and returns the CPU cycles used. */
    fun step(): Int {
        if (stall > 0) {
            val s = stall
            stall = 0
            cycles += s
            return s
        }
        if (nmiPending) {
            nmiPending = false
            interrupt(0xFFFA)
            cycles += 7
            return 7
        }
        if (fi == 0 && bus.irqLine()) {
            interrupt(0xFFFE)
            cycles += 7
            return 7
        }
        val op = read(pc)
        pc = (pc + 1) and 0xFFFF
        val mode = MODE[op]
        pageCrossed = false
        extraCycles = 0
        val addr = address(mode)
        execute(INS[op], mode, addr)
        var c = CYCLES[op] + extraCycles
        if (pageCrossed && PAGE[op]) c++
        cycles += c
        return c
    }

    private fun address(mode: Int): Int {
        when (mode) {
            IMP, ACC -> return 0
            IMM -> { val a = pc; pc = (pc + 1) and 0xFFFF; return a }
            ZP -> { val a = read(pc); pc = (pc + 1) and 0xFFFF; return a }
            ZPX -> { val a = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF; return a }
            ZPY -> { val a = (read(pc) + y) and 0xFF; pc = (pc + 1) and 0xFFFF; return a }
            ABS -> { val a = read16(pc); pc = (pc + 2) and 0xFFFF; return a }
            ABX -> {
                val base = read16(pc); pc = (pc + 2) and 0xFFFF
                val a = (base + x) and 0xFFFF
                pageCrossed = (base xor a) and 0xFF00 != 0
                return a
            }
            ABY -> {
                val base = read16(pc); pc = (pc + 2) and 0xFFFF
                val a = (base + y) and 0xFFFF
                pageCrossed = (base xor a) and 0xFF00 != 0
                return a
            }
            IND -> {
                val ptr = read16(pc); pc = (pc + 2) and 0xFFFF
                val hiAddr = (ptr and 0xFF00) or ((ptr + 1) and 0xFF) // page wrap bug
                return read(ptr) or (read(hiAddr) shl 8)
            }
            IZX -> {
                val zp = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF
                return read(zp) or (read((zp + 1) and 0xFF) shl 8)
            }
            IZY -> {
                val zp = read(pc); pc = (pc + 1) and 0xFFFF
                val base = read(zp) or (read((zp + 1) and 0xFF) shl 8)
                val a = (base + y) and 0xFFFF
                pageCrossed = (base xor a) and 0xFF00 != 0
                return a
            }
            REL -> {
                val off = read(pc).toByte().toInt(); pc = (pc + 1) and 0xFFFF
                return (pc + off) and 0xFFFF
            }
        }
        return 0
    }

    private fun branch(cond: Boolean, addr: Int) {
        if (cond) {
            extraCycles = if ((pc xor addr) and 0xFF00 != 0) 2 else 1
            pc = addr
        }
    }

    private fun adc(m: Int) {
        val r = a + m + fc
        fc = if (r > 0xFF) 1 else 0
        fv = if ((a xor m).inv() and (a xor r) and 0x80 != 0) 1 else 0
        a = r and 0xFF
        setZN(a)
    }

    private fun compare(reg: Int, m: Int) {
        val r = reg - m
        fc = if (r >= 0) 1 else 0
        setZN(r and 0xFF)
    }

    private fun asl(v: Int): Int { fc = (v shr 7) and 1; val r = (v shl 1) and 0xFF; setZN(r); return r }
    private fun lsr(v: Int): Int { fc = v and 1; val r = v shr 1; setZN(r); return r }
    private fun rol(v: Int): Int { val r = ((v shl 1) or fc) and 0xFF; fc = (v shr 7) and 1; setZN(r); return r }
    private fun ror(v: Int): Int { val r = (v shr 1) or (fc shl 7); fc = v and 1; setZN(r); return r }

    /** Read-modify-write helper that handles accumulator mode. */
    private inline fun rmw(mode: Int, addr: Int, f: (Int) -> Int): Int {
        return if (mode == ACC) { a = f(a); a } else { val r = f(read(addr)); write(addr, r); r }
    }

    /** SHX/SHY/SHA/TAS: store value AND (high byte of base address + 1). */
    private fun unstableStore(addr: Int, index: Int, value: Int) {
        val base = (addr - index) and 0xFFFF
        val v = value and (((base shr 8) + 1) and 0xFF)
        val target = if ((base xor addr) and 0xFF00 != 0) (v shl 8) or (addr and 0xFF) else addr
        write(target, v)
    }

    private fun execute(ins: Int, mode: Int, addr: Int) {
        when (ins) {
            ADC -> adc(read(addr))
            SBC -> adc(read(addr) xor 0xFF)
            AND -> { a = a and read(addr); setZN(a) }
            ORA -> { a = a or read(addr); setZN(a) }
            EOR -> { a = a xor read(addr); setZN(a) }
            ASL -> rmw(mode, addr) { asl(it) }
            LSR -> rmw(mode, addr) { lsr(it) }
            ROL -> rmw(mode, addr) { rol(it) }
            ROR -> rmw(mode, addr) { ror(it) }
            BCC -> branch(fc == 0, addr)
            BCS -> branch(fc == 1, addr)
            BEQ -> branch(fz == 1, addr)
            BNE -> branch(fz == 0, addr)
            BMI -> branch(fn == 1, addr)
            BPL -> branch(fn == 0, addr)
            BVC -> branch(fv == 0, addr)
            BVS -> branch(fv == 1, addr)
            BIT -> {
                val m = read(addr)
                fz = if (a and m == 0) 1 else 0
                fv = (m shr 6) and 1
                fn = (m shr 7) and 1
            }
            BRK -> {
                pc = (pc + 1) and 0xFFFF
                push16(pc)
                push(p or 0x10)
                fi = 1
                pc = read16(0xFFFE)
            }
            CLC -> fc = 0
            CLD -> fd = 0
            CLI -> fi = 0
            CLV -> fv = 0
            SEC -> fc = 1
            SED -> fd = 1
            SEI -> fi = 1
            CMP -> compare(a, read(addr))
            CPX -> compare(x, read(addr))
            CPY -> compare(y, read(addr))
            DEC -> { val r = (read(addr) - 1) and 0xFF; write(addr, r); setZN(r) }
            INC -> { val r = (read(addr) + 1) and 0xFF; write(addr, r); setZN(r) }
            DEX -> { x = (x - 1) and 0xFF; setZN(x) }
            DEY -> { y = (y - 1) and 0xFF; setZN(y) }
            INX -> { x = (x + 1) and 0xFF; setZN(x) }
            INY -> { y = (y + 1) and 0xFF; setZN(y) }
            JMP -> pc = addr
            JSR -> { push16((pc - 1) and 0xFFFF); pc = addr }
            RTS -> pc = (pull16() + 1) and 0xFFFF
            RTI -> { p = pull(); pc = pull16() }
            LDA -> { a = read(addr); setZN(a) }
            LDX -> { x = read(addr); setZN(x) }
            LDY -> { y = read(addr); setZN(y) }
            STA -> write(addr, a)
            STX -> write(addr, x)
            STY -> write(addr, y)
            PHA -> push(a)
            PHP -> push(p or 0x10)
            PLA -> { a = pull(); setZN(a) }
            PLP -> p = pull()
            TAX -> { x = a; setZN(x) }
            TAY -> { y = a; setZN(y) }
            TSX -> { x = sp; setZN(x) }
            TXA -> { a = x; setZN(a) }
            TXS -> sp = x
            TYA -> { a = y; setZN(a) }
            NOP -> if (mode != IMP) read(addr)
            // Unofficial opcodes
            LAX -> { a = read(addr); x = a; setZN(a) }
            SAX -> write(addr, a and x)
            DCP -> { val r = (read(addr) - 1) and 0xFF; write(addr, r); compare(a, r) }
            ISB -> { val r = (read(addr) + 1) and 0xFF; write(addr, r); adc(r xor 0xFF) }
            SLO -> { val r = rmw(mode, addr) { asl(it) }; a = a or r; setZN(a) }
            RLA -> { val r = rmw(mode, addr) { rol(it) }; a = a and r; setZN(a) }
            SRE -> { val r = rmw(mode, addr) { lsr(it) }; a = a xor r; setZN(a) }
            RRA -> { val r = rmw(mode, addr) { ror(it) }; adc(r) }
            ANC -> { a = a and read(addr); setZN(a); fc = fn }
            ALR -> { a = lsr(a and read(addr)) }
            ARR -> {
                a = ((a and read(addr)) shr 1) or (fc shl 7)
                setZN(a)
                fc = (a shr 6) and 1
                fv = ((a shr 6) xor (a shr 5)) and 1
            }
            AXS -> {
                val r = (a and x) - read(addr)
                fc = if (r >= 0) 1 else 0
                x = r and 0xFF
                setZN(x)
            }
            SHX -> unstableStore(addr, y, x)
            SHY -> unstableStore(addr, x, y)
            SHA -> unstableStore(addr, y, a and x)
            TAS -> { sp = a and x; unstableStore(addr, y, sp) }
            XAA -> { a = (a or 0xEE) and x and read(addr); setZN(a) }
            LAS -> { sp = sp and read(addr); a = sp; x = sp; setZN(a) }
        }
    }

    override fun save(out: DataOutputStream) {
        out.writeInt(a); out.writeInt(x); out.writeInt(y); out.writeInt(sp); out.writeInt(pc); out.writeInt(p)
        out.writeLong(cycles); out.writeInt(stall); out.writeBoolean(nmiPending)
    }

    override fun load(inp: DataInputStream) {
        a = inp.readInt(); x = inp.readInt(); y = inp.readInt(); sp = inp.readInt(); pc = inp.readInt(); p = inp.readInt()
        cycles = inp.readLong(); stall = inp.readInt(); nmiPending = inp.readBoolean()
    }

    companion object {
        const val IMP = 0; const val ACC = 1; const val IMM = 2; const val ZP = 3; const val ZPX = 4
        const val ZPY = 5; const val ABS = 6; const val ABX = 7; const val ABY = 8; const val IND = 9
        const val IZX = 10; const val IZY = 11; const val REL = 12

        const val ADC = 1; const val AND = 2; const val ASL = 3; const val BCC = 4; const val BCS = 5
        const val BEQ = 6; const val BIT = 7; const val BMI = 8; const val BNE = 9; const val BPL = 10
        const val BRK = 11; const val BVC = 12; const val BVS = 13; const val CLC = 14; const val CLD = 15
        const val CLI = 16; const val CLV = 17; const val CMP = 18; const val CPX = 19; const val CPY = 20
        const val DEC = 21; const val DEX = 22; const val DEY = 23; const val EOR = 24; const val INC = 25
        const val INX = 26; const val INY = 27; const val JMP = 28; const val JSR = 29; const val LDA = 30
        const val LDX = 31; const val LDY = 32; const val LSR = 33; const val NOP = 34; const val ORA = 35
        const val PHA = 36; const val PHP = 37; const val PLA = 38; const val PLP = 39; const val ROL = 40
        const val ROR = 41; const val RTI = 42; const val RTS = 43; const val SBC = 44; const val SEC = 45
        const val SED = 46; const val SEI = 47; const val STA = 48; const val STX = 49; const val STY = 50
        const val TAX = 51; const val TAY = 52; const val TSX = 53; const val TXA = 54; const val TXS = 55
        const val TYA = 56; const val LAX = 57; const val SAX = 58; const val DCP = 59; const val ISB = 60
        const val SLO = 61; const val RLA = 62; const val SRE = 63; const val RRA = 64; const val ANC = 65
        const val ALR = 66; const val ARR = 67; const val AXS = 68; const val SHX = 69; const val SHY = 70
        const val LAS = 71; const val SHA = 72; const val TAS = 73; const val XAA = 74

        val INS = IntArray(256) { NOP }
        val MODE = IntArray(256) { IMP }
        val CYCLES = IntArray(256) { 2 }
        val PAGE = BooleanArray(256)

        private const val KIND_READ = 0
        private const val KIND_WRITE = 1
        private const val KIND_RMW = 2

        private fun cyclesFor(kind: Int, mode: Int): Int = when (kind) {
            KIND_READ -> when (mode) {
                IMM -> 2; ZP -> 3; ZPX, ZPY, ABS, ABX, ABY -> 4; IZX -> 6; IZY -> 5; else -> 2
            }
            KIND_WRITE -> when (mode) {
                ZP -> 3; ZPX, ZPY, ABS -> 4; ABX, ABY -> 5; IZX, IZY -> 6; else -> 2
            }
            else -> when (mode) {
                ACC -> 2; ZP -> 5; ZPX, ABS -> 6; ABX, ABY -> 7; IZX, IZY -> 8; else -> 2
            }
        }

        private fun group(ins: Int, kind: Int, vararg ops: Int) {
            var i = 0
            while (i < ops.size) {
                val code = ops[i]; val mode = ops[i + 1]
                INS[code] = ins; MODE[code] = mode
                CYCLES[code] = cyclesFor(kind, mode)
                PAGE[code] = kind == KIND_READ && (mode == ABX || mode == ABY || mode == IZY)
                i += 2
            }
        }

        private fun single(code: Int, ins: Int, mode: Int, cycles: Int) {
            INS[code] = ins; MODE[code] = mode; CYCLES[code] = cycles; PAGE[code] = false // branch penalties are added in branch()
        }

        init {
            val std = intArrayOf(IMM, ZP, ZPX, ABS, ABX, ABY, IZX, IZY)
            fun std8(base: Int): IntArray {
                // Standard "group one" layout: imm, zp, zpx, abs, abx, aby, izx, izy
                val codes = intArrayOf(base + 0x09, base + 0x05, base + 0x15, base + 0x0D, base + 0x1D, base + 0x19, base + 0x01, base + 0x11)
                val r = IntArray(16)
                for (k in 0 until 8) { r[k * 2] = codes[k]; r[k * 2 + 1] = std[k] }
                return r
            }
            group(ORA, KIND_READ, *std8(0x00))
            group(AND, KIND_READ, *std8(0x20))
            group(EOR, KIND_READ, *std8(0x40))
            group(ADC, KIND_READ, *std8(0x60))
            group(LDA, KIND_READ, *std8(0xA0))
            group(CMP, KIND_READ, *std8(0xC0))
            group(SBC, KIND_READ, *std8(0xE0))
            group(SBC, KIND_READ, 0xEB, IMM)
            group(STA, KIND_WRITE, 0x85, ZP, 0x95, ZPX, 0x8D, ABS, 0x9D, ABX, 0x99, ABY, 0x81, IZX, 0x91, IZY)

            fun shifts(ins: Int, base: Int) =
                group(ins, KIND_RMW, base + 0x0A, ACC, base + 0x06, ZP, base + 0x16, ZPX, base + 0x0E, ABS, base + 0x1E, ABX)
            shifts(ASL, 0x00); shifts(ROL, 0x20); shifts(LSR, 0x40); shifts(ROR, 0x60)
            group(DEC, KIND_RMW, 0xC6, ZP, 0xD6, ZPX, 0xCE, ABS, 0xDE, ABX)
            group(INC, KIND_RMW, 0xE6, ZP, 0xF6, ZPX, 0xEE, ABS, 0xFE, ABX)

            group(LDX, KIND_READ, 0xA2, IMM, 0xA6, ZP, 0xB6, ZPY, 0xAE, ABS, 0xBE, ABY)
            group(LDY, KIND_READ, 0xA0, IMM, 0xA4, ZP, 0xB4, ZPX, 0xAC, ABS, 0xBC, ABX)
            group(STX, KIND_WRITE, 0x86, ZP, 0x96, ZPY, 0x8E, ABS)
            group(STY, KIND_WRITE, 0x84, ZP, 0x94, ZPX, 0x8C, ABS)
            group(CPX, KIND_READ, 0xE0, IMM, 0xE4, ZP, 0xEC, ABS)
            group(CPY, KIND_READ, 0xC0, IMM, 0xC4, ZP, 0xCC, ABS)
            group(BIT, KIND_READ, 0x24, ZP, 0x2C, ABS)

            single(0x10, BPL, REL, 2); single(0x30, BMI, REL, 2); single(0x50, BVC, REL, 2); single(0x70, BVS, REL, 2)
            single(0x90, BCC, REL, 2); single(0xB0, BCS, REL, 2); single(0xD0, BNE, REL, 2); single(0xF0, BEQ, REL, 2)

            single(0x00, BRK, IMP, 7); single(0x20, JSR, ABS, 6); single(0x40, RTI, IMP, 6); single(0x60, RTS, IMP, 6)
            single(0x4C, JMP, ABS, 3); single(0x6C, JMP, IND, 5)
            single(0x08, PHP, IMP, 3); single(0x28, PLP, IMP, 4); single(0x48, PHA, IMP, 3); single(0x68, PLA, IMP, 4)
            single(0x18, CLC, IMP, 2); single(0x38, SEC, IMP, 2); single(0x58, CLI, IMP, 2); single(0x78, SEI, IMP, 2)
            single(0xB8, CLV, IMP, 2); single(0xD8, CLD, IMP, 2); single(0xF8, SED, IMP, 2)
            single(0x88, DEY, IMP, 2); single(0xCA, DEX, IMP, 2); single(0xC8, INY, IMP, 2); single(0xE8, INX, IMP, 2)
            single(0xAA, TAX, IMP, 2); single(0xA8, TAY, IMP, 2); single(0x8A, TXA, IMP, 2); single(0x98, TYA, IMP, 2)
            single(0xBA, TSX, IMP, 2); single(0x9A, TXS, IMP, 2); single(0xEA, NOP, IMP, 2)

            // Unofficial NOPs
            for (c in intArrayOf(0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA)) single(c, NOP, IMP, 2)
            group(NOP, KIND_READ, 0x80, IMM, 0x82, IMM, 0x89, IMM, 0xC2, IMM, 0xE2, IMM)
            group(NOP, KIND_READ, 0x04, ZP, 0x44, ZP, 0x64, ZP, 0x0C, ABS)
            for (c in intArrayOf(0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4)) group(NOP, KIND_READ, c, ZPX)
            for (c in intArrayOf(0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC)) group(NOP, KIND_READ, c, ABX)

            group(LAX, KIND_READ, 0xA7, ZP, 0xB7, ZPY, 0xAF, ABS, 0xBF, ABY, 0xA3, IZX, 0xB3, IZY, 0xAB, IMM)
            group(SAX, KIND_WRITE, 0x87, ZP, 0x97, ZPY, 0x8F, ABS, 0x83, IZX)
            fun rmwSet(ins: Int, base: Int) = group(
                ins, KIND_RMW, base + 0x07, ZP, base + 0x17, ZPX, base + 0x0F, ABS, base + 0x1F, ABX,
                base + 0x1B, ABY, base + 0x03, IZX, base + 0x13, IZY
            )
            rmwSet(SLO, 0x00); rmwSet(RLA, 0x20); rmwSet(SRE, 0x40); rmwSet(RRA, 0x60)
            rmwSet(DCP, 0xC0); rmwSet(ISB, 0xE0)
            group(ANC, KIND_READ, 0x0B, IMM, 0x2B, IMM)
            group(ALR, KIND_READ, 0x4B, IMM)
            group(ARR, KIND_READ, 0x6B, IMM)
            group(AXS, KIND_READ, 0xCB, IMM)
            group(SHY, KIND_WRITE, 0x9C, ABX)
            group(SHX, KIND_WRITE, 0x9E, ABY)
            group(LAS, KIND_READ, 0xBB, ABY)
            group(SHA, KIND_WRITE, 0x9F, ABY, 0x93, IZY)
            group(TAS, KIND_WRITE, 0x9B, ABY)
            group(XAA, KIND_READ, 0x8B, IMM)
        }
    }
}

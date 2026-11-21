package com.nesemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

private val LENGTH_TABLE = intArrayOf(
    10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
    12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
)
private val DUTY_TABLE = arrayOf(
    intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),
    intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),
    intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
    intArrayOf(1, 0, 0, 1, 1, 1, 1, 1)
)
private val TRIANGLE_TABLE = intArrayOf(
    15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
)
private val NOISE_TABLE = intArrayOf(4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068)
private val DMC_TABLE = intArrayOf(428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54)

private class Pulse(private val channel: Int) {
    var enabled = false
    var lengthEnabled = false; var lengthValue = 0
    var timerPeriod = 0; var timerValue = 0
    var dutyMode = 0; var dutyValue = 0
    var sweepReload = false; var sweepEnabled = false; var sweepNegate = false
    var sweepShift = 0; var sweepPeriod = 0; var sweepValue = 0
    var envelopeEnabled = false; var envelopeLoop = false; var envelopeStart = false
    var envelopePeriod = 0; var envelopeValue = 0; var envelopeVolume = 0
    var constantVolume = 0

    fun writeControl(v: Int) {
        dutyMode = (v shr 6) and 3
        lengthEnabled = v and 0x20 == 0
        envelopeLoop = v and 0x20 != 0
        envelopeEnabled = v and 0x10 == 0
        envelopePeriod = v and 0x0F
        constantVolume = v and 0x0F
        envelopeStart = true
    }

    fun writeSweep(v: Int) {
        sweepEnabled = v and 0x80 != 0
        sweepPeriod = ((v shr 4) and 7) + 1
        sweepNegate = v and 0x08 != 0
        sweepShift = v and 7
        sweepReload = true
    }

    fun writeTimerLow(v: Int) { timerPeriod = (timerPeriod and 0xFF00) or v }

    fun writeTimerHigh(v: Int) {
        if (enabled) lengthValue = LENGTH_TABLE[v shr 3]
        timerPeriod = (timerPeriod and 0x00FF) or ((v and 7) shl 8)
        envelopeStart = true
        dutyValue = 0
    }

    fun stepTimer() {
        if (timerValue == 0) {
            timerValue = timerPeriod
            dutyValue = (dutyValue + 1) and 7
        } else timerValue--
    }

    fun stepEnvelope() {
        if (envelopeStart) {
            envelopeVolume = 15
            envelopeValue = envelopePeriod
            envelopeStart = false
        } else if (envelopeValue > 0) {
            envelopeValue--
        } else {
            if (envelopeVolume > 0) envelopeVolume--
            else if (envelopeLoop) envelopeVolume = 15
            envelopeValue = envelopePeriod
        }
    }

    private fun sweepTarget(): Int {
        val delta = timerPeriod shr sweepShift
        return if (sweepNegate) timerPeriod - delta - (if (channel == 1) 1 else 0) else timerPeriod + delta
    }

    fun stepSweep() {
        if (sweepValue == 0 && sweepEnabled && sweepShift > 0 && timerPeriod >= 8) {
            val target = sweepTarget()
            if (target in 0..0x7FF) timerPeriod = target
        }
        if (sweepValue == 0 || sweepReload) {
            sweepValue = sweepPeriod
            sweepReload = false
        } else sweepValue--
    }

    fun stepLength() { if (lengthEnabled && lengthValue > 0) lengthValue-- }

    fun output(): Int {
        if (!enabled || lengthValue == 0 || DUTY_TABLE[dutyMode][dutyValue] == 0) return 0
        if (timerPeriod < 8 || sweepTarget() > 0x7FF) return 0
        return if (envelopeEnabled) envelopeVolume else constantVolume
    }

    fun save(o: DataOutputStream) {
        for (b in booleanArrayOf(enabled, lengthEnabled, sweepReload, sweepEnabled, sweepNegate, envelopeEnabled, envelopeLoop, envelopeStart)) o.writeBoolean(b)
        for (i in intArrayOf(lengthValue, timerPeriod, timerValue, dutyMode, dutyValue, sweepShift, sweepPeriod, sweepValue, envelopePeriod, envelopeValue, envelopeVolume, constantVolume)) o.writeInt(i)
    }

    fun load(i: DataInputStream) {
        enabled = i.readBoolean(); lengthEnabled = i.readBoolean(); sweepReload = i.readBoolean(); sweepEnabled = i.readBoolean()
        sweepNegate = i.readBoolean(); envelopeEnabled = i.readBoolean(); envelopeLoop = i.readBoolean(); envelopeStart = i.readBoolean()
        lengthValue = i.readInt(); timerPeriod = i.readInt(); timerValue = i.readInt(); dutyMode = i.readInt(); dutyValue = i.readInt()
        sweepShift = i.readInt(); sweepPeriod = i.readInt(); sweepValue = i.readInt(); envelopePeriod = i.readInt()
        envelopeValue = i.readInt(); envelopeVolume = i.readInt(); constantVolume = i.readInt()
    }
}

private class Triangle {
    var enabled = false
    var lengthEnabled = false; var lengthValue = 0
    var timerPeriod = 0; var timerValue = 0
    var dutyValue = 0
    var counterPeriod = 0; var counterValue = 0; var counterReload = false

    fun writeControl(v: Int) {
        lengthEnabled = v and 0x80 == 0
        counterPeriod = v and 0x7F
    }

    fun writeTimerLow(v: Int) { timerPeriod = (timerPeriod and 0xFF00) or v }

    fun writeTimerHigh(v: Int) {
        if (enabled) lengthValue = LENGTH_TABLE[v shr 3]
        timerPeriod = (timerPeriod and 0x00FF) or ((v and 7) shl 8)
        timerValue = timerPeriod
        counterReload = true
    }

    fun stepTimer() {
        if (timerValue == 0) {
            timerValue = timerPeriod
            if (lengthValue > 0 && counterValue > 0) dutyValue = (dutyValue + 1) and 31
        } else timerValue--
    }

    fun stepLength() { if (lengthEnabled && lengthValue > 0) lengthValue-- }

    fun stepCounter() {
        if (counterReload) counterValue = counterPeriod
        else if (counterValue > 0) counterValue--
        if (lengthEnabled) counterReload = false
    }

    fun output(): Int {
        if (!enabled || lengthValue == 0 || counterValue == 0) return 0
        if (timerPeriod < 2) return 7 // ultrasonic: avoid popping
        return TRIANGLE_TABLE[dutyValue]
    }

    fun save(o: DataOutputStream) {
        o.writeBoolean(enabled); o.writeBoolean(lengthEnabled); o.writeBoolean(counterReload)
        for (i in intArrayOf(lengthValue, timerPeriod, timerValue, dutyValue, counterPeriod, counterValue)) o.writeInt(i)
    }

    fun load(i: DataInputStream) {
        enabled = i.readBoolean(); lengthEnabled = i.readBoolean(); counterReload = i.readBoolean()
        lengthValue = i.readInt(); timerPeriod = i.readInt(); timerValue = i.readInt(); dutyValue = i.readInt()
        counterPeriod = i.readInt(); counterValue = i.readInt()
    }
}

private class Noise {
    var enabled = false
    var mode = false
    var shiftRegister = 1
    var lengthEnabled = false; var lengthValue = 0
    var timerPeriod = 0; var timerValue = 0
    var envelopeEnabled = false; var envelopeLoop = false; var envelopeStart = false
    var envelopePeriod = 0; var envelopeValue = 0; var envelopeVolume = 0
    var constantVolume = 0

    fun writeControl(v: Int) {
        lengthEnabled = v and 0x20 == 0
        envelopeLoop = v and 0x20 != 0
        envelopeEnabled = v and 0x10 == 0
        envelopePeriod = v and 0x0F
        constantVolume = v and 0x0F
        envelopeStart = true
    }

    fun writePeriod(v: Int) {
        mode = v and 0x80 != 0
        timerPeriod = NOISE_TABLE[v and 0x0F]
    }

    fun writeLength(v: Int) {
        if (enabled) lengthValue = LENGTH_TABLE[v shr 3]
        envelopeStart = true
    }

    fun stepTimer() {
        if (timerValue == 0) {
            timerValue = timerPeriod
            val shift = if (mode) 6 else 1
            val b1 = shiftRegister and 1
            val b2 = (shiftRegister shr shift) and 1
            shiftRegister = (shiftRegister shr 1) or ((b1 xor b2) shl 14)
        } else timerValue--
    }

    fun stepEnvelope() {
        if (envelopeStart) {
            envelopeVolume = 15
            envelopeValue = envelopePeriod
            envelopeStart = false
        } else if (envelopeValue > 0) {
            envelopeValue--
        } else {
            if (envelopeVolume > 0) envelopeVolume--
            else if (envelopeLoop) envelopeVolume = 15
            envelopeValue = envelopePeriod
        }
    }

    fun stepLength() { if (lengthEnabled && lengthValue > 0) lengthValue-- }

    fun output(): Int {
        if (!enabled || lengthValue == 0 || shiftRegister and 1 == 1) return 0
        return if (envelopeEnabled) envelopeVolume else constantVolume
    }

    fun save(o: DataOutputStream) {
        for (b in booleanArrayOf(enabled, mode, lengthEnabled, envelopeEnabled, envelopeLoop, envelopeStart)) o.writeBoolean(b)
        for (i in intArrayOf(shiftRegister, lengthValue, timerPeriod, timerValue, envelopePeriod, envelopeValue, envelopeVolume, constantVolume)) o.writeInt(i)
    }

    fun load(i: DataInputStream) {
        enabled = i.readBoolean(); mode = i.readBoolean(); lengthEnabled = i.readBoolean(); envelopeEnabled = i.readBoolean()
        envelopeLoop = i.readBoolean(); envelopeStart = i.readBoolean()
        shiftRegister = i.readInt(); lengthValue = i.readInt(); timerPeriod = i.readInt(); timerValue = i.readInt()
        envelopePeriod = i.readInt(); envelopeValue = i.readInt(); envelopeVolume = i.readInt(); constantVolume = i.readInt()
    }
}

private class Dmc(private val memRead: (Int) -> Int, private val stallCpu: (Int) -> Unit) {
    var enabled = false
    var value = 0
    var sampleAddress = 0; var sampleLength = 0
    var currentAddress = 0; var currentLength = 0
    var shiftRegister = 0; var bitCount = 0
    var tickPeriod = 0; var tickValue = 0
    var loop = false; var irqEnabled = false; var irq = false

    fun writeControl(v: Int) {
        irqEnabled = v and 0x80 != 0
        if (!irqEnabled) irq = false
        loop = v and 0x40 != 0
        tickPeriod = DMC_TABLE[v and 0x0F]
    }

    fun writeValue(v: Int) { value = v and 0x7F }
    fun writeAddress(v: Int) { sampleAddress = 0xC000 or (v shl 6) }
    fun writeLength(v: Int) { sampleLength = (v shl 4) or 1 }

    fun restart() {
        currentAddress = sampleAddress
        currentLength = sampleLength
    }

    fun stepTimer() {
        if (!enabled) return
        stepReader()
        if (tickValue == 0) {
            tickValue = tickPeriod
            stepShifter()
        } else tickValue--
    }

    private fun stepReader() {
        if (currentLength > 0 && bitCount == 0) {
            stallCpu(4)
            shiftRegister = memRead(currentAddress)
            bitCount = 8
            currentAddress = if (currentAddress == 0xFFFF) 0x8000 else currentAddress + 1
            currentLength--
            if (currentLength == 0) {
                if (loop) restart() else if (irqEnabled) irq = true
            }
        }
    }

    private fun stepShifter() {
        if (bitCount == 0) return
        if (shiftRegister and 1 == 1) { if (value <= 125) value += 2 }
        else { if (value >= 2) value -= 2 }
        shiftRegister = shiftRegister shr 1
        bitCount--
    }

    fun save(o: DataOutputStream) {
        for (b in booleanArrayOf(enabled, loop, irqEnabled, irq)) o.writeBoolean(b)
        for (i in intArrayOf(value, sampleAddress, sampleLength, currentAddress, currentLength, shiftRegister, bitCount, tickPeriod, tickValue)) o.writeInt(i)
    }

    fun load(i: DataInputStream) {
        enabled = i.readBoolean(); loop = i.readBoolean(); irqEnabled = i.readBoolean(); irq = i.readBoolean()
        value = i.readInt(); sampleAddress = i.readInt(); sampleLength = i.readInt(); currentAddress = i.readInt()
        currentLength = i.readInt(); shiftRegister = i.readInt(); bitCount = i.readInt(); tickPeriod = i.readInt(); tickValue = i.readInt()
    }
}

/** 2A03 APU: two pulse channels, triangle, noise and DMC, mixed to 16-bit mono PCM. */
class Apu(memRead: (Int) -> Int, stallCpu: (Int) -> Unit, private val sampleRate: Int = 44100) : Stateful {
    private val pulse1 = Pulse(1)
    private val pulse2 = Pulse(2)
    private val triangle = Triangle()
    private val noise = Noise()
    private val dmc = Dmc(memRead, stallCpu)

    private var cycle = 0L
    private var frameStep = 0
    private var frameCounterCycle = 0
    private var frameMode5 = false
    private var irqInhibit = false
    var frameIrq = false
        private set

    // Output resampling (box filter) and DC-blocking high pass.
    private val cyclesPerSample = 1789773.0 / sampleRate
    private var sampleClock = 0.0
    private var accum = 0.0
    private var accumCount = 0
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    private var lpPrev = 0.0

    /** Samples produced since the last [drainSamples]. */
    private var buffer = ShortArray(4096)
    var bufferedSamples = 0
        private set

    val irq: Boolean get() = frameIrq || dmc.irq

    fun drainSamples(dest: ShortArray): Int {
        val n = minOf(bufferedSamples, dest.size)
        System.arraycopy(buffer, 0, dest, 0, n)
        bufferedSamples = 0
        return n
    }

    fun readStatus(): Int {
        var r = 0
        if (pulse1.lengthValue > 0) r = r or 1
        if (pulse2.lengthValue > 0) r = r or 2
        if (triangle.lengthValue > 0) r = r or 4
        if (noise.lengthValue > 0) r = r or 8
        if (dmc.currentLength > 0) r = r or 16
        if (frameIrq) r = r or 0x40
        if (dmc.irq) r = r or 0x80
        frameIrq = false
        return r
    }

    fun writeRegister(addr: Int, v: Int) {
        when (addr) {
            0x4000 -> pulse1.writeControl(v)
            0x4001 -> pulse1.writeSweep(v)
            0x4002 -> pulse1.writeTimerLow(v)
            0x4003 -> pulse1.writeTimerHigh(v)
            0x4004 -> pulse2.writeControl(v)
            0x4005 -> pulse2.writeSweep(v)
            0x4006 -> pulse2.writeTimerLow(v)
            0x4007 -> pulse2.writeTimerHigh(v)
            0x4008 -> triangle.writeControl(v)
            0x400A -> triangle.writeTimerLow(v)
            0x400B -> triangle.writeTimerHigh(v)
            0x400C -> noise.writeControl(v)
            0x400E -> noise.writePeriod(v)
            0x400F -> noise.writeLength(v)
            0x4010 -> dmc.writeControl(v)
            0x4011 -> dmc.writeValue(v)
            0x4012 -> dmc.writeAddress(v)
            0x4013 -> dmc.writeLength(v)
            0x4015 -> {
                pulse1.enabled = v and 1 != 0
                pulse2.enabled = v and 2 != 0
                triangle.enabled = v and 4 != 0
                noise.enabled = v and 8 != 0
                dmc.enabled = v and 16 != 0
                if (!pulse1.enabled) pulse1.lengthValue = 0
                if (!pulse2.enabled) pulse2.lengthValue = 0
                if (!triangle.enabled) triangle.lengthValue = 0
                if (!noise.enabled) noise.lengthValue = 0
                if (!dmc.enabled) dmc.currentLength = 0
                else if (dmc.currentLength == 0) dmc.restart()
                dmc.irq = false
            }
            0x4017 -> {
                frameMode5 = v and 0x80 != 0
                irqInhibit = v and 0x40 != 0
                if (irqInhibit) frameIrq = false
                frameCounterCycle = 0
                frameStep = 0
                if (frameMode5) { quarterFrame(); halfFrame() }
            }
        }
    }

    private fun quarterFrame() {
        pulse1.stepEnvelope(); pulse2.stepEnvelope(); triangle.stepCounter(); noise.stepEnvelope()
    }

    private fun halfFrame() {
        pulse1.stepSweep(); pulse2.stepSweep()
        pulse1.stepLength(); pulse2.stepLength(); triangle.stepLength(); noise.stepLength()
    }

    private fun stepFrameCounter() {
        frameCounterCycle++
        // NTSC step points in CPU cycles.
        when (frameCounterCycle) {
            7457 -> quarterFrame()
            14913 -> { quarterFrame(); halfFrame() }
            22371 -> quarterFrame()
            29829 -> if (!frameMode5) {
                quarterFrame(); halfFrame()
                if (!irqInhibit) frameIrq = true
            }
            29830 -> if (!frameMode5) frameCounterCycle = 0
            37281 -> { quarterFrame(); halfFrame() }
            37282 -> frameCounterCycle = 0
        }
    }

    /** Advance one CPU cycle. */
    fun step() {
        cycle++
        if (cycle and 1L == 0L) {
            pulse1.stepTimer(); pulse2.stepTimer(); noise.stepTimer(); dmc.stepTimer()
        }
        triangle.stepTimer()
        stepFrameCounter()

        accum += mix()
        accumCount++
        sampleClock += 1.0
        if (sampleClock >= cyclesPerSample) {
            sampleClock -= cyclesPerSample
            emit(accum / accumCount)
            accum = 0.0
            accumCount = 0
        }
    }

    private fun mix(): Double {
        val p = pulse1.output() + pulse2.output()
        val tnd = 3 * triangle.output() + 2 * noise.output() + dmc.value
        return PULSE_MIX[p] + TND_MIX[tnd]
    }

    private fun emit(sample: Double) {
        // High-pass (~37 Hz) to remove DC offset, then a gentle low-pass (~14 kHz).
        val hp = 0.9947 * (hpPrevOut + sample - hpPrevIn)
        hpPrevIn = sample
        hpPrevOut = hp
        lpPrev += (hp - lpPrev) * 0.815
        val s = (lpPrev * 30000.0).toInt().coerceIn(-32768, 32767)
        if (bufferedSamples < buffer.size) buffer[bufferedSamples++] = s.toShort()
    }

    override fun save(out: DataOutputStream) {
        pulse1.save(out); pulse2.save(out); triangle.save(out); noise.save(out); dmc.save(out)
        out.writeLong(cycle); out.writeInt(frameStep); out.writeInt(frameCounterCycle)
        out.writeBoolean(frameMode5); out.writeBoolean(irqInhibit); out.writeBoolean(frameIrq)
    }

    override fun load(inp: DataInputStream) {
        pulse1.load(inp); pulse2.load(inp); triangle.load(inp); noise.load(inp); dmc.load(inp)
        cycle = inp.readLong(); frameStep = inp.readInt(); frameCounterCycle = inp.readInt()
        frameMode5 = inp.readBoolean(); irqInhibit = inp.readBoolean(); frameIrq = inp.readBoolean()
        bufferedSamples = 0
    }

    companion object {
        private val PULSE_MIX = DoubleArray(31) { if (it == 0) 0.0 else 95.52 / (8128.0 / it + 100) }
        private val TND_MIX = DoubleArray(203) { if (it == 0) 0.0 else 163.67 / (24329.0 / it + 100) }
    }
}

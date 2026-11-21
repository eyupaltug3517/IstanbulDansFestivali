package com.nesemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Dot-based 2C02 PPU (NTSC). */
class Ppu(private val mapper: Mapper, private val onNmi: () -> Unit) : Stateful {
    /** Completed frame in ARGB, 256x240. */
    val frame = IntArray(256 * 240)
    var frameComplete = false

    private val vram = ByteArray(0x1000) // 4 KB so four-screen carts work
    private val paletteRam = IntArray(32)
    val oam = IntArray(256)

    var cycle = 0
    var scanline = 261
    var frameCount = 0L
    private var dot = 0L

    // Loopy registers
    private var v = 0; private var t = 0; private var fineX = 0; private var w = 0
    private var oddFrame = false
    private var openBus = 0
    private var readBuffer = 0

    // $2000
    private var ctrlNametable = 0
    private var ctrlIncrement = 1
    private var ctrlSpriteTable = 0
    private var ctrlBgTable = 0
    private var ctrlSpriteSize = 8
    private var ctrlNmi = false
    // $2001
    private var mask = 0
    private val showBgLeft get() = mask and 0x02 != 0
    private val showSpritesLeft get() = mask and 0x04 != 0
    private val showBg get() = mask and 0x08 != 0
    private val showSprites get() = mask and 0x10 != 0
    // $2002
    private var vblank = false
    private var sprite0Hit = false
    private var spriteOverflow = false
    private var oamAddr = 0

    // Background pipeline
    private var ntByte = 0; private var atByte = 0; private var lowTile = 0; private var highTile = 0
    private var tileData = 0L

    // Sprites for the current line
    private var spriteCount = 0
    private val spritePatterns = IntArray(8)
    private val spritePositions = IntArray(8)
    private val spritePriorities = IntArray(8)
    private val spriteIndexes = IntArray(8)

    fun reset() {
        cycle = 0; scanline = 261; v = 0; t = 0; w = 0; fineX = 0
        ctrlNmi = false; mask = 0; oamAddr = 0
    }

    // ---- PPU memory ----

    private fun nametableIndex(addr: Int): Int {
        val a = (addr - 0x2000) and 0x0FFF
        val table = a shr 10
        val offset = a and 0x3FF
        return when (mapper.mirroring) {
            Mirroring.HORIZONTAL -> ((table shr 1) shl 10) or offset
            Mirroring.VERTICAL -> ((table and 1) shl 10) or offset
            Mirroring.SINGLE0 -> offset
            Mirroring.SINGLE1 -> 0x400 or offset
            Mirroring.FOUR_SCREEN -> a
        }
    }

    private fun paletteIndex(addr: Int): Int {
        var i = addr and 0x1F
        if (i >= 16 && i and 3 == 0) i -= 16
        return i
    }

    private fun read(addr: Int): Int {
        val a = addr and 0x3FFF
        return when {
            a < 0x2000 -> mapper.ppuRead(a)
            a < 0x3F00 -> vram[nametableIndex(a)].toInt() and 0xFF
            else -> paletteRam[paletteIndex(a)]
        }
    }

    /** Side-effect free read of PPU memory, for tools and debugging. */
    fun debugRead(addr: Int): Int = read(addr)

    private fun write(addr: Int, value: Int) {
        val a = addr and 0x3FFF
        when {
            a < 0x2000 -> mapper.ppuWrite(a, value)
            a < 0x3F00 -> vram[nametableIndex(a)] = value.toByte()
            else -> paletteRam[paletteIndex(a)] = value and 0x3F
        }
    }

    // ---- CPU-facing registers ----

    fun readRegister(reg: Int): Int {
        when (reg) {
            2 -> {
                var r = openBus and 0x1F
                if (vblank) r = r or 0x80
                if (sprite0Hit) r = r or 0x40
                if (spriteOverflow) r = r or 0x20
                vblank = false
                w = 0
                openBus = r
                return r
            }
            4 -> { openBus = oam[oamAddr]; return openBus }
            7 -> {
                mapper.ppuAddress(v and 0x3FFF, dot)
                var value = read(v)
                if (v and 0x3FFF < 0x3F00) {
                    val buffered = readBuffer
                    readBuffer = value
                    value = buffered
                } else {
                    readBuffer = read(v - 0x1000)
                    value = value or (openBus and 0xC0)
                }
                v = (v + ctrlIncrement) and 0x7FFF
                mapper.ppuAddress(v and 0x3FFF, dot)
                openBus = value
                return value
            }
        }
        return openBus
    }

    fun writeRegister(reg: Int, value: Int) {
        openBus = value
        when (reg) {
            0 -> {
                ctrlNametable = value and 3
                ctrlIncrement = if (value and 0x04 != 0) 32 else 1
                ctrlSpriteTable = if (value and 0x08 != 0) 0x1000 else 0
                ctrlBgTable = if (value and 0x10 != 0) 0x1000 else 0
                ctrlSpriteSize = if (value and 0x20 != 0) 16 else 8
                val wasNmi = ctrlNmi
                ctrlNmi = value and 0x80 != 0
                if (!wasNmi && ctrlNmi && vblank) onNmi()
                t = (t and 0xF3FF) or ((value and 3) shl 10)
            }
            1 -> mask = value
            3 -> oamAddr = value
            4 -> { oam[oamAddr] = value; oamAddr = (oamAddr + 1) and 0xFF }
            5 -> if (w == 0) {
                t = (t and 0xFFE0) or (value shr 3)
                fineX = value and 7
                w = 1
            } else {
                t = (t and 0x8FFF) or ((value and 0x07) shl 12)
                t = (t and 0xFC1F) or ((value and 0xF8) shl 2)
                w = 0
            }
            6 -> if (w == 0) {
                t = (t and 0x80FF) or ((value and 0x3F) shl 8)
                w = 1
            } else {
                t = (t and 0xFF00) or value
                v = t
                w = 0
                mapper.ppuAddress(v and 0x3FFF, dot)
            }
            7 -> {
                mapper.ppuAddress(v and 0x3FFF, dot)
                write(v, value)
                v = (v + ctrlIncrement) and 0x7FFF
                mapper.ppuAddress(v and 0x3FFF, dot)
            }
        }
    }

    fun writeDma(data: IntArray) {
        for (i in 0 until 256) {
            oam[oamAddr] = data[i]
            oamAddr = (oamAddr + 1) and 0xFF
        }
    }

    // ---- Scrolling helpers ----

    private fun incrementX() {
        if (v and 0x001F == 31) {
            v = v and 0x001F.inv()
            v = v xor 0x0400
        } else v++
    }

    private fun incrementY() {
        if (v and 0x7000 != 0x7000) {
            v += 0x1000
        } else {
            v = v and 0x7000.inv()
            var y = (v and 0x03E0) shr 5
            when (y) {
                29 -> { y = 0; v = v xor 0x0800 }
                31 -> y = 0
                else -> y++
            }
            v = (v and 0x03E0.inv()) or (y shl 5)
        }
    }

    private fun copyX() { v = (v and 0xFBE0) or (t and 0x041F) }
    private fun copyY() { v = (v and 0x841F) or (t and 0x7BE0) }

    // ---- Background fetches ----

    private fun fetchNametable() { ntByte = read(0x2000 or (v and 0x0FFF)) }

    private fun fetchAttribute() {
        val addr = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
        val shift = ((v shr 4) and 4) or (v and 2)
        atByte = ((read(addr) shr shift) and 3) shl 2
    }

    private fun fetchLow() {
        val fineY = (v shr 12) and 7
        val addr = ctrlBgTable + ntByte * 16 + fineY
        mapper.ppuAddress(addr, dot)
        lowTile = read(addr)
    }

    private fun fetchHigh() {
        val fineY = (v shr 12) and 7
        val addr = ctrlBgTable + ntByte * 16 + fineY + 8
        mapper.ppuAddress(addr, dot)
        highTile = read(addr)
    }

    private fun storeTileData() {
        var data = 0L
        var lo = lowTile; var hi = highTile
        for (i in 0 until 8) {
            val p1 = (lo and 0x80) shr 7
            val p2 = (hi and 0x80) shr 6
            lo = lo shl 1; hi = hi shl 1
            data = (data shl 4) or (atByte or p1 or p2).toLong()
        }
        tileData = tileData or data
    }

    private fun backgroundPixel(): Int {
        if (!showBg) return 0
        val data = (tileData ushr 32).toInt()
        return (data ushr ((7 - fineX) * 4)) and 0x0F
    }

    // ---- Sprites ----

    private fun fetchSpritePattern(i: Int, row0: Int): Int {
        var row = row0
        var tile = oam[i * 4 + 1]
        val attr = oam[i * 4 + 2]
        val addr: Int
        if (ctrlSpriteSize == 8) {
            if (attr and 0x80 != 0) row = 7 - row
            addr = ctrlSpriteTable + tile * 16 + row
        } else {
            if (attr and 0x80 != 0) row = 15 - row
            val table = (tile and 1) * 0x1000
            tile = tile and 0xFE
            if (row > 7) { tile++; row -= 8 }
            addr = table + tile * 16 + row
        }
        val a = (attr and 3) shl 2
        mapper.ppuAddress(addr, dot)
        var lo = read(addr)
        var hi = read(addr + 8)
        var data = 0
        for (k in 0 until 8) {
            val p1: Int; val p2: Int
            if (attr and 0x40 != 0) {
                p1 = lo and 1; p2 = (hi and 1) shl 1
                lo = lo shr 1; hi = hi shr 1
            } else {
                p1 = (lo and 0x80) shr 7; p2 = (hi and 0x80) shr 6
                lo = lo shl 1; hi = hi shl 1
            }
            data = (data shl 4) or a or p1 or p2
        }
        return data
    }

    private fun evaluateSprites() {
        var count = 0
        for (i in 0 until 64) {
            val y = oam[i * 4]
            val row = scanline - y
            if (row < 0 || row >= ctrlSpriteSize) continue
            if (count < 8) {
                spritePatterns[count] = fetchSpritePattern(i, row)
                spritePositions[count] = oam[i * 4 + 3]
                spritePriorities[count] = (oam[i * 4 + 2] shr 5) and 1
                spriteIndexes[count] = i
            }
            count++
        }
        if (count > 8) {
            count = 8
            spriteOverflow = true
        }
        spriteCount = count
        // Unused sprite slots still fetch tile $FF; mappers like MMC3 watch these fetches.
        if (count < 8) {
            mapper.ppuAddress(if (ctrlSpriteSize == 16) 0x1FF0 else ctrlSpriteTable + 0xFF0, dot)
        }
    }

    /** Returns (index shl 8) or color, color 0 meaning transparent. */
    private fun spritePixel(): Int {
        if (!showSprites) return 0
        val x = cycle - 1
        for (i in 0 until spriteCount) {
            var offset = x - spritePositions[i]
            if (offset < 0 || offset > 7) continue
            offset = 7 - offset
            val color = (spritePatterns[i] ushr (offset * 4)) and 0x0F
            if (color and 3 == 0) continue
            return (i shl 8) or color
        }
        return 0
    }

    private fun renderPixel() {
        val x = cycle - 1
        val y = scanline
        var bg = backgroundPixel()
        var sp = spritePixel()
        if (x < 8 && !showBgLeft) bg = 0
        if (x < 8 && !showSpritesLeft) sp = 0
        val spColor = sp and 0xFF
        val i = sp shr 8
        val b = bg and 3 != 0
        val s = spColor and 3 != 0
        val color = if (!b && !s) 0
        else if (!b) spColor or 0x10
        else if (!s) bg
        else {
            if (spriteIndexes[i] == 0 && x < 255) sprite0Hit = true
            if (spritePriorities[i] == 0) spColor or 0x10 else bg
        }
        var c = read(0x3F00 + color) and 0x3F
        if (mask and 0x01 != 0) c = c and 0x30
        frame[y * 256 + x] = PALETTE[c]
    }

    // ---- Timing ----

    private fun tick() {
        val rendering = showBg || showSprites
        if (rendering && oddFrame && scanline == 261 && cycle == 339) {
            cycle = 0
            scanline = 0
            frameCount++
            oddFrame = !oddFrame
            return
        }
        cycle++
        if (cycle > 340) {
            cycle = 0
            scanline++
            if (scanline > 261) {
                scanline = 0
                frameCount++
                oddFrame = !oddFrame
            }
        }
    }

    fun step() {
        dot++
        tick()
        val rendering = showBg || showSprites
        val preLine = scanline == 261
        val visibleLine = scanline < 240
        val renderLine = preLine || visibleLine
        val preFetchCycle = cycle in 321..336
        val visibleCycle = cycle in 1..256
        val fetchCycle = preFetchCycle || visibleCycle

        if (rendering) {
            if (visibleLine && visibleCycle) renderPixel()
            if (renderLine && fetchCycle) {
                tileData = tileData shl 4
                when (cycle and 7) {
                    1 -> fetchNametable()
                    3 -> fetchAttribute()
                    5 -> fetchLow()
                    7 -> fetchHigh()
                    0 -> storeTileData()
                }
            }
            if (preLine && cycle in 280..304) copyY()
            if (renderLine) {
                if (fetchCycle && cycle and 7 == 0) incrementX()
                if (cycle == 256) incrementY()
                if (cycle == 257) copyX()
            }
            if (cycle == 257) {
                if (visibleLine) evaluateSprites()
                else {
                    spriteCount = 0
                    if (preLine) mapper.ppuAddress(if (ctrlSpriteSize == 16) 0x1FF0 else ctrlSpriteTable + 0xFF0, dot)
                }
            }
        } else if (visibleLine && visibleCycle) {
            // Rendering disabled: show the backdrop color.
            val c = if (v and 0x3F00 == 0x3F00) read(v) else paletteRam[0]
            frame[scanline * 256 + cycle - 1] = PALETTE[c and 0x3F]
        }

        if (scanline == 241 && cycle == 1) {
            vblank = true
            if (ctrlNmi) onNmi()
            frameComplete = true
        }
        if (preLine && cycle == 1) {
            vblank = false
            sprite0Hit = false
            spriteOverflow = false
        }
    }

    override fun save(out: DataOutputStream) {
        out.writeBytes(vram); out.writeInts(paletteRam); out.writeInts(oam)
        for (value in intArrayOf(
            cycle, scanline, v, t, fineX, w, openBus, readBuffer, ctrlNametable, ctrlIncrement,
            ctrlSpriteTable, ctrlBgTable, ctrlSpriteSize, mask, oamAddr, ntByte, atByte, lowTile, highTile, spriteCount
        )) out.writeInt(value)
        out.writeBoolean(oddFrame); out.writeBoolean(ctrlNmi); out.writeBoolean(vblank)
        out.writeBoolean(sprite0Hit); out.writeBoolean(spriteOverflow)
        out.writeLong(tileData); out.writeLong(frameCount); out.writeLong(dot)
        out.writeInts(spritePatterns); out.writeInts(spritePositions); out.writeInts(spritePriorities); out.writeInts(spriteIndexes)
    }

    override fun load(inp: DataInputStream) {
        inp.readBytesInto(vram); inp.readIntsInto(paletteRam); inp.readIntsInto(oam)
        cycle = inp.readInt(); scanline = inp.readInt(); v = inp.readInt(); t = inp.readInt(); fineX = inp.readInt()
        w = inp.readInt(); openBus = inp.readInt(); readBuffer = inp.readInt(); ctrlNametable = inp.readInt()
        ctrlIncrement = inp.readInt(); ctrlSpriteTable = inp.readInt(); ctrlBgTable = inp.readInt()
        ctrlSpriteSize = inp.readInt(); mask = inp.readInt(); oamAddr = inp.readInt(); ntByte = inp.readInt()
        atByte = inp.readInt(); lowTile = inp.readInt(); highTile = inp.readInt(); spriteCount = inp.readInt()
        oddFrame = inp.readBoolean(); ctrlNmi = inp.readBoolean(); vblank = inp.readBoolean()
        sprite0Hit = inp.readBoolean(); spriteOverflow = inp.readBoolean()
        tileData = inp.readLong(); frameCount = inp.readLong(); dot = inp.readLong()
        inp.readIntsInto(spritePatterns); inp.readIntsInto(spritePositions)
        inp.readIntsInto(spritePriorities); inp.readIntsInto(spriteIndexes)
    }

    companion object {
        /** 2C02 palette (ARGB). */
        val PALETTE = intArrayOf(
            0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
            0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
            0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
            0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
            0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
            0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
            0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
            0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
        ).map { it or 0xFF000000.toInt() }.toIntArray()
    }
}

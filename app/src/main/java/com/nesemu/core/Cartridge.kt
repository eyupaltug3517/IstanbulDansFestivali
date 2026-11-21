package com.nesemu.core

import java.util.zip.CRC32

enum class Mirroring { HORIZONTAL, VERTICAL, SINGLE0, SINGLE1, FOUR_SCREEN }

class RomException(message: String) : Exception(message)

/** Parsed iNES / NES 2.0 image. */
class Cartridge(data: ByteArray) {
    val prg: ByteArray
    val chr: ByteArray
    val chrIsRam: Boolean
    val mapperId: Int
    val mirroring: Mirroring
    val hasBattery: Boolean
    val prgRam = ByteArray(0x2000)
    val crc: Long

    init {
        if (data.size < 16 || data[0] != 'N'.code.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'S'.code.toByte() || data[3] != 0x1A.toByte()
        ) throw RomException("Geçerli bir .nes dosyası değil")

        val flags6 = data[6].toInt() and 0xFF
        val flags7 = data[7].toInt() and 0xFF
        val isNes2 = (flags7 and 0x0C) == 0x08
        var prgBanks = data[4].toInt() and 0xFF
        var chrBanks = data[5].toInt() and 0xFF
        var mapper = (flags6 shr 4) or (flags7 and 0xF0)
        if (isNes2) {
            prgBanks = prgBanks or ((data[9].toInt() and 0x0F) shl 8)
            chrBanks = chrBanks or ((data[9].toInt() and 0xF0) shl 4)
            mapper = mapper or ((data[8].toInt() and 0x0F) shl 8)
        } else if (data[12].toInt() != 0 || data[13].toInt() != 0 || data[14].toInt() != 0 || data[15].toInt() != 0) {
            // Old dumps with garbage ("DiskDude!") in bytes 7-15: ignore the upper nibble.
            mapper = flags6 shr 4
        }
        mapperId = mapper
        hasBattery = flags6 and 0x02 != 0
        mirroring = when {
            flags6 and 0x08 != 0 -> Mirroring.FOUR_SCREEN
            flags6 and 0x01 != 0 -> Mirroring.VERTICAL
            else -> Mirroring.HORIZONTAL
        }
        var offset = 16
        if (flags6 and 0x04 != 0) offset += 512 // trainer
        val prgSize = prgBanks * 0x4000
        val chrSize = chrBanks * 0x2000
        if (prgSize == 0 || data.size < offset + prgSize) throw RomException("ROM dosyası eksik ya da bozuk")
        prg = data.copyOfRange(offset, offset + prgSize)
        offset += prgSize
        if (chrSize == 0) {
            chr = ByteArray(0x2000)
            chrIsRam = true
        } else {
            val end = minOf(offset + chrSize, data.size)
            chr = ByteArray(chrSize)
            System.arraycopy(data, offset, chr, 0, end - offset)
            chrIsRam = false
        }
        crc = CRC32().apply { update(data, 16, data.size - 16) }.value
    }
}

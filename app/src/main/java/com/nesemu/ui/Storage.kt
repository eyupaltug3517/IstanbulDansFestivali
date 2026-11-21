package com.nesemu.ui

import android.content.Context
import java.io.File

/** App-private files: imported ROMs, battery saves and save-state slots. */
class Storage(context: Context) {
    val romDir = File(context.filesDir, "roms").apply { mkdirs() }
    private val saveDir = File(context.filesDir, "saves").apply { mkdirs() }

    fun roms(): List<File> =
        romDir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

    private fun gameDir(crc: Long) = File(saveDir, "%08x".format(crc)).apply { mkdirs() }

    fun batteryFile(crc: Long) = File(gameDir(crc), "battery.sav")

    fun slotFile(crc: Long, slot: Int) = File(gameDir(crc), "slot$slot.state")

    /** Writes atomically so a crash never leaves a half-written file. */
    fun writeAtomic(file: File, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun deleteRom(rom: File) {
        rom.delete()
    }

    companion object {
        const val SLOT_COUNT = 4
    }
}

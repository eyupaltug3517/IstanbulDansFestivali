package com.nesemu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * App-private files. Per game (keyed by the ROM file name) we keep:
 * battery save, auto-resume state, 4 save slots with screenshots, a cover
 * screenshot, cheat list and play statistics.
 */
class Storage(context: Context) {
    private val legacySaves = File(context.filesDir, "saves")
    val romDir = File(context.filesDir, "roms").apply { mkdirs() }
    private val gamesDir = File(context.filesDir, "games").apply { mkdirs() }
    private val meta = context.getSharedPreferences("games", Context.MODE_PRIVATE)

    fun roms(): List<File> = romDir.listFiles { f -> f.isFile }?.toList().orEmpty()

    fun gameDir(rom: File) = File(gamesDir, rom.name).apply { mkdirs() }

    fun batteryFile(rom: File) = File(gameDir(rom), "battery.sav")
    fun autoStateFile(rom: File) = File(gameDir(rom), "auto.state")
    fun coverFile(rom: File) = File(gameDir(rom), "cover.png")
    fun slotFile(rom: File, slot: Int) = File(gameDir(rom), "slot$slot.state")
    fun slotThumb(rom: File, slot: Int) = File(gameDir(rom), "slot$slot.png")
    fun cheatsFile(rom: File) = File(gameDir(rom), "cheats.txt")

    // ---- Statistics & favorites ----

    fun lastPlayed(rom: File) = meta.getLong("last:${rom.name}", 0L)
    fun playSeconds(rom: File) = meta.getLong("time:${rom.name}", 0L)
    fun isFavorite(rom: File) = meta.getBoolean("fav:${rom.name}", false)

    fun setFavorite(rom: File, fav: Boolean) = meta.edit().putBoolean("fav:${rom.name}", fav).apply()

    fun recordSession(rom: File, seconds: Long) {
        meta.edit()
            .putLong("last:${rom.name}", System.currentTimeMillis())
            .putLong("time:${rom.name}", playSeconds(rom) + seconds)
            .apply()
    }

    // ---- Cheats: one per line, "1|CODE|name" (1 = enabled) ----

    data class CheatEntry(val code: String, val name: String, val enabled: Boolean)

    fun loadCheats(rom: File): MutableList<CheatEntry> {
        val f = cheatsFile(rom)
        if (!f.exists()) return mutableListOf()
        return f.readLines().mapNotNull { line ->
            val p = line.split('|', limit = 3)
            if (p.size < 3) null else CheatEntry(p[1], p[2], p[0] == "1")
        }.toMutableList()
    }

    fun saveCheats(rom: File, list: List<CheatEntry>) {
        writeAtomic(cheatsFile(rom), list.joinToString("\n") { "${if (it.enabled) 1 else 0}|${it.code}|${it.name.replace('\n', ' ')}" }.toByteArray())
    }

    // ---- Files ----

    /** Writes atomically so a crash never leaves a half-written file. */
    fun writeAtomic(file: File, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun writePng(file: File, frame: IntArray) {
        val bmp = Bitmap.createBitmap(frame, 256, 240, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        writeAtomic(file, out.toByteArray())
    }

    fun readBitmap(file: File): Bitmap? =
        if (file.exists()) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) else null

    /** Moves saves from version 1.0 (stored by ROM checksum) next to the game. */
    fun migrateLegacy(rom: File, crc: Long) {
        val old = File(legacySaves, "%08x".format(crc))
        if (!old.isDirectory) return
        val names = listOf("battery.sav") + (1..SLOT_COUNT).map { "slot$it.state" }
        for (name in names) {
            val src = File(old, name)
            val dst = File(gameDir(rom), name)
            if (src.exists() && !dst.exists()) src.copyTo(dst)
        }
    }

    fun deleteRom(rom: File) {
        rom.delete()
    }

    fun deleteGameData(rom: File) {
        gameDir(rom).deleteRecursively()
        meta.edit().remove("last:${rom.name}").remove("time:${rom.name}").remove("fav:${rom.name}").apply()
    }

    companion object {
        const val SLOT_COUNT = 4
    }
}

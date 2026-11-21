package com.nesemu.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nesemu.core.Cartridge
import com.nesemu.core.Mapper
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Copies .nes files (also inside .zip archives) into the library. */
class RomImporter(private val context: Context, private val storage: Storage) {
    data class Result(val imported: List<File>, val errors: List<String>)

    fun import(uris: List<Uri>): Result {
        val imported = ArrayList<File>()
        val errors = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: "oyun.nes"
            try {
                val bytes = readChecked(uri, name)
                if (isZip(bytes)) {
                    val found = importZip(bytes, imported, errors)
                    if (found == 0) errors.add("$name: içinde .nes oyunu yok")
                } else {
                    imported.add(save(name, bytes))
                }
            } catch (e: Exception) {
                errors.add("$name: ${e.message ?: e.toString()}")
            }
        }
        return Result(imported, errors)
    }

    /** Reads the file, rejecting non-NES files early so huge files (e.g. 100 MB+ DS games) are never loaded. */
    private fun readChecked(uri: Uri, name: String): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("dosya okunamadı")
        stream.buffered().use { input ->
            input.mark(8)
            val head = ByteArray(4)
            val n = input.read(head)
            input.reset()
            val nes = n == 4 && head[0] == 'N'.code.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'S'.code.toByte() && head[3] == 0x1A.toByte()
            val zip = n >= 2 && isZip(head + ByteArray(1))
            if (!nes && !zip) {
                if (name.lowercase().endsWith(".nds")) throw IllegalStateException("Bu bir Nintendo DS oyunu, NES değil")
                throw IllegalStateException("Geçerli bir .nes veya .zip dosyası değil")
            }
            val limit = if (zip) 64 * 1024 * 1024 else 8 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > limit) throw IllegalStateException("Dosya çok büyük")
            }
            return out.toByteArray()
        }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun importZip(bytes: ByteArray, imported: MutableList<File>, errors: MutableList<String>): Int {
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.lowercase().endsWith(".nes")) continue
                count++
                val name = entry.name.substringAfterLast('/')
                try {
                    val data = zip.readBytes()
                    if (data.size > 8 * 1024 * 1024) throw IllegalStateException("çok büyük")
                    imported.add(save(name, data))
                } catch (e: Exception) {
                    errors.add("$name: ${e.message}")
                }
            }
        }
        return count
    }

    private fun save(rawName: String, bytes: ByteArray): File {
        if (rawName.lowercase().endsWith(".nds")) throw IllegalStateException("Nintendo DS oyunu, NES değil")
        // Validate first so the library only ever holds playable files.
        Mapper.create(Cartridge(bytes))
        var name = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (!name.lowercase().endsWith(".nes")) name = name.substringBeforeLast('.') + ".nes"
        val target = File(storage.romDir, name)
        target.writeBytes(bytes)
        return target
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

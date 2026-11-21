package com.nesemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.nesemu.core.Cartridge
import com.nesemu.core.Mapper
import java.io.File

/** Game library: lists imported ROMs and imports new ones from the phone's storage. */
class MainActivity : Activity() {
    private lateinit var storage: Storage
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var roms: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 24))
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "NES Emülatörü"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Oyunlarım"
            textSize = 14f
            setTextColor(Color.rgb(150, 150, 170))
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
        })
        root.addView(Button(this).apply {
            text = "+  ROM Ekle (.nes)"
            textSize = 16f
            setOnClickListener { pickRom() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        empty = TextView(this).apply {
            text = "Henüz oyun yok.\n\n\"ROM Ekle\" ile telefonundaki .nes dosyasını seç. " +
                "Eklenen oyunlar burada listelenir; silmek için oyuna basılı tut."
            textSize = 15f
            setTextColor(Color.rgb(170, 170, 185))
            gravity = Gravity.CENTER
            setPadding(0, (48 * dp).toInt(), 0, 0)
        }
        list = ListView(this).apply {
            divider = null
            setOnItemClickListener { _, _, pos, _ -> play(roms[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(roms[pos]); true }
        }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        roms = storage.roms()
        val dp = resources.displayMetrics.density
        list.adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, roms) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.text = "🎮  " + roms[position].nameWithoutExtension
                v.setTextColor(Color.WHITE)
                v.textSize = 17f
                v.setPadding((8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
                return v
            }
        }
        empty.visibility = if (roms.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun pickRom() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROM && resultCode == RESULT_OK) data?.data?.let { importRom(it) }
    }

    private fun importRom(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Dosya okunamadı")
            // Validate before copying so the library only ever holds playable files.
            Mapper.create(Cartridge(bytes))
            var name = displayName(uri) ?: "oyun.nes"
            if (!name.lowercase().endsWith(".nes")) name += ".nes"
            name = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val target = File(storage.romDir, name)
            target.writeBytes(bytes)
            refresh()
            play(target)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Oyun eklenemedi")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun play(rom: File) {
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.absolutePath))
    }

    private fun confirmDelete(rom: File) {
        AlertDialog.Builder(this)
            .setTitle(rom.nameWithoutExtension)
            .setMessage("Bu oyun listeden silinsin mi? (Kayıtların silinmez.)")
            .setPositiveButton("Sil") { _, _ ->
                storage.deleteRom(rom)
                Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    companion object {
        private const val REQUEST_ROM = 1
    }
}

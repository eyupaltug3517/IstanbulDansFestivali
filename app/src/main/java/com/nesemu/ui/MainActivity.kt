package com.nesemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Game library: cover grid, search, favorites, "continue" card and import. */
class MainActivity : Activity() {
    private lateinit var storage: Storage
    private lateinit var content: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var continueHolder: FrameLayout
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private lateinit var chipViews: List<TextView>
    private var filter = Filter.ALL
    private var query = ""

    private enum class Filter(val label: String) { ALL("Tümü"), FAVORITES("★ Favoriler"), RECENT("Son oynanan") }

    private fun dp(v: Float) = Theme.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        buildUi()
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(20f), dp(18f), dp(110f))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logo.addView(TextView(this).apply {
            text = "RETRO"
            textSize = 30f
            typeface = Theme.TITLE_FONT
            setTextColor(Theme.ACCENT)
            letterSpacing = 0.12f
        })
        logo.addView(TextView(this).apply {
            text = "SALON"
            textSize = 30f
            typeface = Theme.TITLE_FONT
            setTextColor(Theme.ACCENT_2)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = -dp(12f)
        })
        header.addView(logo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Theme.pillButton(this, "🌐 Online", Theme.SURFACE) {
            startActivity(Intent(this@MainActivity, OnlineActivity::class.java))
        }.apply {
            textSize = 14f
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)).apply { rightMargin = dp(10f) })
        header.addView(TextView(this).apply {
            text = "⚙"
            textSize = 24f
            setTextColor(Theme.TEXT)
            gravity = Gravity.CENTER
            background = Theme.clickable(this@MainActivity, Theme.SURFACE, 24f)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        content.addView(header)

        subtitle = Theme.label(this, "", 14f, Theme.TEXT_DIM).apply { setPadding(0, dp(6f), 0, dp(18f)) }
        content.addView(subtitle)

        continueHolder = FrameLayout(this)
        content.addView(continueHolder)

        search = EditText(this).apply {
            hint = "🔍  Oyun ara…"
            setHintTextColor(Theme.TEXT_DIM)
            setTextColor(Theme.TEXT)
            textSize = 16f
            isSingleLine = true
            background = Theme.rounded(Theme.SURFACE, Theme.dp(this@MainActivity, 16f))
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); renderGrid() }
            })
        }
        content.addView(search)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, dp(14f)) }
        chipViews = Filter.entries.map { f ->
            TextView(this).apply {
                text = f.label
                textSize = 14f
                setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
                setOnClickListener { filter = f; paintChips(); renderGrid() }
                chips.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8f)
                })
            }
        }
        paintChips()
        content.addView(chips)

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(grid)

        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        val fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.clickable(this@MainActivity, Theme.ACCENT, 32f)
            elevation = Theme.dp(this@MainActivity, 10f)
            contentDescription = "Oyun ekle"
            setOnClickListener { pickRoms() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            fitsSystemWindows = true
            addView(scroll)
            addView(fab, FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(22f), dp(26f))
            })
        }
        setContentView(root)
    }

    private fun paintChips() {
        chipViews.forEachIndexed { i, v ->
            val on = Filter.entries[i] == filter
            v.background = Theme.clickable(this, if (on) Theme.ACCENT else Theme.SURFACE, 20f)
            v.setTextColor(if (on) Color.WHITE else Theme.TEXT_DIM)
        }
    }

    private fun refresh() {
        val roms = storage.roms()
        val total = roms.sumOf { storage.playSeconds(it) }
        subtitle.text = if (roms.isEmpty()) "Eski oyunlar, yeni cep" else
            "${roms.size} oyun" + (if (total >= 60) " · toplam ${formatTime(total)} oynandı" else "")
        renderContinue(roms)
        renderGrid()
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "$h sa $m dk"
            m > 0 -> "$m dk"
            else -> "1 dk'dan az"
        }
    }

    private fun renderContinue(roms: List<File>) {
        continueHolder.removeAllViews()
        val last = roms.filter { storage.lastPlayed(it) > 0 }.maxByOrNull { storage.lastPlayed(it) } ?: return
        val card = FrameLayout(this).apply {
            background = Theme.rounded(Theme.SURFACE, Theme.dp(this@MainActivity, 20f))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            foreground = Theme.clickable(this@MainActivity, Color.TRANSPARENT, 20f)
            setOnClickListener { play(last) }
        }
        val cover = storage.readBitmap(storage.coverFile(last))
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (cover != null) setImageBitmap(cover) else background = Theme.coverGradient(last.name, 0f)
        }
        card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)))
        // Dark fade at the bottom so the text is readable over any picture.
        card.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, 10, 10, 16))
            )
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(14f))
        }
        texts.addView(Theme.label(this, "KALDIĞIN YERDEN DEVAM ET", 11f, Theme.ACCENT_2, bold = true).apply { typeface = Theme.TITLE_FONT })
        texts.addView(Theme.label(this, Theme.prettyTitle(last.name), 19f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            rightMargin = dp(70f)
        })
        card.addView(TextView(this).apply {
            text = "▶"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rounded(Theme.ACCENT, Theme.dp(this@MainActivity, 26f))
        }, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14f), dp(14f)) })
        continueHolder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)).apply { bottomMargin = dp(16f) })
    }

    private fun renderGrid() {
        grid.removeAllViews()
        var roms = storage.roms()
        if (roms.isEmpty()) {
            grid.addView(emptyState())
            return
        }
        if (query.isNotEmpty()) roms = roms.filter { Theme.prettyTitle(it.name).contains(query, ignoreCase = true) }
        roms = when (filter) {
            Filter.ALL -> roms.sortedBy { Theme.prettyTitle(it.name).lowercase() }
            Filter.FAVORITES -> roms.filter { storage.isFavorite(it) }.sortedBy { Theme.prettyTitle(it.name).lowercase() }
            Filter.RECENT -> roms.filter { storage.lastPlayed(it) > 0 }.sortedByDescending { storage.lastPlayed(it) }
        }
        if (roms.isEmpty()) {
            grid.addView(Theme.label(this, when {
                query.isNotEmpty() -> "\"$query\" ile eşleşen oyun yok."
                filter == Filter.FAVORITES -> "Henüz favori yok. Bir oyunun ☆ işaretine dokun."
                else -> "Henüz oynanan oyun yok."
            }, 15f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(40f), 0, 0) })
            return
        }
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = maxOf(2, (widthDp / 190).toInt())
        roms.chunked(cols).forEach { rowRoms ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(12f)
                    bottomMargin = dp(14f)
                }
                if (i < rowRoms.size) row.addView(gameCard(rowRoms[i]), lp) else row.addView(View(this), lp)
            }
            grid.addView(row)
        }
    }

    private fun gameCard(rom: File): View {
        val title = Theme.prettyTitle(rom.name)
        val radius = Theme.dp(this, 16f)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.clickable(this@MainActivity, Theme.SURFACE, 16f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
            setOnClickListener { play(rom) }
            setOnLongClickListener { showOptions(rom); true }
        }
        val coverFrame = FrameLayout(this)
        val cover = storage.readBitmap(storage.coverFile(rom))
        val image = AspectImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = if (cover != null) Theme.rounded(Color.BLACK, radius * 0.7f) else Theme.coverGradient(rom.name, radius * 0.7f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            if (cover != null) setImageBitmap(cover)
        }
        coverFrame.addView(image)
        if (cover == null) {
            val initials = title.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            coverFrame.addView(TextView(this).apply {
                text = initials.ifEmpty { "?" }
                textSize = 34f
                typeface = Theme.TITLE_FONT
                setTextColor(Color.argb(220, 255, 255, 255))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val fav = storage.isFavorite(rom)
        coverFrame.addView(TextView(this).apply {
            text = if (fav) "★" else "☆"
            textSize = 18f
            setTextColor(if (fav) Theme.ACCENT_2 else Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rounded(Color.argb(140, 0, 0, 0), Theme.dp(this@MainActivity, 18f))
            setOnClickListener {
                storage.setFavorite(rom, !fav)
                renderGrid()
            }
        }, FrameLayout.LayoutParams(dp(36f), dp(36f), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6f), dp(6f), 0) })
        card.addView(coverFrame)

        card.addView(Theme.label(this, title, 14f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), dp(10f), dp(4f), 0)
        })
        val last = storage.lastPlayed(rom)
        val meta = if (last == 0L) "Yeni" else
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
                " · " + formatTime(storage.playSeconds(rom))
        card.addView(Theme.label(this, meta, 12f, if (last == 0L) Theme.GOOD else Theme.TEXT_DIM).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), dp(4f), dp(4f), 0)
        })
        return card
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12f), dp(36f), dp(12f), 0)
        addView(Theme.label(this@MainActivity, "🕹️", 64f).apply { gravity = Gravity.CENTER })
        addView(Theme.label(this@MainActivity, "Salon boş… şimdilik!", 20f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, dp(8f))
        })
        addView(Theme.label(this@MainActivity,
            "Telefonundaki .nes oyunlarını (veya içinde .nes olan .zip arşivlerini) ekle. Birden fazla dosyayı aynı anda seçebilirsin.",
            15f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER })
        addView(Theme.pillButton(this@MainActivity, "+  Oyun Ekle", Theme.ACCENT) { pickRoms() }.apply {
            setPadding(dp(28f), dp(14f), dp(28f), dp(14f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22f)
        })
    }

    private fun showOptions(rom: File) {
        val fav = storage.isFavorite(rom)
        val items = arrayOf("▶  Oyna", if (fav) "☆  Favorilerden çıkar" else "★  Favorilere ekle", "🧹  Kayıtları sıfırla", "🗑  Oyunu sil")
        AlertDialog.Builder(this)
            .setTitle(Theme.prettyTitle(rom.name))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> play(rom)
                    1 -> { storage.setFavorite(rom, !fav); refresh() }
                    2 -> confirm("Kayıtlar sıfırlansın mı?", "Bu oyunun tüm kayıt slotları, otomatik kaydı ve hile kodları silinir.", "Sıfırla") {
                        storage.deleteGameData(rom); refresh()
                    }
                    3 -> confirm("Oyun silinsin mi?", "Oyun ve tüm kayıtları telefondan silinir.", "Sil") {
                        storage.deleteGameData(rom); storage.deleteRom(rom); refresh()
                    }
                }
            }
            .show()
    }

    private fun confirm(title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---- Import ----

    private fun pickRoms() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        importUris(uris)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { importUris(listOf(it)) }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val toast = Toast.makeText(this, "Oyunlar ekleniyor…", Toast.LENGTH_SHORT)
        toast.show()
        Thread {
            val result = RomImporter(this, storage).import(uris)
            runOnUiThread {
                toast.cancel()
                refresh()
                when {
                    result.errors.isNotEmpty() -> AlertDialog.Builder(this)
                        .setTitle(if (result.imported.isEmpty()) "Oyun eklenemedi" else "${result.imported.size} oyun eklendi, bazıları eklenemedi")
                        .setMessage(result.errors.joinToString("\n"))
                        .setPositiveButton("Tamam", null)
                        .show()
                    result.imported.size == 1 -> play(result.imported[0])
                    else -> Toast.makeText(this, "${result.imported.size} oyun eklendi 🎉", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun play(rom: File) {
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.absolutePath))
    }

    companion object {
        private const val REQUEST_ROM = 1
    }
}

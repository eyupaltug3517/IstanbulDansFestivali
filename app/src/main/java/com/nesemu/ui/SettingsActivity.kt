package com.nesemu.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var settings: Settings

    private fun dp(v: Float) = Theme.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(32f))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(Theme.TEXT)
            setPadding(0, dp(4f), dp(16f), dp(4f))
            setOnClickListener { finish() }
        })
        header.addView(Theme.label(this, "Ayarlar", 24f, bold = true))
        content.addView(header)

        section(content, "GÖRÜNTÜ")
        content.addView(choiceRow("Ekran filtresi", VideoFilter.entries.map { it.label }, settings.videoFilter.ordinal) {
            settings.videoFilter = VideoFilter.entries[it]
        })

        section(content, "KUMANDA")
        content.addView(sliderRow("Saydamlık", 20, 100, settings.padOpacity, "%") { settings.padOpacity = it })
        content.addView(sliderRow("Boyut", 70, 130, settings.padScale, "%") { settings.padScale = it })
        content.addView(switchRow("Titreşim", "Tuşa basınca hafif titreşim", settings.haptics) { settings.haptics = it })

        section(content, "OYUN")
        content.addView(switchRow("Ses", "Oyun müziği ve efektler", settings.sound) { settings.sound = it })
        content.addView(switchRow("Kaldığın yerden devam", "Oyunu kapatınca otomatik kaydet, açınca devam et", settings.autoResume) {
            settings.autoResume = it
        })
        content.addView(choiceRow("Hızlı ileri sarma", listOf("2x", "3x", "4x"), settings.fastSpeed - 2) {
            settings.fastSpeed = it + 2
        })

        section(content, "HAKKINDA")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        content.addView(Theme.label(this,
            "Retro Salon $version\nSıfırdan yazılmış NES emülatörü.\n\n" +
                "Desteklenen kartuşlar: NROM, MMC1, UxROM, CNROM, MMC3, AxROM, Color Dreams, GxROM.\n\n" +
                "Gamepad: L1 geri sar · R1 hızlı ileri · Mode menü",
            14f, Theme.TEXT_DIM))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            fitsSystemWindows = true
            addView(content)
        }
        setContentView(scroll)
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(Theme.label(this, title, 12f, Theme.ACCENT_2, bold = true).apply {
            typeface = Theme.TITLE_FONT
            setPadding(dp(4f), dp(28f), 0, dp(10f))
        })
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.SURFACE, Theme.dp(this@SettingsActivity, 16f))
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        }
    }

    private fun switchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) = box().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Theme.label(context, title, 16f, bold = true))
        texts.addView(Theme.label(context, subtitle, 13f, Theme.TEXT_DIM))
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(context).apply {
            isChecked = value
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Theme.ACCENT, Theme.TEXT_DIM)
            )
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    private fun sliderRow(title: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) = box().apply {
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Theme.label(context, title, 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueLabel = Theme.label(context, "$value$unit", 15f, Theme.ACCENT_2, bold = true)
        top.addView(valueLabel)
        addView(top)
        addView(SeekBar(context).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(Theme.ACCENT)
            thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
            setPadding(0, dp(12f), 0, dp(4f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueLabel.text = "${p + min}$unit"
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
    }

    private fun choiceRow(title: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) = box().apply {
        addView(Theme.label(context, title, 16f, bold = true))
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10f), 0, 0) }
        var current = selected
        val views = ArrayList<TextView>()
        fun paint() = views.forEachIndexed { i, v ->
            v.background = Theme.clickable(context, if (i == current) Theme.ACCENT else Theme.SURFACE_HI, 20f)
            v.setTextColor(if (i == current) android.graphics.Color.WHITE else Theme.TEXT_DIM)
        }
        options.forEachIndexed { i, label ->
            val chip = TextView(context).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                setOnClickListener { current = i; paint(); onChange(i) }
            }
            views.add(chip)
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(6f)
            })
        }
        paint()
        addView(chips)
    }
}

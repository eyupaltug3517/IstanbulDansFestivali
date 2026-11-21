package com.nesemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nesemu.net.NetConfig
import com.nesemu.net.Netplay
import com.nesemu.net.SupabaseTransport
import java.io.File
import java.util.zip.CRC32

/** Hands a connected guest session from the lobby to the game screen. */
object NetHolder {
    @Volatile var guestSession: Netplay? = null
}

/** Online lobby: host a room with one of your games, or join a friend's room by code. */
class OnlineActivity : Activity() {
    private lateinit var storage: Storage
    private lateinit var status: TextView
    private lateinit var codeField: EditText
    private var joining: Netplay? = null

    private fun dp(v: Float) = Theme.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
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
        header.addView(Theme.label(this, "Online Oyna", 24f, bold = true))
        content.addView(header)
        content.addView(Theme.label(this,
            "Arkadaşınla internetten birlikte oyna. Odayı kuran 1. oyuncu, katılan 2. oyuncu olur. " +
                "İkinizin telefonunda da aynı oyun dosyası olmalı.",
            14f, Theme.TEXT_DIM).apply { setPadding(0, dp(8f), 0, dp(8f)) })

        // Join
        content.addView(section("ODAYA KATIL"))
        val joinBox = box()
        joinBox.addView(Theme.label(this, "Arkadaşının gönderdiği 4 haneli oda kodunu gir", 15f))
        codeField = EditText(this).apply {
            hint = "• • • •"
            setHintTextColor(Theme.TEXT_DIM)
            setTextColor(Theme.ACCENT_2)
            textSize = 34f
            typeface = Theme.TITLE_FONT
            letterSpacing = 0.4f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            imeOptions = EditorInfo.IME_ACTION_GO
            background = Theme.rounded(Color.rgb(16, 16, 24), Theme.dp(this@OnlineActivity, 14f))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnEditorActionListener { _, _, _ -> join(); true }
        }
        joinBox.addView(codeField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })
        joinBox.addView(Theme.pillButton(this, "Katıl", Theme.ACCENT) { join() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        status = Theme.label(this, "", 14f, Theme.ACCENT_2).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        joinBox.addView(status)
        content.addView(joinBox)

        // Host
        content.addView(section("ODA KUR"))
        val roms = storage.roms().sortedBy { Theme.prettyTitle(it.name).lowercase() }
        if (roms.isEmpty()) {
            content.addView(Theme.label(this, "Oda kurmak için önce kütüphaneye oyun ekle.", 14f, Theme.TEXT_DIM))
        }
        content.addView(Theme.label(this, "Hangi oyunu oynayacaksınız?", 14f, Theme.TEXT_DIM).apply {
            setPadding(dp(2f), 0, 0, dp(8f))
            visibility = if (roms.isEmpty()) View.GONE else View.VISIBLE
        })
        for (rom in roms) {
            content.addView(TextView(this).apply {
                text = "🎮   " + Theme.prettyTitle(rom.name)
                textSize = 16f
                setTextColor(Theme.TEXT)
                setTypeface(typeface, Typeface.BOLD)
                background = Theme.clickable(this@OnlineActivity, Theme.SURFACE, 14f)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                setOnClickListener { host(rom) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            fitsSystemWindows = true
            addView(content)
        })
    }

    private fun section(title: String) = Theme.label(this, title, 12f, Theme.ACCENT_2, bold = true).apply {
        typeface = Theme.TITLE_FONT
        setPadding(dp(4f), dp(24f), 0, dp(10f))
    }

    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.SURFACE, Theme.dp(this@OnlineActivity, 18f))
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
    }

    private fun host(rom: File) {
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ROM, rom.absolutePath)
            .putExtra(GameActivity.EXTRA_NET, GameActivity.NET_HOST))
        finish()
    }

    private fun join() {
        val code = codeField.text.toString().trim()
        if (code.length != 4) {
            status.text = "Kod 4 haneli olmalı"
            return
        }
        joining?.close()
        status.text = "Bağlanılıyor…"
        val net = Netplay(SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY), isHost = false, room = code)
        joining = net
        net.events = object : Netplay.Events {
            override fun onConnected() = runOnUiThread {
                if (joining === net) status.text = "Odaya girildi, oyun kurucusu bekleniyor…"
            }
            override fun onStart(crc: Long, name: String) {
                val rom = findRom(crc)
                runOnUiThread {
                    if (joining !== net) return@runOnUiThread
                    if (rom == null) {
                        net.close()
                        joining = null
                        status.text = ""
                        AlertDialog.Builder(this@OnlineActivity)
                            .setTitle("Bu oyun sende yok")
                            .setMessage("Arkadaşın \"$name\" oynuyor. Aynı oyun dosyasını kütüphanene ekleyip tekrar dene.")
                            .setPositiveButton("Tamam", null)
                            .show()
                        return@runOnUiThread
                    }
                    joining = null // ownership moves to the game screen
                    NetHolder.guestSession = net
                    startActivity(Intent(this@OnlineActivity, GameActivity::class.java)
                        .putExtra(GameActivity.EXTRA_ROM, rom.absolutePath)
                        .putExtra(GameActivity.EXTRA_NET, GameActivity.NET_GUEST)
                        .putExtra(GameActivity.EXTRA_ROOM, code))
                    finish()
                }
            }
            override fun onError(message: String) = runOnUiThread {
                if (joining === net) status.text = message
            }
        }
        net.connect()
    }

    private fun findRom(crc: Long): File? = storage.roms().firstOrNull { f ->
        try {
            val data = f.readBytes()
            CRC32().apply { update(data, 16, data.size - 16) }.value == crc
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        super.onDestroy()
        joining?.close()
    }
}

package com.nesemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nesemu.core.Buttons
import com.nesemu.core.Cheat
import com.nesemu.core.Console
import com.nesemu.net.NetConfig
import com.nesemu.net.Netplay
import com.nesemu.net.NetplayDriver
import com.nesemu.net.SupabaseTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GameActivity : Activity(), ControllerView.Listener {
    private lateinit var console: Console
    private lateinit var storage: Storage
    private lateinit var settings: Settings
    private lateinit var rom: File
    private lateinit var title: String
    private lateinit var gameView: GameView
    private lateinit var controls: ControllerView
    private lateinit var root: FrameLayout
    private var thread: EmulatorThread? = null
    private var touchHeld = 0
    private var keyHeld = 0
    private var recordedFrames = 0L
    /** Overlays (menu, slot picker, cheats), top-most last. */
    private val overlays = ArrayList<View>()

    // Online play
    private var netRole: String? = null
    private var net: Netplay? = null
    private var roomCode = ""
    private var waitingOverlay: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateNetStatus()
            ui.postDelayed(this, 500)
        }
    }
    private val isOnline get() = netRole != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        storage = Storage(this)
        settings = Settings(this)

        rom = File(intent.getStringExtra(EXTRA_ROM) ?: "")
        title = Theme.prettyTitle(rom.name)
        console = try {
            Console(rom.readBytes(), EmulatorThread.SAMPLE_RATE)
        } catch (e: Exception) {
            Toast.makeText(this, "Oyun açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        storage.migrateLegacy(rom, console.cart.crc)
        loadBattery()
        netRole = intent.getStringExtra(EXTRA_NET)
        // Cheats would make the two players' games differ, so they are off online.
        if (!isOnline) applyCheats(storage.loadCheats(rom))

        gameView = GameView(this)
        controls = ControllerView(this, this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(gameView)
        root.addView(controls)
        setContentView(root)
        hideSystemBars()

        val t = EmulatorThread(console, gameView)
        thread = t
        applySettings()

        val auto = storage.autoStateFile(rom)
        if (netRole != NET_GUEST && settings.autoResume && auto.exists()) {
            t.post {
                val ok = try { console.loadState(auto.readBytes()); true } catch (e: Exception) { false }
                if (ok) runOnUiThread { toast("Kaldığın yerden devam ediliyor") }
            }
        }
        t.start()
        when (netRole) {
            NET_HOST -> startHosting()
            NET_GUEST -> startGuest()
        }
    }

    private fun applySettings() {
        val t = thread ?: return
        t.soundOn = settings.sound
        t.fastSpeed = settings.fastSpeed
        gameView.filter = settings.videoFilter
        controls.opacity = settings.padOpacity
        controls.scale = settings.padScale
        controls.haptics = settings.haptics
        controls.fastSpeed = settings.fastSpeed
        controls.netMode = isOnline
    }

    // ---- ControllerView.Listener ----

    override fun onPad(held: Int, turbo: Int) {
        touchHeld = held
        thread?.held = touchHeld or keyHeld
        thread?.turbo = turbo
    }

    override fun onRewind(active: Boolean) {
        if (isOnline) return
        thread?.rewinding = active
    }

    override fun onFastForward() {
        if (isOnline) return
        val t = thread ?: return
        t.fastForward = !t.fastForward
        controls.fastForward = t.fastForward
    }

    override fun onMenu() = showPauseMenu()

    // ---- Lifecycle ----

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        if (thread == null) return
        controls.releaseAll()
        thread?.setPausedState(true)
        persist()
    }

    override fun onResume() {
        super.onResume()
        if (thread == null) return
        applySettings()
        if (overlays.isEmpty()) thread?.setPausedState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        net?.close()
        net = null
        thread?.shutdown()
        thread = null
    }

    /** Saves battery RAM, the resume point and the cover picture; waits so nothing is lost if the app is killed. */
    private fun persist() {
        val t = thread ?: return
        val latch = CountDownLatch(1)
        t.post {
            try {
                if (netRole != NET_GUEST) {
                    saveBattery()
                    storage.writeAtomic(storage.autoStateFile(rom), console.saveState())
                    storage.writePng(storage.coverFile(rom), console.ppu.frame.copyOf())
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        val frames = t.framesPlayed
        storage.recordSession(rom, (frames - recordedFrames) / 60)
        recordedFrames = frames
    }

    private fun loadBattery() {
        if (!console.hasBattery) return
        val f = storage.batteryFile(rom)
        if (f.exists()) console.loadBatteryRam(f.readBytes())
    }

    private fun saveBattery() {
        if (console.hasBattery) storage.writeAtomic(storage.batteryFile(rom), console.batteryRam())
    }

    private fun applyCheats(list: List<Storage.CheatEntry>) {
        val cheats = list.filter { it.enabled }.mapNotNull { Cheat.parse(it.code) }
        val t = thread
        if (t == null) console.setCheats(cheats) else t.post { console.setCheats(cheats) }
    }

    // ---- Overlays ----

    private fun dp(v: Float) = Theme.dpi(this, v)

    private fun pushOverlay(content: View): View {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(215, 6, 6, 12))
            isClickable = true // swallow touches meant for the pad
            val scroll = ScrollView(this@GameActivity).apply { isFillViewport = true }
            val holder = FrameLayout(this@GameActivity)
            holder.addView(content, FrameLayout.LayoutParams(
                minOf(dp(420f), resources.displayMetrics.widthPixels - dp(32f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            holder.setPadding(0, dp(24f), 0, dp(24f))
            scroll.addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            alpha = 0f
            animate().alpha(1f).setDuration(140).start()
        }
        root.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlays.add(scrim)
        return scrim
    }

    private fun popOverlay() {
        val top = overlays.removeLastOrNull() ?: return
        root.removeView(top)
        if (overlays.isEmpty()) {
            hideSystemBars()
            thread?.setPausedState(false)
        }
    }

    private fun closeAllOverlays() {
        while (overlays.isNotEmpty()) popOverlay()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.SURFACE, Theme.dp(this@GameActivity, 24f))
        setPadding(dp(20f), dp(20f), dp(20f), dp(16f))
        elevation = Theme.dp(this@GameActivity, 8f)
    }

    private fun menuButton(text: String, color: Int = Theme.SURFACE_HI, textColor: Int = Theme.TEXT, onClick: () -> Unit): View =
        Theme.pillButton(this, text, color, textColor) { onClick() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            }
        }

    private fun showPauseMenu() {
        if (overlays.isNotEmpty()) return
        controls.releaseAll()
        thread?.setPausedState(true)
        val c = card()
        c.addView(Theme.label(this, "DURAKLATILDI", 12f, Theme.ACCENT_2, bold = true).apply { typeface = Theme.TITLE_FONT })
        c.addView(Theme.label(this, title, 20f, bold = true).apply { setPadding(0, dp(4f), 0, dp(8f)) })
        c.addView(menuButton("▶   Devam Et", Theme.ACCENT) { popOverlay() })
        if (isOnline) {
            c.addView(Theme.label(this, "Online oyunda menü açıkken arkadaşının oyunu da bekler.", 13f, Theme.TEXT_DIM).apply {
                setPadding(dp(4f), dp(10f), dp(4f), 0)
            })
            c.addView(menuButton("⚙   Ayarlar") { startActivity(Intent(this, SettingsActivity::class.java)) })
            c.addView(menuButton("🚪   Odadan Çık") { finish() })
            pushOverlay(c)
            return
        }
        c.addView(menuButton("💾   Kaydet") { showSlots(save = true) })
        c.addView(menuButton("📂   Kayıt Yükle") { showSlots(save = false) })
        c.addView(menuButton("🎯   Hile Kodları") { showCheats() })
        c.addView(menuButton("⚙   Ayarlar") { startActivity(Intent(this, SettingsActivity::class.java)) })
        c.addView(menuButton("🔄   Baştan Başla") {
            AlertDialog.Builder(this)
                .setTitle("Baştan başlansın mı?")
                .setMessage("Kaydedilmemiş ilerleme kaybolur.")
                .setPositiveButton("Baştan Başla") { _, _ ->
                    thread?.post { console.reset(); thread?.clearRewind() }
                    closeAllOverlays()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        c.addView(menuButton("🚪   Oyundan Çık") { finish() })
        pushOverlay(c)
    }

    private fun showSlots(save: Boolean) {
        val c = card()
        c.addView(Theme.label(this, if (save) "KAYDET" else "KAYIT YÜKLE", 12f, Theme.ACCENT_2, bold = true).apply { typeface = Theme.TITLE_FONT })
        c.addView(Theme.label(this, if (save) "Bir slot seç" else "Hangi kayıttan devam edelim?", 18f, bold = true).apply {
            setPadding(0, dp(4f), 0, dp(12f))
        })
        for (row in 0 until Storage.SLOT_COUNT / 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 2) {
                val slot = row * 2 + col + 1
                line.addView(slotCard(slot, save), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (col == 0) rightMargin = dp(6f) else leftMargin = dp(6f)
                    bottomMargin = dp(12f)
                })
            }
            c.addView(line)
        }
        c.addView(menuButton("Geri") { popOverlay() })
        pushOverlay(c)
    }

    private fun slotCard(slot: Int, save: Boolean): View {
        val file = storage.slotFile(rom, slot)
        val exists = file.exists()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.clickable(this@GameActivity, Theme.SURFACE_HI, 16f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(10f))
        }
        val thumb = AspectImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = Theme.rounded(Color.rgb(10, 10, 16), Theme.dp(this@GameActivity, 10f))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            storage.readBitmap(storage.slotThumb(rom, slot))?.let { setImageBitmap(it) }
        }
        val frame = FrameLayout(this)
        frame.addView(thumb)
        if (!exists) frame.addView(Theme.label(this, "BOŞ", 14f, Theme.TEXT_DIM, bold = true).apply { typeface = Theme.TITLE_FONT },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.addView(frame)
        box.addView(Theme.label(this, "Slot $slot", 14f, bold = true).apply { setPadding(dp(2f), dp(8f), 0, 0) })
        val whenText = if (exists) DateUtils.getRelativeTimeSpanString(file.lastModified()).toString() else "—"
        box.addView(Theme.label(this, whenText, 12f, Theme.TEXT_DIM).apply { setPadding(dp(2f), dp(2f), 0, 0) })
        box.setOnClickListener {
            if (save) {
                val doSave = {
                    val t = thread
                    t?.post {
                        val ok = try {
                            storage.writeAtomic(file, console.saveState())
                            storage.writePng(storage.slotThumb(rom, slot), console.ppu.frame.copyOf())
                            true
                        } catch (e: Exception) { false }
                        runOnUiThread {
                            toast(if (ok) "Slot $slot'a kaydedildi" else "Kaydedilemedi")
                            closeAllOverlays()
                        }
                    }
                }
                if (exists) {
                    AlertDialog.Builder(this)
                        .setTitle("Slot $slot dolu")
                        .setMessage("Eski kaydın üzerine yazılsın mı?")
                        .setPositiveButton("Üzerine Yaz") { _, _ -> doSave() }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                } else doSave()
            } else if (!exists) {
                toast("Slot $slot boş")
            } else {
                thread?.post {
                    val err = try { console.loadState(file.readBytes()); thread?.clearRewind(); null } catch (e: Exception) { e.message ?: "hata" }
                    runOnUiThread {
                        toast(if (err == null) "Slot $slot yüklendi" else "Yüklenemedi: $err")
                        if (err == null) closeAllOverlays()
                    }
                }
            }
        }
        return box
    }

    private fun showCheats() {
        val entries = storage.loadCheats(rom)
        val c = card()
        c.addView(Theme.label(this, "HİLE KODLARI", 12f, Theme.ACCENT_2, bold = true).apply { typeface = Theme.TITLE_FONT })
        c.addView(Theme.label(this, "Game Genie (ör. SXIOPO) veya RAM kodu (ör. 075A:09)", 13f, Theme.TEXT_DIM).apply {
            setPadding(0, dp(4f), 0, dp(12f))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(list)

        fun save() {
            storage.saveCheats(rom, entries)
            applyCheats(entries)
        }

        fun render() {
            list.removeAllViews()
            if (entries.isEmpty()) {
                list.addView(Theme.label(this, "Henüz kod yok.", 14f, Theme.TEXT_DIM).apply { setPadding(0, dp(4f), 0, dp(8f)) })
            }
            entries.forEachIndexed { i, e ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rounded(Theme.SURFACE_HI, Theme.dp(this@GameActivity, 12f))
                    setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
                }
                row.addView(CheckBox(this).apply {
                    isChecked = e.enabled
                    buttonTintList = android.content.res.ColorStateList.valueOf(Theme.ACCENT)
                    setOnCheckedChangeListener { _, on -> entries[i] = e.copy(enabled = on); save() }
                })
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(Theme.label(this, e.name.ifBlank { "Kod" }, 15f, bold = true))
                texts.addView(Theme.label(this, e.code, 12f, Theme.ACCENT_2).apply { typeface = Typeface.MONOSPACE })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 18f; setTextColor(Theme.TEXT_DIM)
                    setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                    setOnClickListener { entries.removeAt(i); save(); render() }
                })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8f)
                })
            }
        }
        render()

        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Theme.TEXT_DIM)
            setTextColor(Theme.TEXT)
            textSize = 15f
            isSingleLine = true
            background = Theme.rounded(Color.rgb(16, 16, 24), Theme.dp(this@GameActivity, 12f))
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        val code = field("Kod").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(12))
            typeface = Typeface.MONOSPACE
        }
        val name = field("Açıklama (ör. Sonsuz can)")
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) } }
        c.addView(code, lp())
        c.addView(name, lp())
        c.addView(menuButton("+   Kodu Ekle", Theme.ACCENT) {
            val parsed = Cheat.parse(code.text.toString())
            if (parsed == null) {
                toast("Geçersiz kod")
            } else {
                entries.add(Storage.CheatEntry(parsed.code, name.text.toString().trim(), true))
                save(); render()
                code.setText(""); name.setText("")
                toast("Kod eklendi ve açıldı")
            }
        })
        c.addView(menuButton("Geri") { popOverlay() })
        pushOverlay(c)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (waitingOverlay != null) finish()
        else if (overlays.isNotEmpty()) popOverlay() else showPauseMenu()
    }

    // ---- Physical gamepad / keyboard ----

    private fun keyToButton(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Buttons.UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Buttons.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Buttons.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Buttons.RIGHT
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_K -> Buttons.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_J -> Buttons.B
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Buttons.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> Buttons.SELECT
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
        if (fromPad && overlays.isEmpty() && !isOnline) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { thread?.rewinding = down; return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { if (down && event.repeatCount == 0) onFastForward(); return true }
                KeyEvent.KEYCODE_BUTTON_MODE -> { if (down) showPauseMenu(); return true }
            }
            val b = keyToButton(event.keyCode)
            if (b != 0) {
                keyHeld = if (down) keyHeld or b else keyHeld and b.inv()
                thread?.held = touchHeld or keyHeld
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Online ----

    private fun startHosting() {
        val t = thread ?: return
        roomCode = (1000..9999).random().toString()
        val n = Netplay(SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY), isHost = true, room = roomCode)
        net = n
        val driver = NetplayDriver(console, n)
        n.events = object : Netplay.Events {
            override fun onPeerJoined() {
                t.post {
                    driver.hostRestart(title)
                    t.netDriver = driver
                }
                runOnUiThread {
                    toast("Arkadaşın bağlandı! İyi oyunlar 🎮")
                    waitingOverlay?.let { closeAllOverlays() }
                    waitingOverlay = null
                }
            }
            override fun onDesync() {
                // Re-send our state; the guest reloads it and both continue in sync.
                t.post { if (t.netDriver != null) driver.hostRestart(title) }
                runOnUiThread { toast("Senkron düzeltiliyor…") }
            }
            override fun onPeerLeft() = runOnUiThread { peerLeft() }
            override fun onError(message: String) = runOnUiThread { netFailed(message) }
        }
        n.connect()
        showWaitingForFriend()
        ui.post(statusUpdater)
    }

    private fun startGuest() {
        val t = thread ?: return
        val n = NetHolder.guestSession
        NetHolder.guestSession = null
        if (n == null) {
            finish()
            return
        }
        net = n
        roomCode = intent.getStringExtra(EXTRA_ROOM) ?: n.room
        n.events = object : Netplay.Events {
            override fun onDesync() = runOnUiThread { toast("Senkron düzeltiliyor…") }
            override fun onPeerLeft() = runOnUiThread { peerLeft() }
            override fun onError(message: String) = runOnUiThread { netFailed(message) }
        }
        t.netDriver = NetplayDriver(console, n)
        ui.post(statusUpdater)
    }

    private fun showWaitingForFriend() {
        controls.releaseAll()
        thread?.setPausedState(true)
        val c = card()
        c.addView(Theme.label(this, "ONLİNE ODA", 12f, Theme.ACCENT_2, bold = true).apply { typeface = Theme.TITLE_FONT })
        c.addView(Theme.label(this, title, 18f, bold = true).apply { setPadding(0, dp(4f), 0, dp(14f)) })
        c.addView(Theme.label(this, "Oda kodu", 14f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER })
        c.addView(Theme.label(this, roomCode, 48f, Theme.ACCENT_2, bold = true).apply {
            typeface = Theme.TITLE_FONT
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
        })
        c.addView(Theme.label(this,
            "Arkadaşın Retro Salon'da \"Online Oyna → Odaya Katıl\" deyip bu kodu girsin. " +
                "Onun telefonunda da aynı oyun olmalı.\n\nSen 1. oyuncusun, arkadaşın 2. oyuncu.",
            14f, Theme.TEXT_DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(6f)) })
        c.addView(Theme.label(this, "⏳  Arkadaşın bekleniyor…", 15f, Theme.ACCENT_2, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, dp(6f))
        })
        c.addView(menuButton("📤   Kodu Paylaş", Theme.ACCENT) {
            val text = "Retro Salon'da birlikte oynayalım! 🎮\nOyun: $title\nOda kodu: $roomCode"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Kodu paylaş"))
        })
        c.addView(menuButton("İptal") { finish() })
        waitingOverlay = pushOverlay(c)
    }

    private fun updateNetStatus() {
        val n = net ?: return
        val t = thread ?: return
        val player = if (n.isHost) "1P" else "2P"
        val ping = if (n.pingMs >= 0) " · ${n.pingMs} ms" else ""
        controls.statusText = when {
            !n.started -> null
            t.waitingForPeer -> "⏳ Arkadaşın bekleniyor…"
            else -> "🌐 Oda $roomCode · $player$ping"
        }
    }

    private fun peerLeft() {
        if (isFinishing) return
        thread?.setPausedState(true)
        AlertDialog.Builder(this)
            .setTitle("Arkadaşın ayrıldı")
            .setMessage("Bağlantı koptu ya da arkadaşın oyundan çıktı.")
            .setCancelable(false)
            .setPositiveButton("Çık") { _, _ -> finish() }
            .show()
    }

    private fun netFailed(message: String) {
        if (isFinishing) return
        thread?.setPausedState(true)
        AlertDialog.Builder(this)
            .setTitle("Online bağlantı sorunu")
            .setMessage(message + "\n\nİnternet bağlantını kontrol edip tekrar dene.")
            .setCancelable(false)
            .setPositiveButton("Çık") { _, _ -> finish() }
            .show()
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_NET = "net"
        const val EXTRA_ROOM = "room"
        const val NET_HOST = "host"
        const val NET_GUEST = "guest"
    }
}

/** ImageView that keeps the NES 256:240 aspect ratio. */
class AspectImageView(context: android.content.Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 240 / 256)
    }
}

package com.nesemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.nesemu.core.Buttons
import com.nesemu.core.Console
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameActivity : Activity() {
    private lateinit var console: Console
    private lateinit var storage: Storage
    private lateinit var gameView: GameView
    private var thread: EmulatorThread? = null
    private var touchButtons = 0
    private var keyButtons = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        storage = Storage(this)

        val path = intent.getStringExtra(EXTRA_ROM)
        console = try {
            Console(File(path!!).readBytes(), EmulatorThread.SAMPLE_RATE)
        } catch (e: Exception) {
            Toast.makeText(this, "Oyun açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadBattery()

        val dp = resources.displayMetrics.density
        gameView = GameView(this)
        val controls = ControllerView(this) { touchButtons = it; updateButtons() }
        val menu = TextView(this).apply {
            text = "☰"
            textSize = 22f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setOnClickListener { showMenu(this) }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(gameView)
        root.addView(controls)
        root.addView(menu, FrameLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt(), Gravity.TOP or Gravity.END))
        setContentView(root)
        hideSystemBars()

        thread = EmulatorThread(console, gameView).also {
            it.soundOn = getPreferences(MODE_PRIVATE).getBoolean(PREF_SOUND, true)
            it.start()
        }
    }

    private fun updateButtons() {
        console.buttons1 = touchButtons or keyButtons
    }

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
        thread?.setPausedState(true)
        thread?.post { saveBattery() }
    }

    override fun onResume() {
        super.onResume()
        thread?.setPausedState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        thread?.shutdown()
        thread = null
        if (::console.isInitialized) saveBattery()
    }

    // ---- Battery saves (e.g. Zelda) ----

    private fun loadBattery() {
        if (!console.hasBattery) return
        val f = storage.batteryFile(console.cart.crc)
        if (f.exists()) console.loadBatteryRam(f.readBytes())
    }

    private fun saveBattery() {
        if (!console.hasBattery) return
        try {
            storage.writeAtomic(storage.batteryFile(console.cart.crc), console.batteryRam())
        } catch (_: Exception) {
        }
    }

    // ---- Menu ----

    private fun showMenu(anchor: View) {
        val t = thread ?: return
        t.setPausedState(true)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Kaydet")
        popup.menu.add(0, 2, 1, "Kayıt Yükle")
        popup.menu.add(0, 3, 2, if (t.soundOn) "Sesi Kapat" else "Sesi Aç")
        popup.menu.add(0, 4, 3, "Oyunu Sıfırla")
        popup.menu.add(0, 5, 4, "Oyundan Çık")
        var dialogOpened = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { dialogOpened = true; chooseSlot(save = true) }
                2 -> { dialogOpened = true; chooseSlot(save = false) }
                3 -> {
                    t.soundOn = !t.soundOn
                    getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_SOUND, t.soundOn).apply()
                }
                4 -> t.post { console.reset() }
                5 -> finish()
            }
            true
        }
        popup.setOnDismissListener { if (!dialogOpened) t.setPausedState(false) }
        popup.show()
    }

    private fun slotLabel(slot: Int): String {
        val f = storage.slotFile(console.cart.crc, slot)
        val time = if (f.exists()) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date(f.lastModified())) else "boş"
        return "Slot $slot  —  $time"
    }

    private fun chooseSlot(save: Boolean) {
        val t = thread ?: return
        val labels = Array(Storage.SLOT_COUNT) { slotLabel(it + 1) }
        AlertDialog.Builder(this)
            .setTitle(if (save) "Hangi slota kaydedilsin?" else "Hangi kayıt yüklensin?")
            .setItems(labels) { _, which ->
                val slot = which + 1
                val file = storage.slotFile(console.cart.crc, slot)
                if (save) {
                    t.post {
                        val ok = try {
                            storage.writeAtomic(file, console.saveState()); true
                        } catch (e: Exception) { false }
                        runOnUiThread { toast(if (ok) "Slot $slot'a kaydedildi" else "Kaydedilemedi") }
                    }
                } else if (!file.exists()) {
                    toast("Slot $slot boş")
                } else {
                    t.post {
                        val err = try {
                            console.loadState(file.readBytes()); null
                        } catch (e: Exception) { e.message ?: "hata" }
                        runOnUiThread { toast(if (err == null) "Slot $slot yüklendi" else "Yüklenemedi: $err") }
                    }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .setOnDismissListener { t.setPausedState(false) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
        val isPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
        val b = keyToButton(event.keyCode)
        if (isPad && b != 0) {
            keyButtons = if (event.action == KeyEvent.ACTION_DOWN) keyButtons or b else keyButtons and b.inv()
            updateButtons()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_ROM = "rom"
        private const val PREF_SOUND = "sound"
    }
}

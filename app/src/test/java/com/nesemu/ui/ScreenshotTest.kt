package com.nesemu.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the main screens to build/screens/ for visual review.
 * Opt-in: set NES_SCREENSHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun shot(view: View, name: String) {
        ShadowLooper.idleMainLooper()
        val w = view.width; val h = view.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seedLibrary() {
        val ctx = RuntimeEnvironment.getApplication()
        val storage = Storage(ctx)
        val src = File("src/test/resources/roms/nestest.nes")
        for (n in listOf("Captain Tsubasa Vol. II - Super Striker (J) [T+Eng].nes", "Mega Adventure (U).nes", "Pixel Quest [!].nes", "Zeta Racer.nes")) {
            src.copyTo(File(storage.romDir, n), overwrite = true)
        }
        val a = File(storage.romDir, "Captain Tsubasa Vol. II - Super Striker (J) [T+Eng].nes")
        val nes = com.nesemu.core.Console(src.readBytes())
        repeat(30) { nes.stepFrame() }
        storage.writePng(storage.coverFile(a), nes.ppu.frame)
        storage.recordSession(a, 4500)
        storage.setFavorite(File(storage.romDir, "Pixel Quest [!].nes"), true)
    }

    @Test
    fun library() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        seedLibrary()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(act.window.decorView, "library")
    }

    @Test
    fun emptyLibrary() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(act.window.decorView, "library_empty")
    }

    @Test
    fun settings() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shot(act.window.decorView, "settings")
    }

    @Test
    fun gameScreen() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        seedLibrary()
        val rom = File(Storage(RuntimeEnvironment.getApplication()).romDir, "Zeta Racer.nes")
        val intent = Intent(RuntimeEnvironment.getApplication(), GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, rom.path)
        val controller = Robolectric.buildActivity(GameActivity::class.java, intent).setup()
        val act = controller.get()
        shot(act.window.decorView, "game")
        act.onMenu()
        shot(act.window.decorView, "game_menu")
        controller.pause().stop().destroy()
    }
}

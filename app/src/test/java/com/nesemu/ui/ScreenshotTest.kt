package com.nesemu.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Renders the app's screens to build/screens/ for visual review and for the website.
 * Uses original pixel-art scenes from website/art-src as stand-in game pictures.
 * Opt-in: set NES_SCREENSHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w411dp-h891dp-port-xxhdpi")
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }
    private val art = File("../website/art-src")
    private val app get() = RuntimeEnvironment.getApplication()

    private fun shot(view: View, name: String) {
        ShadowLooper.idleMainLooper()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun artPixels(name: String): IntArray {
        val bmp = BitmapFactory.decodeFile(File(art, "$name.png").path)
        val px = IntArray(256 * 240)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 240)
        return px
    }

    private val games = listOf(
        "Gece Ormanı.nes" to "platformer",
        "Yıldız Avcısı.nes" to "space",
        "Turbo Pist.nes" to "racing",
        "Mücevher Kulesi.nes" to "puzzle",
        "Kale Muhafızı.nes" to null,
        "Ninja Koşusu.nes" to null,
    )

    private fun seedLibrary(): Storage {
        val storage = Storage(app)
        val src = File("src/test/resources/roms/nestest.nes")
        games.forEachIndexed { i, (name, cover) ->
            val rom = File(storage.romDir, name)
            src.copyTo(rom, overwrite = true)
            if (cover != null) {
                storage.writePng(storage.coverFile(rom), artPixels(cover))
                storage.recordSession(rom, longArrayOf(9000, 3600, 1500, 600)[i])
            }
        }
        // Most recent first: the platformer gets the "continue" card.
        Thread.sleep(20)
        storage.recordSession(File(storage.romDir, "Gece Ormanı.nes"), 1)
        storage.setFavorite(File(storage.romDir, "Turbo Pist.nes"), true)
        storage.setFavorite(File(storage.romDir, "Gece Ormanı.nes"), true)
        return storage
    }

    /** Puts a pixel-art picture where the game is drawn (the real SurfaceView renders nothing here). */
    private fun showArt(act: Activity, scene: String) {
        val root = GameActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(act) as FrameLayout
        val bmp = BitmapFactory.decodeFile(File(art, "$scene.png").path)
        val rect = ScreenLayout.gameRect(root.width, root.height, act.resources.displayMetrics.density)
        val iv = ImageView(act).apply {
            setImageDrawable(BitmapDrawable(act.resources, bmp).apply { setFilterBitmap(false) })
            scaleType = ImageView.ScaleType.FIT_XY
        }
        root.addView(iv, 1, FrameLayout.LayoutParams(rect.width().toInt(), rect.height().toInt()).apply {
            leftMargin = rect.left.toInt(); topMargin = rect.top.toInt()
        })
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    private fun startGame(storage: Storage, name: String) = Robolectric.buildActivity(
        GameActivity::class.java,
        Intent(app, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM, File(storage.romDir, name).path)
    ).setup()

    @Test
    fun library() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        seedLibrary()
        shot(Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView, "library")
    }

    @Test
    fun settings() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        shot(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView, "settings")
    }

    @Test
    fun online() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        seedLibrary()
        shot(Robolectric.buildActivity(OnlineActivity::class.java).setup().get().window.decorView, "online")
    }

    @Test
    fun gamePortrait() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        val storage = seedLibrary()
        val c = startGame(storage, "Gece Ormanı.nes")
        val act = c.get()
        showArt(act, "platformer")
        shot(act.window.decorView, "game")
        act.onMenu()
        shot(act.window.decorView, "game_menu")
        // The online room screen, as the host sees it before a friend joins.
        GameActivity::class.java.getDeclaredMethod("closeAllOverlays").apply { isAccessible = true }.invoke(act)
        GameActivity::class.java.getDeclaredField("roomCode").apply { isAccessible = true }.set(act, "4829")
        GameActivity::class.java.getDeclaredMethod("showWaitingForFriend").apply { isAccessible = true }.invoke(act)
        shot(act.window.decorView, "online_room")
        c.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "tr-w891dp-h411dp-land-xxhdpi")
    fun gameLandscape() {
        assumeTrue(System.getenv("NES_SCREENSHOTS") != null)
        val storage = seedLibrary()
        val c = startGame(storage, "Yıldız Avcısı.nes")
        val act = c.get()
        showArt(act, "space")
        act.onFastForward()
        shot(act.window.decorView, "game_landscape")
        c.pause().stop().destroy()
    }
}

package fr.guerard.palettewall

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * When the screen turns off (at most once per interval), sets the next Gallery
 * favourite as the wallpaper. At the next unlock it refreshes One UI's colour
 * palette, hidden behind an overlay showing the new wallpaper:
 *
 *  Wallpaper and style -> Colour palette ->
 *  switch OFF + Apply -> switch ON, icons option, Colour 1 + Apply -> home.
 *
 * One UI only recomputes the palette from a wallpaper set by another app after
 * that off/apply/on/apply round trip.
 *
 * Buttons are found by their resource IDs (from a uiautomator dump on One UI 9),
 * so the flow does not depend on the phone's language. Instead of fixed delays,
 * each step polls the screen until the expected change has happened.
 */
class PaletteService : AccessibilityService() {

    /** NAVIGATE: reacting to screen events. WAIT: polling for a specific change. */
    private enum class Step { IDLE, NAVIGATE, WAIT }
    private enum class Phase { OFF, ON }

    private val main = Handler(Looper.getMainLooper())
    private var step = Step.IDLE
    private var phase = Phase.OFF
    private var pending: Runnable? = null
    private var overlay: View? = null
    private var iconsCycled = false
    private val hideRunnable = Runnable { hideOverlay() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.i(TAG, "${i.action} (enabled=${Prefs.enabled(c)}, step=$step, due=${Prefs.isDue(c)}, pending=${Prefs.palettePending(c)})")
            if (!Prefs.enabled(c) || step != Step.IDLE) return
            when (i.action) {
                // Change the picture while nobody is looking.
                Intent.ACTION_SCREEN_OFF -> if (Prefs.isDue(c)) prepareWallpaper()
                // Then fix the colours right after unlocking.
                Intent.ACTION_USER_PRESENT -> when {
                    Prefs.palettePending(c) -> main.postDelayed({ refreshPalette() }, 400)
                    Prefs.isDue(c) -> main.postDelayed({ runNow() }, 400)
                }
            }
        }
    }

    private val timeout = Runnable {
        Log.w(TAG, "Palette flow timed out (phase $phase)")
        finish(success = false)
    }

    override fun onServiceConnected() {
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        hideOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun prepareWallpaper() {
        // The CPU may sleep right after the screen turns off, freezing the decode.
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Chameleon:wallpaper")
        lock.acquire(60_000)
        Thread {
            try {
                if (Wallpapers.setNextFavourite(this) != null) Prefs.setPalettePending(this, true)
            } finally {
                if (lock.isHeld) lock.release()
            }
        }.start()
    }

    /** Changes the wallpaper now and then refreshes the palette. */
    fun runNow() {
        if (step != Step.IDLE) return
        step = Step.WAIT // busy while the wallpaper is being set
        Thread {
            val id = Wallpapers.setNextFavourite(this)
            main.post {
                step = Step.IDLE
                if (id == null) {
                    Toast.makeText(this, "No favourite could be set", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setPalettePending(this, true)
                    refreshPalette()
                }
            }
        }.start()
    }

    /** Refreshes the palette for the current wallpaper. */
    fun refreshPalette() {
        if (step != Step.IDLE) return
        // The "Wallpaper and style" screen Settings opens (it has no launcher entry).
        val launch = Intent().setClassName(DRESSROOM, "$DRESSROOM.presentation.settings.WallpaperSettingActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        step = Step.NAVIGATE
        phase = Phase.OFF
        iconsCycled = false
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 25_000)
        if (Prefs.hideFlash(this)) showOverlay()
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open Wallpaper and style", e)
            finish(success = false)
            Toast.makeText(this, "Cannot open Wallpaper and style (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (step != Step.NAVIGATE || event?.packageName?.toString() != DRESSROOM) return
        // Wait until the screen stops changing before acting.
        schedule(250) { advance() }
    }

    // --- flow ---------------------------------------------------------------

    private fun advance() {
        if (step != Step.NAVIGATE) return
        val root = root() ?: return

        // The palette screen is recognised by its on/off bar, which stays
        // visible even while the palette is switched off.
        if (switchBar(root) == null) {
            // Main "Wallpaper and style" screen: open Colour palette.
            byId(root, "color_icon")?.let { clickUp(it) }
            return
        }
        val toggle = paletteToggle(root) ?: return

        when (phase) {
            Phase.OFF -> if (toggle.isChecked) {
                clickUp(toggle)
                waitFor({ paletteToggle(it)?.isChecked == false }) { advance() }
            } else {
                waitFor({ applyEnabled(it) }) {
                    pressApply("palette off")
                    phase = Phase.ON
                    // Done when the change is saved (Apply greys out) or the screen closes.
                    waitFor({ !applyEnabled(it) || switchBar(it) == null }, 2500) { advance() }
                }
            }

            Phase.ON -> {
                if (!toggle.isChecked) {
                    clickUp(toggle)
                    waitFor({ paletteToggle(it)?.isChecked == true && byId(it, "color_palette_container") != null }) { advance() }
                    return
                }
                // "Apply palette to app icons", as chosen in the app.
                val wantIcons = Prefs.iconsToo(this)
                val icons = iconToggle(root)
                Log.i(TAG, "Icons switch: found=${icons != null}, checked=${icons?.isChecked}, want=$wantIcons")
                if (icons != null) {
                    // One UI only re-themes the icons when this switch changes in the
                    // same Apply, so if it is already on, flip it off and on again.
                    if (wantIcons && icons.isChecked && !iconsCycled) {
                        iconsCycled = true
                        clickUp(icons)
                        waitFor({ iconToggle(it)?.isChecked == false }) { advance() }
                        return
                    }
                    if (icons.isChecked != wantIcons) {
                        iconsCycled = true
                        clickUp(icons)
                        waitFor({ iconToggle(it)?.isChecked == wantIcons }) { advance() }
                        return
                    }
                }
                // The first tab is "wallpaper colours" (the second is "basic colours").
                val firstTab = firstTab(root)
                if (firstTab != null && !firstTab.isSelected) {
                    clickUp(firstTab)
                    waitFor({ firstTab(it)?.isSelected == true }) { advance() }
                    return
                }
                val container = byId(root, "color_palette_container")
                if (container == null) { Log.i(TAG, "Palette list not found yet"); return }
                // Show the first page of palettes, then pick Colour 1 and apply.
                container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                later(250) {
                    val first = root()?.let { byId(it, "color_palette_container") }?.let { firstClickable(it) }
                    Log.i(TAG, "Colour 1 found=${first != null}, clicked=${first?.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    waitFor({ applyEnabled(it) }) { ok ->
                        val applied = ok && pressApply("palette on")
                        waitFor({ !applyEnabled(it) || switchBar(it) == null }, 2500) {
                            // Only count it as done if Apply was really pressed; otherwise
                            // the palette stays pending and the next unlock retries.
                            finish(success = applied)
                        }
                    }
                }
            }
        }
    }

    /** Presses Apply if it is enabled; returns whether the click was sent. */
    private fun pressApply(label: String): Boolean {
        val button = root()?.let { byId(it, "bottom_button") }
        val sent = button != null && button.isEnabled &&
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, if (sent) "Applied ($label)" else "Apply not enabled ($label)")
        return sent
    }

    private fun finish(success: Boolean) {
        main.removeCallbacks(timeout)
        pending?.let { main.removeCallbacks(it) }
        step = Step.IDLE
        if (success) Prefs.setPalettePending(this, false)
        performGlobalAction(GLOBAL_ACTION_HOME)
        // One UI re-themes the home screen icons a moment after Apply; keep the
        // cover up until that has finished so the icons don't change in patches.
        main.postDelayed(hideRunnable, if (success) ICONS_SETTLE_MS else 350)
    }

    // --- timing -------------------------------------------------------------

    private fun schedule(delayMs: Long, block: () -> Unit) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { block() }
        pending = r
        main.postDelayed(r, delayMs)
    }

    /** Runs [block] after [delayMs], ignoring screen events in the meantime. */
    private fun later(delayMs: Long, block: () -> Unit) {
        step = Step.WAIT
        schedule(delayMs) { step = Step.NAVIGATE; block() }
    }

    /**
     * Polls the screen every 80 ms until [done] is true (or [maxMs] passes), then
     * runs [then] with whether the condition was met.
     */
    private fun waitFor(
        done: (AccessibilityNodeInfo) -> Boolean,
        maxMs: Long = 1500,
        then: (Boolean) -> Unit,
    ) {
        step = Step.WAIT
        val deadline = System.currentTimeMillis() + maxMs
        fun poll() {
            val ok = root()?.let { runCatching { done(it) }.getOrDefault(false) } ?: false
            if (ok || System.currentTimeMillis() >= deadline) {
                step = Step.NAVIGATE
                then(ok)
            } else {
                schedule(80) { poll() }
            }
        }
        schedule(80) { poll() }
    }

    // --- overlay ------------------------------------------------------------

    /** Covers the screen with the new wallpaper while the taps run underneath. */
    private fun showOverlay() {
        main.removeCallbacks(hideRunnable) // a new run cancels a pending hide
        if (overlay != null) return
        val view = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = Wallpapers.previewFile(this)
        if (preview.exists()) {
            view.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(preview.path))
            })
        }
        view.addView(
            TextView(this).apply {
                text = "Matching colours…"
                setTextColor(Color.WHITE)
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                textSize = 15f
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (64 * resources.displayMetrics.density).toInt() }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching {
            getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
        }.onFailure { Log.w(TAG, "Overlay not shown", it) }
    }

    private fun hideOverlay() {
        overlay?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        overlay = null
    }

    // --- node helpers -------------------------------------------------------

    /** The Wallpaper and style window, even while our overlay is on top. */
    private fun root(): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == DRESSROOM }?.let { return it }
        return windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == DRESSROOM }
    }

    /** First node with this dressroom resource ID inside [node]'s subtree. */
    private fun byId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        node.findAccessibilityNodeInfosByViewId("$DRESSROOM:id/$id").firstOrNull()

    private fun switchBar(root: AccessibilityNodeInfo) = byId(root, "color_theme_switch_bar")
    private fun paletteToggle(root: AccessibilityNodeInfo) = switchBar(root)?.let { byId(it, "switch_toggle") }
    private fun iconToggle(root: AccessibilityNodeInfo) = byId(root, "switch_app_icon")?.let { byId(it, "switch_toggle") }
    private fun firstTab(root: AccessibilityNodeInfo) = byId(root, "color_list_tab_layout")?.getChild(0)?.getChild(0)
    private fun applyEnabled(root: AccessibilityNodeInfo) = byId(root, "bottom_button")?.isEnabled == true

    /** Clicks the node, or its nearest clickable ancestor. */
    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    /** Depth-first: the first clickable descendant ("Colour 1"). */
    private fun firstClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable) return child
            firstClickable(child)?.let { return it }
        }
        return null
    }

    companion object {
        /** How long the cover stays up after a successful run (ponytail: tune on the phone). */
        const val ICONS_SETTLE_MS = 1800L
        const val DRESSROOM = "com.samsung.android.app.dressroom"
        var instance: PaletteService? = null
            private set
    }
}

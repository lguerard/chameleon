package fr.guerard.palettewall

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * After each unlock (at most once per interval):
 *  1. sets a random Gallery favourite as the home screen wallpaper,
 *  2. opens Samsung's "Wallpaper and style" -> Colour palette,
 *  3. switches the palette OFF and taps Apply,
 *  4. switches it back ON, sets "apply to app icons", picks the first
 *     wallpaper palette and taps Apply,
 *  5. goes back to the home screen.
 *
 * One UI only recomputes the system palette from a wallpaper set by another app
 * after this off/apply/on/apply round trip.
 *
 * Buttons are found by their resource IDs (from a uiautomator dump on One UI 9),
 * so the flow does not depend on the phone's language.
 */
class PaletteService : AccessibilityService() {

    /** NAVIGATE: getting to the palette screen. WAIT: a delayed action is pending. */
    private enum class Step { IDLE, NAVIGATE, WAIT }
    private enum class Phase { OFF, ON }

    private val main = Handler(Looper.getMainLooper())
    private var step = Step.IDLE
    private var phase = Phase.OFF
    private var pending: Runnable? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT && Prefs.enabled(c) && Prefs.isDue(c)) {
                // Let the unlock animation finish first.
                main.postDelayed({ runNow() }, 800)
            }
        }
    }

    private val timeout = Runnable {
        Log.w(TAG, "Palette flow timed out (step $step, phase $phase)")
        finish()
    }

    override fun onServiceConnected() {
        instance = this
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Changes the wallpaper and then refreshes the palette. */
    fun runNow() {
        if (step != Step.IDLE) return
        step = Step.WAIT // busy while the wallpaper is being set
        Thread {
            val id = Wallpapers.setRandomFavourite(this)
            main.post {
                if (id == null) {
                    step = Step.IDLE
                    Toast.makeText(this, "Palette Wallpaper: no favourite could be set", Toast.LENGTH_LONG).show()
                } else {
                    openWallpaperAndStyle()
                }
            }
        }.start()
    }

    /** Only refreshes the palette for the current wallpaper. */
    fun fixPaletteOnly() {
        if (step != Step.IDLE) return
        openWallpaperAndStyle()
    }

    private fun openWallpaperAndStyle() {
        // The "Wallpaper and style" screen Settings opens (it has no launcher entry).
        val launch = Intent().setClassName(DRESSROOM, "$DRESSROOM.presentation.settings.WallpaperSettingActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        step = Step.NAVIGATE
        phase = Phase.OFF
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 30_000)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open Wallpaper and style", e)
            main.removeCallbacks(timeout)
            step = Step.IDLE
            Toast.makeText(this, "Palette Wallpaper: cannot open Wallpaper and style (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (step != Step.NAVIGATE || event?.packageName?.toString() != DRESSROOM) return
        // Wait until the screen stops changing before acting.
        schedule(350) { advance() }
    }

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

    private fun advance() {
        if (step != Step.NAVIGATE) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DRESSROOM) return

        // The palette screen is recognised by its on/off bar, which stays
        // visible even while the palette is switched off.
        val switchBar = byId(root, "color_theme_switch_bar")
        if (switchBar == null) {
            // Main "Wallpaper and style" screen: open Colour palette.
            byId(root, "color_icon")?.let { clickUp(it) }
            return
        }
        val paletteToggle = byId(switchBar, "switch_toggle") ?: return

        when (phase) {
            Phase.OFF -> {
                if (paletteToggle.isChecked) {
                    clickUp(paletteToggle)
                    later(800) { advance() }
                } else {
                    later(300) {
                        pressApply("palette off")
                        phase = Phase.ON
                        // Apply may close the screen; advance() re-enters it if so.
                        later(1500) { advance() }
                    }
                }
            }

            Phase.ON -> {
                if (!paletteToggle.isChecked) {
                    clickUp(paletteToggle)
                    later(900) { advance() }
                    return
                }
                // "Apply palette to app icons", as chosen in the app.
                val iconToggle = byId(root, "switch_app_icon")?.let { byId(it, "switch_toggle") }
                if (iconToggle != null && iconToggle.isChecked != Prefs.iconsToo(this)) {
                    clickUp(iconToggle)
                    later(600) { advance() }
                    return
                }
                val container = byId(root, "color_palette_container") ?: return
                // The first tab is "wallpaper colours" (the second is "basic colours").
                val firstTab = byId(root, "color_list_tab_layout")?.getChild(0)?.getChild(0)
                if (firstTab != null && !firstTab.isSelected) {
                    clickUp(firstTab)
                    later(700) { advance() }
                    return
                }
                // Show the first page of palettes, then pick the first one and apply.
                container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                later(500) {
                    val fresh = rootInActiveWindow?.let { byId(it, "color_palette_container") }
                    fresh?.let { firstClickable(it) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    later(800) {
                        pressApply("palette on")
                        later(1200) { finish() }
                    }
                }
            }
        }
    }

    private fun pressApply(label: String) {
        val button = rootInActiveWindow?.let { byId(it, "bottom_button") }
        if (button != null && button.isEnabled) {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Applied ($label)")
        } else {
            Log.i(TAG, "Apply not enabled ($label)")
        }
    }

    private fun finish() {
        main.removeCallbacks(timeout)
        pending?.let { main.removeCallbacks(it) }
        step = Step.IDLE
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // --- node helpers -------------------------------------------------------

    /** First node with this dressroom resource ID inside [node]'s subtree. */
    private fun byId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        node.findAccessibilityNodeInfosByViewId("$DRESSROOM:id/$id").firstOrNull()

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
            if (child.isClickable && child.isVisibleToUser) return child
            firstClickable(child)?.let { return it }
        }
        return null
    }

    companion object {
        const val DRESSROOM = "com.samsung.android.app.dressroom"
        var instance: PaletteService? = null
            private set
    }
}
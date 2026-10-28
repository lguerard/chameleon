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
 *  2. opens Samsung's "Wallpaper and style",
 *  3. taps Colour palette -> wallpaper colours tab -> first palette -> Apply,
 *  4. goes back to the home screen.
 *
 * Buttons are found by their resource IDs (from a uiautomator dump on One UI 9),
 * so the flow does not depend on the phone's language.
 */
class PaletteService : AccessibilityService() {

    private enum class Step { IDLE, OPEN_MAIN, OPEN_PALETTE, PALETTE_RESET, PICK, APPLY }

    private val main = Handler(Looper.getMainLooper())
    private var step = Step.IDLE
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
        Log.w(TAG, "Palette flow timed out in step $step")
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

    /** Changes the wallpaper and then fixes the palette. */
    fun runNow() {
        if (step != Step.IDLE) return
        step = Step.OPEN_MAIN // mark busy while the wallpaper is being set
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

    /** Only re-applies the palette for the current wallpaper. */
    fun fixPaletteOnly() {
        if (step != Step.IDLE) return
        openWallpaperAndStyle()
    }

    private fun openWallpaperAndStyle() {
        val launch = packageManager.getLaunchIntentForPackage(DRESSROOM)
        if (launch == null) {
            step = Step.IDLE
            Toast.makeText(this, "Palette Wallpaper: Wallpaper and style app not found", Toast.LENGTH_LONG).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        step = Step.OPEN_MAIN
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 15_000)
        startActivity(launch)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (step == Step.IDLE || event?.packageName?.toString() != DRESSROOM) return
        if (step == Step.APPLY) return // handled by its own delayed check
        // Wait until the screen stops changing before acting.
        schedule(350) { advance() }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { block() }
        pending = r
        main.postDelayed(r, delayMs)
    }

    private fun advance() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DRESSROOM) return

        val paletteContainer = byId(root, "color_palette_container")

        when {
            // Main "Wallpaper and style" screen: open Colour palette.
            paletteContainer == null && (step == Step.OPEN_MAIN || step == Step.OPEN_PALETTE) -> {
                val entry = byId(root, "color_icon") ?: return
                if (clickUp(entry)) step = Step.OPEN_PALETTE
            }

            paletteContainer != null && (step == Step.OPEN_MAIN || step == Step.OPEN_PALETTE) -> {
                // Palette must be switched on.
                val switchBar = byId(root, "color_theme_switch_bar")
                val toggle = switchBar?.let { firstById(it, "switch_toggle") }
                if (toggle != null && !toggle.isChecked) {
                    clickUp(toggle)
                    return
                }
                // The first tab is "wallpaper colours" (the second is "basic colours").
                val firstTab = byId(root, "color_list_tab_layout")?.getChild(0)?.getChild(0)
                if (firstTab != null && !firstTab.isSelected) {
                    clickUp(firstTab)
                    return
                }
                // Make sure the first page of palettes is showing.
                paletteContainer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                step = Step.PALETTE_RESET
                schedule(500) { advance() }
            }

            paletteContainer != null && step == Step.PALETTE_RESET -> {
                val first = firstClickable(paletteContainer) ?: return
                first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                step = Step.APPLY
                main.postDelayed({ apply() }, 800)
            }
        }
    }

    private fun apply() {
        val root = rootInActiveWindow
        val button = root?.let { byId(it, "bottom_button") }
        if (button != null && button.isEnabled) {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Palette applied")
        } else {
            // Already the selected palette: nothing to apply.
            Log.i(TAG, "Apply not enabled, palette unchanged")
        }
        main.postDelayed({ finish() }, 1200)
    }

    private fun finish() {
        main.removeCallbacks(timeout)
        pending?.let { main.removeCallbacks(it) }
        step = Step.IDLE
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // --- node helpers -------------------------------------------------------

    private fun byId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$DRESSROOM:id/$id").firstOrNull()

    private fun firstById(node: AccessibilityNodeInfo, id: String) = byId(node, id)

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

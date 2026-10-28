package fr.guerard.palettewall

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    private val dark by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val bg by lazy { if (dark) 0xFF101414.toInt() else 0xFFF3F6F4.toInt() }
    private val card by lazy { if (dark) 0xFF1C2222.toInt() else Color.WHITE }
    private val fg by lazy { if (dark) 0xFFE6EAE8.toInt() else 0xFF1A1F1D.toInt() }
    private val subtext by lazy { if (dark) 0xFF9AA5A1.toInt() else 0xFF5F6B67.toInt() }
    private val accent by lazy {
        if (Build.VERSION.SDK_INT >= 31)
            getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        else 0xFF1E9E7A.toInt()
    }
    private val ok = 0xFF43A047.toInt()
    private val bad = 0xFFE53935.toInt()

    private lateinit var setup: LinearLayout
    private lateinit var lastChange: TextView

    private val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val grantCommand =
        "adb shell pm grant fr.guerard.palettewall android.permission.WRITE_SECURE_SETTINGS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(24))
        }

        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.logo)
                background = rounded(card, 18)
                clipToOutline = true // rounded corners from the background shape
                layoutParams = LinearLayout.LayoutParams(px(72), px(72)).apply { marginEnd = px(14) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    this.text = "Chameleon"
                    textSize = 30f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accent)
                })
                addView(TextView(context).apply {
                    this.text = "Your Gallery favourites, with matching system colours."
                    textSize = 14f
                    setTextColor(subtext)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        setup = section(col, "Setup")

        val options = section(col, "Behaviour")
        options.addView(switchRow(
            "Change automatically",
            "Picture at screen off, colours at unlock",
            Prefs.enabled(this)
        ) { Prefs.setEnabled(this, it) })
        options.addView(switchRow(
            "Also set the lock screen", null, Prefs.lockToo(this)
        ) { Prefs.setLockToo(this, it) })
        options.addView(switchRow(
            "Apply the palette to app icons", null, Prefs.iconsToo(this)
        ) { Prefs.setIconsToo(this, it) })
        options.addView(switchRow(
            "Hide Wallpaper and style",
            "Cover the screen with the new picture while colours update",
            Prefs.hideFlash(this)
        ) { Prefs.setHideFlash(this, it) })

        val interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.intervalMinutes(this@MainActivity).toString())
            setTextColor(fg)
            gravity = Gravity.CENTER
            // Saved as you type; no separate button to forget.
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) {
                    e?.toString()?.toIntOrNull()?.let { Prefs.setIntervalMinutes(this@MainActivity, it) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            layoutParams = LinearLayout.LayoutParams(px(80), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val intervalRow = row().apply {
            addView(label("Minimum minutes between changes", null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(interval)
        }
        options.addView(intervalRow)

        val actions = section(col, "Actions")
        lastChange = TextView(this).apply {
            textSize = 14f
            setTextColor(subtext)
            setPadding(0, px(8), 0, px(8))
        }
        actions.addView(lastChange)
        actions.addView(bigButton("Change wallpaper now", true) {
            val s = PaletteService.instance
            if (s == null) toast("Turn on the accessibility service first") else s.runNow()
        })
        actions.addView(bigButton("Only re-apply the palette", false) {
            val s = PaletteService.instance
            if (s == null) toast("Turn on the accessibility service first") else s.refreshPalette()
        })

        actions.addView(bigButton("Copy debug log", false) {
            // An app can read its own log lines without adb.
            val log = runCatching {
                Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "Chameleon")).inputStream
                    .bufferedReader().readText().lines().takeLast(120).joinToString("\n")
            }.getOrDefault("")
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Chameleon log", log))
            toast(if (log.isBlank()) "Log is empty" else "Log copied: paste it somewhere")
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(col)
            // Edge-to-edge on Android 15: keep content clear of the system bars.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                col.setPadding(px(16), bars.top + px(16), px(16), bars.bottom + px(24))
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    /** Rebuilds the setup checklist; tapping a missing item does the fix. */
    private fun refresh() {
        lastChange.text = lastChangeText()
        setup.removeAllViews()
        val hasPhotos = checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED
        val favs = if (hasPhotos) runCatching { Wallpapers.favouriteIds(this).size }.getOrDefault(-1) else -1
        val secure = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        setup.addView(statusRow(
            hasPhotos,
            if (hasPhotos) "Photo access" else "Allow photo access",
            if (hasPhotos) (if (favs >= 0) "$favs favourites found" else "Favourites unknown")
            else "Tap and choose \"Allow all\""
        ) { if (!hasPhotos) requestPermissions(arrayOf(mediaPermission), 1) })

        val service = PaletteService.instance != null
        setup.addView(statusRow(
            service,
            if (service) "Accessibility service on" else "Turn on the accessibility service",
            if (service) null else "Tap, then enable Chameleon"
        ) { if (!service) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        setup.addView(statusRow(
            secure,
            if (secure) "Can open Wallpaper and style" else "Grant secure settings once",
            if (secure) null else "Tap to copy the adb command, run it on a computer"
        ) {
            if (!secure) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("adb", grantCommand))
                toast("Command copied")
            }
        })
    }

    /** "Last change: 12 minutes ago · next in 108 min" (or "due at the next screen off"). */
    private fun lastChangeText(): String {
        val last = Prefs.lastChange(this)
        if (last == 0L) return "No wallpaper change yet"
        val now = System.currentTimeMillis()
        val ago = DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS)
        val left = (last + Prefs.intervalMinutes(this) * 60_000L - now) / 60_000
        return "Last change: $ago · " +
            if (left > 0) "next possible in $left min" else "due at the next screen off"
    }

    // --- view helpers -------------------------------------------------------

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * dp
    }

    /** Adds a titled card to [parent] and returns its content column. */
    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            this.text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.1f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subtext)
            setPadding(px(4), px(16), 0, px(6))
        })
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            setPadding(px(16), px(8), px(16), px(8))
        }
        parent.addView(inner)
        return inner
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(8), 0, px(8))
    }

    private fun label(title: String, subtitle: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { this.text = title; textSize = 16f; setTextColor(fg) })
        if (subtitle != null) addView(TextView(context).apply {
            this.text = subtitle; textSize = 13f; setTextColor(subtext)
        })
    }

    private fun switchRow(title: String, subtitle: String?, on: Boolean, onChange: (Boolean) -> Unit) =
        row().apply {
            addView(label(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(context).apply {
                isChecked = on
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    private fun statusRow(good: Boolean, title: String, subtitle: String?, onClick: () -> Unit) =
        row().apply {
            addView(TextView(context).apply {
                this.text = if (good) "✓" else "!"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(if (good) ok else bad, 100)
                layoutParams = LinearLayout.LayoutParams(px(26), px(26)).apply { marginEnd = px(14) }
            })
            addView(label(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        }

    private fun bigButton(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) (if (dark) Color.BLACK else Color.WHITE) else accent)
        background = if (primary) rounded(accent, 14) else GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(2), accent)
            cornerRadius = 14 * dp
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, px(52)
        ).apply { topMargin = px(6); bottomMargin = px(6) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

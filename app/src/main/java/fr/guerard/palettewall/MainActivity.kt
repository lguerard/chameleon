package fr.guerard.palettewall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        col.addView(status)

        col.addView(button("1. Allow photo access (choose \"Allow all\")") {
            requestPermissions(arrayOf(mediaPermission), 1)
        })
        col.addView(button("2. Turn on the accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(CheckBox(this).apply {
            text = "Change automatically after unlocking"
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this@MainActivity, v) }
        })
        col.addView(CheckBox(this).apply {
            text = "Also set the lock screen"
            isChecked = Prefs.lockToo(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setLockToo(this@MainActivity, v) }
        })

        col.addView(TextView(this).apply { text = "Minimum minutes between changes" })
        val interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.intervalMinutes(this@MainActivity).toString())
        }
        col.addView(interval)
        col.addView(button("Save interval") {
            val v = interval.text.toString().toIntOrNull()
            if (v == null || v < 0) toast("Enter a number of minutes")
            else { Prefs.setIntervalMinutes(this, v); toast("Saved") }
        })

        col.addView(button("Change wallpaper now") {
            val s = PaletteService.instance
            if (s == null) toast("Turn on the accessibility service first") else s.runNow()
        })
        col.addView(button("Only re-apply the palette") {
            val s = PaletteService.instance
            if (s == null) toast("Turn on the accessibility service first") else s.fixPaletteOnly()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun refresh() {
        val hasPhotos = checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED
        val favs = if (hasPhotos) runCatching { Wallpapers.favouriteIds(this).size }.getOrDefault(-1) else -1
        val service = PaletteService.instance != null
        status.text = buildString {
            appendLine(if (hasPhotos) "✓ Photo access" else "✗ Photo access missing")
            appendLine(if (favs >= 0) "Favourites found: $favs" else "Favourites: unknown")
            appendLine(if (service) "✓ Accessibility service on" else "✗ Accessibility service off")
            val secure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
            appendLine(
                if (secure) "✓ Can open Wallpaper and style"
                else "✗ Run once on a computer:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
            )
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

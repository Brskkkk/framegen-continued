package com.idk.frameinterp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var resSpinner: Spinner
    private lateinit var overlayModeSpinner: Spinner
    private lateinit var prefs: SharedPreferences

    private data class AppEntry(val label: String, val packageName: String, val icon: Drawable)
    private var pendingPackage: String? = null

    private val modeOptions = listOf(
        "Block Motion (CPU) \u2013 balanced" to Prefs.MODE_BLOCK_MOTION,
        "Optical Flow (CPU) \u2013 sharpest" to Prefs.MODE_OPTICAL_FLOW,
        "Frame Blend (CPU) \u2013 simplest" to Prefs.MODE_FRAME_BLEND,
        "GLSL Shader (GPU) \u2013 motion warp" to Prefs.MODE_GLSL_SHADER,
        "Lite Blend (GPU) \u2013 lightest & smoothest" to Prefs.MODE_LITE_BLEND,
        "Grid Warp (GPU) \u2013 block matching mesh" to Prefs.MODE_GRID_WARP,
        "Nearest Frame (CPU) \u2013 zero-compute" to Prefs.MODE_NEAREST_FRAME,
        "Global Shift (CPU) \u2013 one search only" to Prefs.MODE_GLOBAL_SHIFT,
        "Dither Blend (CPU) \u2013 no blend math" to Prefs.MODE_DITHER_BLEND,
        "Downscaled Blend (GPU) \u2013 quarter-res shading" to Prefs.MODE_DOWNSCALED_BLEND,
        "Vulkan Warp (GPU/Vulkan) \u2013 native compute" to Prefs.MODE_VULKAN_WARP,
        "Half Blend (CPU) \u2013 bit-trick 50/50" to Prefs.MODE_HALF_BLEND_CPU,
        "Half Blend (GPU) \u2013 fixed 50/50 shader" to Prefs.MODE_HALF_BLEND_GPU,
        "Alternate Frame (CPU) \u2013 toggle, no math" to Prefs.MODE_ALTERNATE_FRAME_CPU,
        "Alternate Frame (GPU) \u2013 toggle, no mix()" to Prefs.MODE_ALTERNATE_FRAME_GPU,
        "Checkerboard (CPU) \u2013 2x2 pixel pick" to Prefs.MODE_CHECKERBOARD_CPU,
        "Checkerboard (GPU) \u2013 discrete pixel pick" to Prefs.MODE_CHECKERBOARD_GPU,
        "Quarter-Res Blend (CPU) \u2013 1/16th the pixels" to Prefs.MODE_QUARTER_RES_CPU,
        "Quarter-Res Blend (GPU) \u2013 1/16th the shading" to Prefs.MODE_QUARTER_RES_GPU,
        "Scanline Blend (CPU) \u2013 whole-row copies" to Prefs.MODE_SCANLINE_CPU,
        "Scanline Blend (GPU) \u2013 discrete row pick" to Prefs.MODE_SCANLINE_GPU,
        "Diff-Adaptive Blend (CPU) \u2013 no motion search at all" to Prefs.MODE_DIFF_ADAPTIVE,
        "Edge Warp (CPU) \u2013 blend only near edges" to Prefs.MODE_EDGE_WARP,
        "Motion Blur (CPU) \u2013 velocity-vector blur on fast pans" to Prefs.MODE_MOTION_BLUR
    )

    private var resOptions: List<Pair<String, Int>> = emptyList()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
        val pkg = pendingPackage
        if (pkg != null && hasOverlayPermission()) {
            pendingPackage = null
            launchAndStart(pkg)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort - foreground notification just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        resOptions = buildResOptions()

        statusText = TextView(this).apply {
            setPadding(48, 32, 48, 16)
            gravity = Gravity.CENTER
        }

        val modeLabel = TextView(this).apply {
            text = "Interpolation mode"
            setPadding(48, 16, 48, 0)
        }
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modeOptions.map { it.first }
            )
            val savedMode = prefs.getString(Prefs.KEY_INTERP_MODE, Prefs.DEFAULT_MODE)
            setSelection(modeOptions.indexOfFirst { it.second == savedMode }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(Prefs.KEY_INTERP_MODE, modeOptions[pos].second).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val resLabel = TextView(this).apply {
            text = "Capture resolution"
            setPadding(48, 24, 48, 0)
        }
        resSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resOptions.map { it.first }
            )
            val savedRes = prefs.getInt(Prefs.KEY_CAPTURE_LONG_SIDE, Prefs.DEFAULT_CAPTURE_LONG_SIDE)
            setSelection(resOptions.indexOfFirst { it.second == savedRes }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt(Prefs.KEY_CAPTURE_LONG_SIDE, resOptions[pos].second).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val pickLabel = TextView(this).apply {
            text = "Pick a game/app to smooth"
            setPadding(48, 24, 48, 8)
        }

        val overlayModeLabel = TextView(this).apply {
            text = "Overlay display mode"
            setPadding(48, 24, 48, 0)
        }
        val overlayModeOptions = listOf(
            "Alternating Frames \u2013 show real, then generated, in sequence" to Prefs.OVERLAY_MODE_ALTERNATING,
            "Async Reprojection \u2013 steady-clock redraw, less stutter-prone" to Prefs.OVERLAY_MODE_ASYNC_REPROJECTION
        )
        overlayModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                overlayModeOptions.map { it.first }
            )
            val savedOverlayMode = prefs.getString(Prefs.KEY_OVERLAY_MODE, Prefs.DEFAULT_OVERLAY_MODE)
            setSelection(overlayModeOptions.indexOfFirst { it.second == savedOverlayMode }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(Prefs.KEY_OVERLAY_MODE, overlayModeOptions[pos].second).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val listView = ListView(this)

        val topLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(modeLabel)
            addView(modeSpinner)
            addView(resLabel)
            addView(resSpinner)
            addView(overlayModeLabel)
            addView(overlayModeSpinner)
            addView(pickLabel)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(topLayout)
            addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(root)

        val apps = loadLaunchableApps()
        listView.adapter = AppRowAdapter(apps)
        listView.setOnItemClickListener { _, _, position, _ -> onAppSelected(apps[position].packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = if (hasOverlayPermission()) {
            "Ready. Pick a mode and resolution above, then tap an app below to start."
        } else {
            "\"Draw over other apps\" permission is required.\nTap an app below to grant it."
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun onAppSelected(targetPackage: String) {
        if (!hasOverlayPermission()) {
            pendingPackage = targetPackage
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        launchAndStart(targetPackage)
    }

    private fun launchAndStart(targetPackage: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            Toast.makeText(this, "Can't find a launcher entry for $targetPackage", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ShizukuCaptureEngine(this).isReady()) {
            Toast.makeText(this, "Shizuku isn't running/paired - start it first.", Toast.LENGTH_LONG).show()
        }
        val serviceIntent = Intent(this, FrameInterpService::class.java).apply {
            putExtra(FrameInterpService.EXTRA_TARGET_PACKAGE, targetPackage)
        }
        startForegroundService(serviceIntent)
        startActivity(launchIntent)
        Toast.makeText(this, "Starting - open $targetPackage now", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Prefs.CAPTURE_RESOLUTIONS is a generic preset list with no idea what
     * this device's real screen actually is. Presets at/above native clamp
     * to the same value in ScreenMetrics.getCaptureSpec(), so this filters
     * to presets strictly below native and adds one explicit "Native" entry.
     */
    private fun buildResOptions(): List<Pair<String, Int>> {
        val real = ScreenMetrics.getReal(this)
        val nativeLongSide = maxOf(real.width, real.height)
        val below = Prefs.CAPTURE_RESOLUTIONS.filter { it.second < nativeLongSide }
        val nativeLabel = "Native (${real.width}x${real.height})" to nativeLongSide
        return listOf(nativeLabel) + below
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        return resolved
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { info: ApplicationInfo ->
                AppEntry(
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon = packageManager.getApplicationIcon(info)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private inner class AppRowAdapter(private val apps: List<AppEntry>) : ArrayAdapter<AppEntry>(this@MainActivity, 0, apps) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = apps[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 24, 48, 24)
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(entry.icon)
                layoutParams = LinearLayout.LayoutParams(96, 96).apply { marginEnd = 32 }
            }
            val label = TextView(this@MainActivity).apply {
                text = entry.label
                textSize = 16f
            }
            row.addView(icon)
            row.addView(label)
            return row
        }
    }
}

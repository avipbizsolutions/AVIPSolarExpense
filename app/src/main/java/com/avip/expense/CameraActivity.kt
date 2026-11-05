package com.avip.expense

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The only way photos enter this app. Same as AVIP GPSCam: a 4:3 frame with the
 * GPS stamp drawn live where it will be saved (mini map with pin, address,
 * Lat/Long, facing direction, date & time) and a north arrow, burned into the
 * photo, plus GPS in the photo's EXIF. Gallery photos cannot be used.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIR = "dir"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_MULTI = "multi"
        const val EXTRA_FILES = "files"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        /** true = odometer photo: a guide box on screen, and a clean crop of it saved for reading the number. */
        const val EXTRA_ODOMETER = "odometer"
        private const val PREFS = "camera"
        private const val GALLERY_FOLDER = "AVIP Solar Expense"
    }

    private data class Snapshot(val loc: Location?, val place: PlaceInfo, val time: Date, val heading: Float?)

    private lateinit var dir: File
    private var prefix = "photo"
    private var multi = false
    private val saved = ArrayList<String>()

    private lateinit var previewView: PreviewView
    private lateinit var stampPreview: StampPreviewView
    private lateinit var grid: GridOverlayView
    private lateinit var shutter: View
    private lateinit var thumb: ImageView
    private lateinit var doneBtn: TextView
    private lateinit var gpsLabel: TextView
    private lateinit var gridBtn: TextView
    private lateinit var hdBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var focusRing: View
    private val zoomChips = mutableListOf<Pair<TextView, Float>>()

    private lateinit var heading: HeadingProvider
    private lateinit var tilt: TiltProvider
    private lateinit var lm: LocationManager
    private lateinit var executor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var hd = true
    private var busy = false
    private var lastZoom: Float? = null
    private val hideZoom = Runnable { zoomLabel.visibility = View.GONE }

    private var current: Location? = null
    private var geocoded: Location? = null
    private var place = PlaceInfo.EMPTY
    private var previewMap: Bitmap? = null
    private var previewMapAt: Location? = null
    private var previewMapLoading = false
    private val stampTicker = object : Runnable {
        override fun run() {
            stampPreview.render(previewMap, current, place, Date(), heading.heading)
            stampPreview.postDelayed(this, 500)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera() else { toast(tr("Photo के लिए Camera की अनुमति चाहिए", "Camera permission is needed for photos")); finishWithResult() }
        if (hasLocation()) startLocation() else gpsLabel.text = tr("Location की अनुमति नहीं — photo पर GPS नहीं आएगा", "No location permission: the photo will have no GPS")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return }).apply { mkdirs() }
        prefix = intent.getStringExtra(EXTRA_PREFIX) ?: "photo"
        multi = intent.getBooleanExtra(EXTRA_MULTI, false)
        hd = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("hd", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        executor = Executors.newSingleThreadExecutor()
        heading = HeadingProvider(this) { }
        buildUi()
        tilt = TiltProvider(this) { r -> grid.setRoll(r) }
        applyGrid(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("grid", true))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult()
        })

        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (has(Manifest.permission.CAMERA)) {
            startCamera()
            if (!hasLocation()) perms.launch(need.drop(1).toTypedArray())
        } else perms.launch(need.toTypedArray())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Photo"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        root.addView(horizontal {
            setPadding(dp(6), dp(8), dp(10), dp(8))
            addView(icon(R.drawable.ic_back, Color.WHITE, 40).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { finishWithResult() }
            })
            addView(vertical {
                if (subtitle.isNotBlank()) addView(text(subtitle, 12f, 0xFFB8C4CF.toInt()) { maxLines = 1 })
                addView(text(title, 15f, Color.WHITE, bold = true) { maxLines = 2 })
            }, lp(0, WRAP, 1f).margins(context, l = 4))
            gridBtn = chip("Grid") { applyGrid(grid.visibility != View.VISIBLE) }
            hdBtn = chip("HD") { toggleHd() }
            addView(gridBtn); addView(hdBtn)
        })

        // 4:3 frame, aligned to the top like AVIP GPSCam, with the live stamp on top
        val frame = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_START }
        frame.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridOverlayView(this)
        frame.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        stampPreview = StampPreviewView(this)
        frame.addView(stampPreview, FrameLayout.LayoutParams(MATCH, MATCH))
        focusRing = View(this).apply {
            background = rounded(Color.TRANSPARENT, dpf(30f), Color.WHITE, dp(2))
            visibility = View.GONE
        }
        frame.addView(focusRing, FrameLayout.LayoutParams(dp(60), dp(60)))
        if (intent.getBooleanExtra(EXTRA_ODOMETER, false)) frame.addView(OdometerGuideView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        zoomLabel = text("1.0x", 22f, Color.WHITE, bold = true) { setShadowLayer(6f, 0f, 0f, Color.BLACK); visibility = View.GONE }
        frame.addView(zoomLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(frame, lp(MATCH, 0, 1f))

        root.addView(vertical {
            setPadding(dp(14), dp(8), dp(14), dp(14))
            addView(horizontal {
                gravity = Gravity.CENTER
                listOf(0.5f, 1f, 2f, 5f).forEach { r ->
                    val c = text(if (r < 1) ".5x" else "${r.toInt()}x", 13f, Color.WHITE, bold = true) {
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setOnClickListener { setZoom(r) }
                    }
                    zoomChips += c to r
                    addView(c, lp(WRAP, WRAP).margins(context, l = 4, r = 4))
                }
            })
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(0x33FFFFFF, dpf(10f), Color.WHITE, dp(2))
                    clipToOutline = true
                }
                addView(thumb, lp(dp(54), dp(54)))
                addView(View(context), lp(0, 1, 1f))
                shutter = FrameLayout(context).apply {
                    background = circle(Color.WHITE)
                    addView(View(context).apply { background = rounded(Color.WHITE, dpf(30f), Color.BLACK, dp(3)) },
                        FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
                    contentDescription = "Take photo"
                    setOnClickListener { takePhoto() }
                }
                addView(shutter, lp(dp(76), dp(76)))
                addView(View(context), lp(0, 1, 1f))
                val right = vertical {
                    gravity = Gravity.CENTER
                    gpsLabel = text("GPS…", 11.5f, 0xFFB8C4CF.toInt()) { gravity = Gravity.CENTER }
                    addView(gpsLabel)
                    doneBtn = text("Done", 14f, Ui.TEXT, bold = true) {
                        gravity = Gravity.CENTER
                        background = rounded(Ui.SUN, dpf(20f))
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        visibility = if (multi) View.VISIBLE else View.GONE
                        setOnClickListener { finishWithResult() }
                    }
                    addView(doneBtn, lp(WRAP, WRAP).margins(context, t = 6))
                }
                addView(right, lp(dp(70), WRAP))
            }, lp().margins(context, t = 6))
            addView(text(tr("Photo सिर्फ इसी camera से · Gallery से upload बंद", "Photos from this camera only"), 11.5f, 0xFFB8C4CF.toInt()) { gravity = Gravity.CENTER },
                lp().margins(context, t = 8))
        })
        setContentView(root)
        setupGestures()
        paintChips()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = text(label, 12.5f, Color.WHITE, bold = true) {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) }
    }

    private fun paintChips() {
        fun paint(tv: TextView, on: Boolean) {
            tv.background = if (on) rounded(Ui.SUN, dpf(14f)) else rounded(0x33FFFFFF, dpf(14f))
            tv.setTextColor(if (on) Ui.TEXT else Color.WHITE)
        }
        paint(gridBtn, grid.visibility == View.VISIBLE)
        hdBtn.text = if (hd) "HD" else "SD"
        paint(hdBtn, hd)
        doneBtn.text = if (saved.isEmpty()) "Done" else "Done (${saved.size})"
    }

    private fun applyGrid(on: Boolean) {
        grid.visibility = if (on) View.VISIBLE else View.GONE
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("grid", on).apply()
        if (::tilt.isInitialized) { if (on) tilt.start() else tilt.stop() }
        paintChips()
    }

    private fun toggleHd() {
        if (busy) return
        hd = !hd
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hd", hd).apply()
        toast(if (hd) tr("HD: पूरी quality", "HD: full quality") else tr("SD: छोटी photo, जल्दी upload", "SD: smaller photo, faster upload"))
        paintChips()
        bind()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() { super.onStart(); if (hasLocation()) startLocation() }

    override fun onResume() {
        super.onResume()
        heading.start()
        if (grid.visibility == View.VISIBLE) tilt.start()
        stampPreview.removeCallbacks(stampTicker)
        stampPreview.post(stampTicker)
    }

    override fun onPause() {
        super.onPause()
        stampPreview.removeCallbacks(stampTicker)
        heading.stop(); tilt.stop()
    }

    override fun onStop() { super.onStop(); try { lm.removeUpdates(locationListener) } catch (_: Exception) {} }

    override fun onDestroy() { super.onDestroy(); if (::executor.isInitialized) executor.shutdown() }

    private fun finishWithResult() {
        if (busy) { toast(tr("Photo save हो रही है…", "Saving photo…")); return }
        val data = Intent().putStringArrayListExtra(EXTRA_FILES, saved)
        current?.let { data.putExtra(EXTRA_LAT, it.latitude).putExtra(EXTRA_LNG, it.longitude) }
        setResult(if (saved.isEmpty()) Activity.RESULT_CANCELED else Activity.RESULT_OK, data)
        finish()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ provider = future.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val keepZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        // Photos are 4:3, so the preview shows exactly what is saved (as in AVIP GPSCam).
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio43).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setResolutionSelector(ratio43)
        if (!hd) builder.setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
        )
        val cap = builder.build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
            capture = cap
            lastZoom = null
            camera?.cameraInfo?.zoomState?.observe(this) { onZoom(it) }
            keepZoom?.let { setZoom(it) }
        } catch (e: Exception) {
            toast(tr("Camera शुरू नहीं हुआ: ${e.message ?: ""}", "Camera did not start: ${e.message ?: ""}"))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                setZoom(z * d.scaleFactor); return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        stampPreview.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress && e.pointerCount == 1) tap.onTouchEvent(e)
            true
        }
    }

    private fun setZoom(r: Float) {
        val cam = camera ?: return
        val s = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(r.coerceIn(s.minZoomRatio, s.maxZoomRatio))
    }

    private fun onZoom(s: ZoomState) {
        zoomChips.forEach { (c, r) ->
            c.visibility = if (r >= s.minZoomRatio - 0.01f && r <= s.maxZoomRatio + 0.01f) View.VISIBLE else View.GONE
            val on = abs(s.zoomRatio - r) < 0.05f
            c.background = if (on) rounded(Ui.SUN, dpf(14f)) else rounded(0x33FFFFFF, dpf(14f))
            c.setTextColor(if (on) Ui.TEXT else Color.WHITE)
        }
        val prev = lastZoom
        lastZoom = s.zoomRatio
        if (prev != null && abs(prev - s.zoomRatio) > 0.01f) {
            zoomLabel.text = String.format(Locale.US, "%.1fx", s.zoomRatio)
            zoomLabel.visibility = View.VISIBLE
            zoomLabel.removeCallbacks(hideZoom)
            zoomLabel.postDelayed(hideZoom, 1200)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        focusRing.translationX = x - dp(30)
        focusRing.translationY = y - dp(30)
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().cancel()
        focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
    }

    // ---------------------------------------------------------------- photo

    private fun takePhoto() {
        val cap = capture ?: return
        if (busy) return
        setBusy(true)
        val snap = Snapshot(current, place, Date(), heading.heading)
        if (snap.loc == null) toast(tr("GPS अभी नहीं मिला — photo पर location नहीं आएगी", "No GPS yet: the photo will have no location"))
        cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = try { image.toBitmap() } finally { image.close() }
                runOnUiThread { process(bmp, rotation, snap) }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { setBusy(false); toast(tr("Photo नहीं खिंची: ${exception.message ?: ""}", "Photo not taken: ${exception.message ?: ""}")) }
            }
        })
    }

    private fun process(raw: Bitmap, rotation: Int, snap: Snapshot) {
        // HD is capped at 2560 px so photos upload quickly from site; the stamp scales with the photo.
        val maxDim = if (hd) 2560 else 1280
        lifecycleScope.launch {
            try {
                val small = withContext(Dispatchers.IO) {
                    val photo = prepare(raw, rotation, maxDim)
                    val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(snap.time) + ".jpg"
                    val f = File(dir, name)
                    // odometer: keep the guide-box area, before the stamp, for reading the number
                    if (intent.getBooleanExtra(EXTRA_ODOMETER, false)) {
                        try {
                            val r = OdometerGuideView.cropRect(photo.width, photo.height)
                            val crop = Bitmap.createBitmap(photo, r.left, r.top, r.width(), r.height())
                            File(f.absolutePath + OdometerReader.CROP_SUFFIX).outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                            if (crop !== photo) crop.recycle()
                        } catch (_: Exception) {}
                    }
                    val map = snap.loc?.let { MapTiles.render(it.latitude, it.longitude, zoom = 16, sizePx = 512) }
                    Stamp.draw(photo, map, snap.loc, snap.place, snap.time, snap.heading)
                    f.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, if (hd) 88 else 82, it) }
                    writeExif(f, snap)
                    copyToGallery(f, snap)
                    saved += f.absolutePath
                    val t = Bitmap.createScaledBitmap(photo, 160, (160f * photo.height / photo.width).toInt(), true)
                    photo.recycle()
                    t
                }
                thumb.setImageBitmap(small)
                paintChips()
                if (!multi) { setBusy(false); finishWithResult(); return@launch }
                toast(tr("Photo save हो गई", "Photo saved"))
            } catch (e: Exception) {
                toast(tr("Save नहीं हुई: ${e.message ?: ""}", "Not saved: ${e.message ?: ""}"))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun prepare(src: Bitmap, rotation: Int, maxDim: Int): Bitmap {
        val scale = min(1f, maxDim.toFloat() / max(src.width, src.height))
        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        if (out.isMutable) return out
        val copy = out.copy(Bitmap.Config.ARGB_8888, true)
        out.recycle()
        return copy
    }

    private fun writeExif(f: File, snap: Snapshot) {
        try {
            val exif = ExifInterface(f.absolutePath)
            snap.loc?.let { exif.setGpsInfo(it) }
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(snap.time))
            exif.saveAttributes()
        } catch (_: Exception) {
        }
    }

    /** A copy in the phone gallery (Pictures/AVIP Solar Expense), like AVIP GPSCam. */
    private fun copyToGallery(f: File, snap: Snapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, snap.time.time)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + GALLERY_FOLDER)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
        }
    }

    private fun setBusy(v: Boolean) {
        busy = v
        shutter.isEnabled = !v
        shutter.alpha = if (v) 0.5f else 1f
    }

    // ---------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { gpsLabel.text = tr("GPS बंद है", "GPS is off"); return }
        for (p in providers) {
            try {
                lm.getLastKnownLocation(p)?.let { onLocation(it) }
                lm.requestLocationUpdates(p, 2000L, 1f, locationListener, mainLooper)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun onLocation(loc: Location) {
        val cur = current
        if (cur != null && loc.time - cur.time <= 10_000 && loc.accuracy > cur.accuracy) return
        current = loc
        heading.declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
        gpsLabel.text = "GPS ±${loc.accuracy.roundToInt()} m"
        gpsLabel.setTextColor(if (loc.accuracy <= 20f) 0xFF3DDC84.toInt() else 0xFFFFC107.toInt())
        refreshPreviewMap(loc)
        val g = geocoded
        if (g == null || g.distanceTo(loc) > 30f) {
            geocoded = loc
            lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { Geocoding.lookup(this@CameraActivity, loc) }
                if (p != null) place = p else geocoded = null
            }
        }
    }

    /** Map thumbnail for the live stamp; refreshed after moving about 30 m. */
    private fun refreshPreviewMap(loc: Location) {
        val last = previewMapAt
        if (previewMapLoading || (previewMap != null && last != null && last.distanceTo(loc) < 30f)) return
        previewMapLoading = true
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { MapTiles.render(loc.latitude, loc.longitude, zoom = 16, sizePx = 512) }
            previewMapLoading = false
            if (bmp != null) {
                val old = previewMap
                previewMap = bmp
                previewMapAt = loc
                old?.recycle()
            }
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
}

package lt.skeveldros

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity(), GLSurfaceView.Renderer {

    companion object {
        const val S = 540          // straightened image size (75 x 75 mm)
        const val N = 360          // 50 mm in pixels
        const val M = (S - N) / 2  // margin
        const val HALF_IN = 0.025f
        const val HALF_OUT = 0.0375f
        val THICK = listOf(3, 4, 5, 6, 8, 10, 12, 15, 19)
        fun minFor(t: Int) = when (t) { 3 -> 15; in 4..12 -> 40; else -> 30 }
    }

    private var session: Session? = null
    private var installRequested = false
    private val bg = BackgroundRenderer()
    private var viewW = 1
    private var viewH = 1
    @Volatile private var geomDirty = true
    @Volatile private var captureRequested = false
    @Volatile private var inResult = false

    private lateinit var gl: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var arLayer: FrameLayout
    private lateinit var resultLayer: ScrollView
    private lateinit var rv: ResultView
    private lateinit var numText: TextView
    private lateinit var verdict: TextView
    private lateinit var details: TextView
    private lateinit var sens: SeekBar
    private lateinit var fmin: SeekBar
    private lateinit var fminLabel: TextView
    private lateinit var pol: Spinner
    private lateinit var thick: Spinner
    private lateinit var order: EditText
    private lateinit var lenOk: CheckBox
    private lateinit var histText: TextView
    private lateinit var btnOne: Button
    private lateinit var btnHalf: Button
    private lateinit var btnEdges: Button
    private var gray: FloatArray? = null
    private var coverage = 0f

    private val dpf get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dpf).toInt()

    // ---------------------------------------------------------------- UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)

        arLayer = FrameLayout(this)
        gl = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = OverlayView(this)
        arLayer.addView(gl, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        arLayer.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val shot = button("Fiksuoti kadrą", primary = true) {
            if (overlay.hasFrame) captureRequested = true
            else toast("Palaukite, kol atsiras geltonas rėmelis")
        }
        shot.textSize = 20f
        arLayer.addView(shot, FrameLayout.LayoutParams(MATCH_PARENT, dp(68), Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(28))
        })

        resultLayer = ScrollView(this).apply { setBackgroundColor(Color.WHITE); visibility = View.GONE }
        resultLayer.addView(buildResult())

        root.addView(arLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(resultLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        renderHistory()
    }

    private fun buildResult(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(40))
        }
        rv = ResultView(this)
        rv.onChanged = { updateResult() }
        col.addView(rv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val res = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        numText = TextView(this).apply { textSize = 52f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK) }
        verdict = TextView(this).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        res.addView(numText)
        res.addView(verdict, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(18) })
        col.addView(res)
        details = TextView(this).apply { textSize = 14f; setTextColor(0xFF5B6270.toInt()) }
        col.addView(details)

        btnOne = button("+1") { setTap(1f) }
        btnHalf = button("+½") { setTap(.5f) }
        btnEdges = button("Linijos") { rv.showEdges = !rv.showEdges; styleToggle(btnEdges, rv.showEdges); rv.invalidate() }
        col.addView(row(btnOne, btnHalf, button("Atšaukti") { rv.undo() }, btnEdges))
        col.addView(hint("Bakstelėkite skeveldrą, kad pridėtumėte; bakstelėkite tašką, kad ištrintumėte. Žalias = 1, geltonas = ½."))

        sens = SeekBar(this).apply { max = 31; progress = 10 }   // 4..35 %
        col.addView(labeled("Jautrumas", sens, null))
        fmin = SeekBar(this).apply { max = 20; progress = 4 }    // 2..12 mm
        fminLabel = TextView(this).apply { textSize = 15f; setTextColor(Color.BLACK) }
        col.addView(labeled("Min. skeveldra", fmin, fminLabel))
        pol = spinner(listOf("šviesūs", "tamsūs", "abu"), 0)
        col.addView(row(label("Įtrūkimai"), pol, button("Perskaičiuoti") { recount() }))
        val seekL = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { fminLabel.text = fmt(minMm()) + " mm" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { recount() }
        }
        sens.setOnSeekBarChangeListener(seekL); fmin.setOnSeekBarChangeListener(seekL)
        fminLabel.text = fmt(minMm()) + " mm"
        pol.onItemSelectedListener = onSelect { if (gray != null) recount() }

        thick = spinner(THICK.map { "$it mm" }, 1)
        thick.onItemSelectedListener = onSelect { updateResult() }
        order = EditText(this).apply { hint = "Užsakymo nr."; inputType = InputType.TYPE_CLASS_TEXT; textSize = 16f }
        col.addView(row(label("Storis"), thick, order))
        lenOk = CheckBox(this).apply { text = "Ilgiausia skeveldra ≤ 100 mm"; textSize = 16f; setOnCheckedChangeListener { _, _ -> updateResult() } }
        col.addView(lenOk)

        col.addView(row(button("Išsaugoti", primary = true) { save() }, button("Nauja nuotrauka") { backToCamera() }))
        col.addView(TextView(this).apply {
            text = "Istorija"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); setPadding(0, dp(22), 0, dp(6))
        })
        histText = TextView(this).apply { textSize = 14f; setTextColor(Color.BLACK); setTextIsSelectable(true) }
        col.addView(histText)
        col.addView(row(button("Dalintis CSV") { shareCsv() }, button("Ištrinti istoriją") { clearHistory() }))
        setTap(1f); styleToggle(btnEdges, true)
        return col
    }

    private fun minMm() = 2f + fmin.progress * .5f
    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.US, "%.1f", v).replace('.', ',')

    private fun setTap(w: Float) {
        rv.tapWeight = w
        styleToggle(btnOne, w == 1f); styleToggle(btnHalf, w != 1f)
    }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = 12 * dpf
            setColor(if (primary) YELLOW else 0xFFF1F3F5.toInt())
            setStroke(dp(1), if (primary) YELLOW else 0xFFDDE1E6.toInt())
        }
        setTextColor(Color.BLACK)
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun styleToggle(b: Button, on: Boolean) {
        (b.background as? GradientDrawable)?.setColor(if (on) Color.BLACK else 0xFFF1F3F5.toInt())
        b.setTextColor(if (on) Color.WHITE else Color.BLACK)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }) }
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(Color.BLACK) }
    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF5B6270.toInt()); setPadding(0, dp(2), 0, dp(8)) }

    private fun labeled(t: String, s: SeekBar, value: TextView?) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(label(t), LinearLayout.LayoutParams(dp(130), WRAP_CONTENT))
        addView(s, LinearLayout.LayoutParams(0, dp(44), 1f))
        if (value != null) addView(value, LinearLayout.LayoutParams(dp(60), WRAP_CONTENT))
    }

    private fun spinner(items: List<String>, sel: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
    }

    private fun onSelect(f: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; f() }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    // ---------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        if (inResult) return
        startAr()
    }

    private fun startAr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(this)
                try {
                    val configs = s.getSupportedCameraConfigs(CameraConfigFilter(s))
                    configs.maxByOrNull { it.imageSize.width * it.imageSize.height }?.let { s.cameraConfig = it }
                } catch (_: Exception) {}
                val cfg = Config(s)
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                cfg.focusMode = Config.FocusMode.AUTO
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) cfg.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(cfg)
                session = s
                geomDirty = true
            } catch (e: Exception) {
                overlay.set(null, "ARCore klaida: ${e.javaClass.simpleName}")
                return
            }
        }
        try { session?.resume() } catch (e: Exception) {
            overlay.set(null, "Kamera nepasiekiama"); return
        }
        gl.onResume()
    }

    override fun onPause() {
        super.onPause()
        gl.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close(); session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED) {
            overlay.set(null, "Reikia leidimo naudoti kamerą")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inResult) backToCamera() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- GL / AR
    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.create()
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        viewW = w; viewH = h; geomDirty = true
    }

    override fun onDrawFrame(g: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geomDirty) { s.setDisplayGeometry(Surface.ROTATION_0, viewW, viewH); geomDirty = false }
        s.setCameraTextureName(bg.textureId)
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) {
            overlay.set(null, "Lėtai pajudinkite telefoną"); return
        }
        val hit = frame.hitTest(viewW / 2f, viewH / 2f).firstOrNull {
            val t = it.trackable
            (t is Plane && t.isPoseInPolygon(it.hitPose)) || t is DepthPoint ||
                    (t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
        }
        if (hit == null) { overlay.set(null, "Nukreipkite į stiklą, ieškomas paviršius"); return }

        val pose = hit.hitPose
        val c = pose.translation
        val n = norm(pose.yAxis)
        val camX = cam.pose.xAxis
        val u = norm(sub(camX, mul(n, dot(camX, n))))
        val v = norm(cross(n, u))

        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        cam.getViewMatrix(view, 0)
        cam.getProjectionMatrix(proj, 0, 0.01f, 100f)
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val inner = square(c, u, v, HALF_IN).map { project(vp, it) }
        val outer = square(c, u, v, HALF_OUT).map { project(vp, it) }
        if (inner.any { it == null } || outer.any { it == null }) { overlay.set(null, "Atitraukite telefoną"); return }
        val idx = order(inner.map { it!! })
        val innerS = FloatArray(8); val outerS = FloatArray(8)
        for (k in 0..3) {
            innerS[k * 2] = inner[idx[k]]!![0]; innerS[k * 2 + 1] = inner[idx[k]]!![1]
            outerS[k * 2] = outer[idx[k]]!![0]; outerS[k * 2 + 1] = outer[idx[k]]!![1]
        }
        val tilt = abs(dot(norm(cam.pose.zAxis), n))
        val cm = String.format(Locale.US, "%.0f", hit.distance * 100)
        overlay.set(innerS, if (tilt < 0.9f) "Laikykite telefoną tiesiau · $cm cm" else "Rėmelis 50×50 mm · $cm cm")

        if (captureRequested) capture(frame, outerS)
    }

    private fun capture(frame: Frame, outerView: FloatArray) {
        val img = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { return } catch (e: Exception) {
            captureRequested = false; toast("Nepavyko gauti kadro"); return
        }
        captureRequested = false
        val src: Bitmap
        try { src = yToBitmap(img) } finally { img.close() }
        val imgPts = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.VIEW, outerView, Coordinates2d.IMAGE_PIXELS, imgPts)
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(imgPts, 0, floatArrayOf(0f, 0f, S.toFloat(), 0f, S.toFloat(), S.toFloat(), 0f, S.toFloat()), 0, 4)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        runOnUiThread { showResult(out) }
    }

    private fun yToBitmap(img: Image): Bitmap {
        val w = img.width; val h = img.height
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * rs
            for (x in 0 until w) {
                val g = buf.get(row + x * ps).toInt() and 255
                px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // vector helpers
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun mul(a: FloatArray, k: Float) = floatArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: FloatArray): FloatArray { val l = sqrt(dot(a, a)).coerceAtLeast(1e-6f); return mul(a, 1f / l) }

    private fun square(c: FloatArray, u: FloatArray, v: FloatArray, h: Float) = listOf(
        floatArrayOf(c[0] - u[0] * h - v[0] * h, c[1] - u[1] * h - v[1] * h, c[2] - u[2] * h - v[2] * h),
        floatArrayOf(c[0] + u[0] * h - v[0] * h, c[1] + u[1] * h - v[1] * h, c[2] + u[2] * h - v[2] * h),
        floatArrayOf(c[0] + u[0] * h + v[0] * h, c[1] + u[1] * h + v[1] * h, c[2] + u[2] * h + v[2] * h),
        floatArrayOf(c[0] - u[0] * h + v[0] * h, c[1] - u[1] * h + v[1] * h, c[2] - u[2] * h + v[2] * h)
    )

    private fun project(vp: FloatArray, p: FloatArray): FloatArray? {
        val o = FloatArray(4)
        android.opengl.Matrix.multiplyMV(o, 0, vp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
        if (o[3] <= 0f) return null
        return floatArrayOf((o[0] / o[3] + 1f) / 2f * viewW, (1f - o[1] / o[3]) / 2f * viewH)
    }

    /** Returns corner indices ordered top-left, top-right, bottom-right, bottom-left on screen. */
    private fun order(p: List<FloatArray>): IntArray {
        var area = 0f
        for (i in 0..3) { val a = p[i]; val b = p[(i + 1) % 4]; area += a[0] * b[1] - b[0] * a[1] }
        val seq = if (area > 0) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 3, 2, 1)
        var tl = 0; var best = Float.MAX_VALUE
        for (k in 0..3) { val q = p[seq[k]]; if (q[0] + q[1] < best) { best = q[0] + q[1]; tl = k } }
        return IntArray(4) { seq[(tl + it) % 4] }
    }

    // ---------------------------------------------------------------- result
    private fun showResult(b: Bitmap) {
        inResult = true
        gl.onPause(); session?.pause()
        rv.bitmap = b; rv.roi0 = M; rv.roiN = N
        rv.marks.clear(); rv.clearUndo()
        gray = Counter.gray(b)
        arLayer.visibility = View.GONE
        resultLayer.visibility = View.VISIBLE
        resultLayer.scrollTo(0, 0)
        recount()
    }

    private fun backToCamera() {
        inResult = false
        resultLayer.visibility = View.GONE
        arLayer.visibility = View.VISIBLE
        startAr()
    }

    private fun recount() {
        val g = gray ?: return
        val sensP = 4 + sens.progress; val mm = minMm(); val p = pol.selectedItemPosition
        details.text = "Skaičiuojama…"
        Thread {
            val r = Counter.count(g, S, M, N, sensP, mm, p)
            val px = IntArray(S * S) { if (r.edges[it]) 0xFFFF2840.toInt() else 0 }
            val eb = Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                coverage = r.coverage
                rv.edges = eb
                rv.setMarks(r.marks)
            }
        }.start()
    }

    private fun total() = rv.marks.sumOf { it.w.toDouble() }.toFloat()

    private fun updateResult() {
        val n = total()
        val t = THICK[thick.selectedItemPosition]
        val min = minFor(t)
        val full = rv.marks.count { it.w == 1f }
        numText.text = fmt(n)
        details.text = "Min. $min ($t mm) · $full pilnos, ${rv.marks.size - full} kraštinės · linijų ${(coverage * 100).toInt()}%"
        val (txt, col) = when {
            n < min -> "Neatitinka" to 0xFFC1121F.toInt()
            !lenOk.isChecked -> "Patikrinkite ilgį" to 0xFFDDE1E6.toInt()
            else -> "Atitinka" to 0xFF00A86B.toInt()
        }
        verdict.text = txt
        verdict.setTextColor(if (col == 0xFFDDE1E6.toInt()) Color.BLACK else Color.WHITE)
        verdict.background = GradientDrawable().apply { cornerRadius = 8 * dpf; setColor(col) }
    }

    // ---------------------------------------------------------------- history
    private val prefs get() = getSharedPreferences("skeveldros", MODE_PRIVATE)
    private fun history(): List<String> = prefs.getString("hist", "")!!.split("\n").filter { it.isNotBlank() }

    private fun save() {
        if (rv.bitmap == null) return
        val t = THICK[thick.selectedItemPosition]
        val n = total()
        val ok = n >= minFor(t) && lenOk.isChecked
        val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val ord = order.text.toString().replace(";", ",").replace("\n", " ").trim()
        val line = listOf(d, ord, "$t", fmt(n), if (ok) "Atitinka" else "Neatitinka").joinToString(";")
        val h = (listOf(line) + history()).take(1000)
        prefs.edit().putString("hist", h.joinToString("\n")).apply()
        renderHistory(); toast("Išsaugota")
    }

    private fun renderHistory() {
        val h = history()
        histText.text = if (h.isEmpty()) "Dar nėra išsaugotų bandymų." else h.take(50).joinToString("\n") {
            val p = it.split(";")
            "${p.getOrElse(0) { "" }}  ${p.getOrElse(1) { "" }.ifBlank { "–" }}  ${p.getOrElse(2) { "" }} mm  ${p.getOrElse(3) { "" }}  ${p.getOrElse(4) { "" }}"
        }
    }

    private fun shareCsv() {
        val csv = "Data;Užsakymas;Storis mm;Skeveldros;Rezultatas\n" + history().joinToString("\n")
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, csv); putExtra(Intent.EXTRA_SUBJECT, "Fragmentacijos bandymai") }
        startActivity(Intent.createChooser(i, "Dalintis CSV"))
    }

    private var delArmed = 0L
    private fun clearHistory() {
        val now = System.currentTimeMillis()
        if (now - delArmed > 3000) { delArmed = now; toast("Paspauskite dar kartą, kad ištrintumėte"); return }
        prefs.edit().remove("hist").apply(); renderHistory(); delArmed = 0
    }
}

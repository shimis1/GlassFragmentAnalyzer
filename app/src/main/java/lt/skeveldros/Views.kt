package lt.skeveldros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

const val YELLOW = 0xFFFFC400.toInt()
const val GREEN = 0xFF00C27A.toInt()

/** Draws the live 50x50 mm frame over the AR camera view. */
class OverlayView(ctx: Context) : View(ctx) {
    @Volatile private var corners: FloatArray? = null
    @Volatile private var message: String = "Paleidžiama kamera…"
    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = YELLOW; style = Paint.Style.STROKE; strokeWidth = 4 * dp; strokeJoin = Paint.Join.ROUND
    }
    private val shade = Paint().apply { color = 0x66000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 17 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * dp }

    fun set(c: FloatArray?, msg: String) {
        corners = c; message = msg
        postInvalidate()
    }

    val hasFrame get() = corners != null

    override fun onDraw(canvas: Canvas) {
        val c = corners
        val w = width.toFloat(); val h = height.toFloat()
        if (c != null) {
            val p = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w, h, Path.Direction.CW)
                moveTo(c[0], c[1]); lineTo(c[2], c[3]); lineTo(c[4], c[5]); lineTo(c[6], c[7]); close()
            }
            canvas.drawPath(p, shade)
            val q = Path().apply { moveTo(c[0], c[1]); lineTo(c[2], c[3]); lineTo(c[4], c[5]); lineTo(c[6], c[7]); close() }
            canvas.drawPath(q, line)
        } else {
            val s = 12 * dp
            canvas.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, cross)
            canvas.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, cross)
        }
        val m = message
        val tw = text.measureText(m)
        val y = 56 * dp
        canvas.drawRoundRect(RectF(w / 2 - tw / 2 - 14 * dp, y - 26 * dp, w / 2 + tw / 2 + 14 * dp, y + 12 * dp), 12 * dp, 12 * dp, pill)
        canvas.drawText(m, w / 2, y, text)
    }
}

/** Shows the captured, straightened sample with fragment markers; tap to add or remove. */
class ResultView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
    var edges: Bitmap? = null
    var showEdges = true
    var roi0 = 0
    var roiN = 0
    val marks = ArrayList<Mark>()
    private val undo = ArrayList<List<Mark>>()
    var tapWeight = 1f
    var onChanged: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW; style = Paint.Style.STROKE; strokeWidth = 3 * dp }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 130 }
    private var downX = 0f
    private var downY = 0f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, w)
    }

    fun setMarks(list: List<Mark>, keepUndo: Boolean = true) {
        if (keepUndo) pushUndo()
        marks.clear(); marks.addAll(list)
        invalidate(); onChanged?.invoke()
    }

    fun pushUndo() {
        undo.add(ArrayList(marks))
        if (undo.size > 50) undo.removeAt(0)
    }

    fun undo() {
        if (undo.isEmpty()) return
        marks.clear(); marks.addAll(undo.removeAt(undo.size - 1))
        invalidate(); onChanged?.invoke()
    }

    fun clearUndo() = undo.clear()

    private fun scale(): Float = bitmap?.let { width.toFloat() / it.width } ?: 1f

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val dst = Rect(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, bmpPaint)
        val e = edges
        if (showEdges && e != null) canvas.drawBitmap(e, null, dst, edgePaint)
        val s = scale()
        canvas.drawRect(roi0 * s, roi0 * s, (roi0 + roiN) * s, (roi0 + roiN) * s, frame)
        for (m in marks) {
            fill.color = if (m.w == 1f) GREEN else YELLOW
            val r = (if (m.w == 1f) 8 else 7) * dp
            canvas.drawCircle(m.x * s, m.y * s, r, fill)
            canvas.drawCircle(m.x * s, m.y * s, r, stroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; return true }
            MotionEvent.ACTION_UP -> {
                if (hypot(ev.x - downX, ev.y - downY) > 10 * dp) return true
                val s = scale()
                val x = ev.x / s; val y = ev.y / s
                val rad = 18 * dp / s
                var best = -1; var bd = rad
                marks.forEachIndexed { i, m ->
                    val d = hypot(m.x - x, m.y - y)
                    if (d < bd) { bd = d; best = i }
                }
                pushUndo()
                if (best >= 0) marks.removeAt(best) else marks.add(Mark(x, y, tapWeight))
                invalidate(); onChanged?.invoke()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

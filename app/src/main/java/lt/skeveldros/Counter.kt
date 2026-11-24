package lt.skeveldros

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Mark(val x: Float, val y: Float, val w: Float)

class CountResult(val marks: List<Mark>, val edges: BooleanArray, val coverage: Float)

/**
 * Finds fragment centres as local maxima of the distance to the nearest crack line.
 * Works on a square image of size [size]; the 50x50 mm counting square is
 * [roi0, roi0 + n) on both axes (n pixels = 50 mm).
 */
object Counter {
    const val POL_BRIGHT = 0
    const val POL_DARK = 1
    const val POL_BOTH = 2

    fun gray(b: Bitmap): FloatArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return FloatArray(w * h) {
            val c = px[it]
            ((c shr 16) and 255) * .299f + ((c shr 8) and 255) * .587f + (c and 255) * .114f
        }
    }

    private fun boxBlur(a: FloatArray, W: Int, H: Int, R: Int): FloatArray {
        val w1 = W + 1
        val I = DoubleArray(w1 * (H + 1))
        for (y in 0 until H) {
            var s = 0.0
            for (x in 0 until W) {
                s += a[y * W + x]
                I[(y + 1) * w1 + x + 1] = I[y * w1 + x + 1] + s
            }
        }
        val o = FloatArray(W * H)
        for (y in 0 until H) {
            val y0 = max(0, y - R); val y1 = min(H, y + R + 1)
            for (x in 0 until W) {
                val x0 = max(0, x - R); val x1 = min(W, x + R + 1)
                o[y * W + x] = ((I[y1 * w1 + x1] - I[y0 * w1 + x1] - I[y1 * w1 + x0] + I[y0 * w1 + x0]) /
                        ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return o
    }

    fun count(gr: FloatArray, size: Int, roi0: Int, n: Int, sensPct: Int, minMm: Float, pol: Int, maskOutside: Boolean = false): CountResult {
        val W = size; val H = size; val total = W * H
        val pm = n / 50f
        val sm = boxBlur(gr, W, H, 1)
        val bg = boxBlur(gr, W, H, max(3, (pm * 1.5f).roundToInt()))
        val hist = IntArray(256)
        val df = IntArray(total)
        for (i in 0 until total) {
            var v = when (pol) {
                POL_BRIGHT -> sm[i] - bg[i]
                POL_DARK -> bg[i] - sm[i]
                else -> abs(sm[i] - bg[i])
            }
            v = min(255f, max(0f, v) * 4f)
            val iv = v.toInt()
            df[i] = iv; hist[iv]++
        }
        val p = sensPct / 100f
        var acc = 0; var t = 255
        while (t > 1) { acc += hist[t]; if (acc >= p * total) break; t-- }
        val E = BooleanArray(total) { df[it] >= t }
        val E0 = E.copyOf()

        // remove small specks (reflections, dust)
        val st = IntArray(total); val seen = BooleanArray(total); val buf = IntArray(total)
        val minSp = max(5f, pm * pm * .5f)
        for (s0 in 0 until total) {
            if (!E[s0] || seen[s0]) continue
            var sp = 0; var k = 0
            st[sp++] = s0; seen[s0] = true
            while (sp > 0) {
                val i = st[--sp]; buf[k++] = i
                val x = i % W; val y = i / W
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue
                    val j = yy * W + xx
                    if (E[j] && !seen[j]) { seen[j] = true; st[sp++] = j }
                }
            }
            if (k < minSp) for (q in 0 until k) E[buf[q]] = false
        }
        var cov = E.count { it }
        if (cov < total * p * .35f) { E0.copyInto(E); cov = E.count { it } }
        if (maskOutside) for (i in 0 until total) {
            val x = i % W; val y = i / W
            if (x < roi0 || y < roi0 || x >= roi0 + n || y >= roi0 + n) E[i] = true
        }

        // chamfer distance to nearest crack
        val big = 1e6f
        val dt = FloatArray(total) { if (E[it]) 0f else big }
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x; var v = dt[i]; if (v == 0f) continue
            if (x > 0) v = min(v, dt[i - 1] + 1)
            if (y > 0) {
                v = min(v, dt[i - W] + 1)
                if (x > 0) v = min(v, dt[i - W - 1] + 1.414f)
                if (x < W - 1) v = min(v, dt[i - W + 1] + 1.414f)
            }
            dt[i] = v
        }
        for (y in H - 1 downTo 0) for (x in W - 1 downTo 0) {
            val i = y * W + x; var v = dt[i]; if (v == 0f) continue
            if (x < W - 1) v = min(v, dt[i + 1] + 1)
            if (y < H - 1) {
                v = min(v, dt[i + W] + 1)
                if (x < W - 1) v = min(v, dt[i + W + 1] + 1.414f)
                if (x > 0) v = min(v, dt[i + W - 1] + 1.414f)
            }
            dt[i] = v
        }
        val cap = pm * minMm * 1.5f
        for (i in 0 until total) if (dt[i] > cap) dt[i] = cap
        val ds = boxBlur(dt, W, H, 1)

        // local maxima
        val R = max(2, (minMm * pm * .5f).roundToInt())
        val dmin = minMm * pm * .25f
        val mx = FloatArray(total); val my = FloatArray(total)
        for (y in 0 until H) for (x in 0 until W) {
            var v = 0f
            for (k in max(0, x - R)..min(W - 1, x + R)) v = max(v, ds[y * W + k])
            mx[y * W + x] = v
        }
        for (y in 0 until H) for (x in 0 until W) {
            var v = 0f
            for (k in max(0, y - R)..min(H - 1, y + R)) v = max(v, mx[k * W + x])
            my[y * W + x] = v
        }
        val r0 = roi0.toFloat(); val r1 = (roi0 + n).toFloat()
        val cand = ArrayList<FloatArray>()
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x; val v = ds[i]
            if (v >= dmin && v >= my[i] && !E[i]) cand.add(floatArrayOf(v, x.toFloat(), y.toFloat()))
        }
        cand.sortByDescending { it[0] }
        val kept = ArrayList<FloatArray>()
        val out = ArrayList<Mark>()
        for (c in cand) {
            val v = c[0]; val x = c[1]; val y = c[2]
            var ok = true
            for (a in kept) {
                val d = hypot(a[0] - x, a[1] - y)
                if (d < R * 2 || d < min(a[2], v) * .9f) { ok = false; break }
            }
            if (!ok) continue
            kept.add(floatArrayOf(x, y, v))
            val inside = x >= r0 && x < r1 && y >= r0 && y < r1
            var w = 0f
            if (inside) {
                val db = minOf(x - r0, r1 - x, y - r0, r1 - y)
                w = if (db < v * (if (maskOutside) 1.15f else .8f)) .5f else 1f
            } else if (!maskOutside) {
                val ox = maxOf(r0 - x, 0f, x - r1); val oy = maxOf(r0 - y, 0f, y - r1)
                if (hypot(ox, oy) < v * .6f) w = .5f
            }
            if (w > 0f) out.add(Mark(x, y, w))
        }
        return CountResult(out, E, cov.toFloat() / total)
    }
}

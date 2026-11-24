package lt.skeveldros

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the printed template: a black square ring, outer 70 mm, inner window 50 mm,
 * lying on white paper. Works on a small grayscale image (values 0..255).
 * Returns the 4 outer corners (x0,y0,...,x3,y3) in clockwise order, or null.
 */
object TemplateFinder {

    fun find(g: IntArray, w: Int, h: Int): FloatArray? {
        val n = w * h
        val hist = IntArray(256)
        for (v in g) hist[v]++
        var acc = 0; var p95 = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= n * 0.05) { p95 = v; break } }
        if (p95 < 60) return null
        val t = (p95 * 0.45f).toInt()
        val dark = BooleanArray(n) { g[it] < t }

        val label = IntArray(n) { -1 }
        val stack = IntArray(n)
        val minSide = min(w, h) * 0.15f
        var best: FloatArray? = null
        var bestArea = 0f
        var lab = 0
        for (s0 in 0 until n) {
            if (!dark[s0] || label[s0] >= 0) continue
            var sp = 0; stack[sp++] = s0; label[s0] = lab
            var cnt = 0
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            // extreme points
            var sMin = Int.MAX_VALUE; var sMinI = s0; var sMax = Int.MIN_VALUE; var sMaxI = s0
            var dMin = Int.MAX_VALUE; var dMinI = s0; var dMax = Int.MIN_VALUE; var dMaxI = s0
            var xMinI = s0; var xMaxI = s0; var yMinI = s0; var yMaxI = s0
            while (sp > 0) {
                val i = stack[--sp]; cnt++
                val x = i % w; val y = i / w
                if (x < minX) { minX = x; xMinI = i }; if (x > maxX) { maxX = x; xMaxI = i }
                if (y < minY) { minY = y; yMinI = i }; if (y > maxY) { maxY = y; yMaxI = i }
                val s = x + y; val d = x - y
                if (s < sMin) { sMin = s; sMinI = i }; if (s > sMax) { sMax = s; sMaxI = i }
                if (d < dMin) { dMin = d; dMinI = i }; if (d > dMax) { dMax = d; dMaxI = i }
                if (x > 0 && dark[i - 1] && label[i - 1] < 0) { label[i - 1] = lab; stack[sp++] = i - 1 }
                if (x < w - 1 && dark[i + 1] && label[i + 1] < 0) { label[i + 1] = lab; stack[sp++] = i + 1 }
                if (y > 0 && dark[i - w] && label[i - w] < 0) { label[i - w] = lab; stack[sp++] = i - w }
                if (y < h - 1 && dark[i + w] && label[i + w] < 0) { label[i + w] = lab; stack[sp++] = i + w }
            }
            lab++
            val bw = (maxX - minX + 1).toFloat(); val bh = (maxY - minY + 1).toFloat()
            if (bw < minSide || bh < minSide) continue
            if (minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1) continue
            if (bw / bh < 0.55f || bw / bh > 1.8f) continue
            val fill = cnt / (bw * bh)
            if (fill < 0.12f || fill > 0.85f) continue

            fun pt(i: Int) = floatArrayOf((i % w).toFloat(), (i / w).toFloat())
            // A: square roughly upright; B: rotated near 45 degrees
            val qa = listOf(pt(sMinI), pt(dMaxI), pt(sMaxI), pt(dMinI))
            val qb = listOf(pt(yMinI), pt(xMaxI), pt(yMaxI), pt(xMinI))
            for (q in listOf(qa, qb)) {
                val area = abs(area(q))
                if (area < minSide * minSide || area <= bestArea) continue
                if (!sidesOk(q)) continue
                if (!validate(q, g, w, h, t)) continue
                bestArea = area
                best = FloatArray(8) { k -> q[k / 2][k % 2] }
            }
        }
        return best
    }

    private fun area(q: List<FloatArray>): Float {
        var a = 0f
        for (i in 0..3) { val p = q[i]; val r = q[(i + 1) % 4]; a += p[0] * r[1] - r[0] * p[1] }
        return a / 2f
    }

    private fun sidesOk(q: List<FloatArray>): Boolean {
        val s = (0..3).map { val p = q[it]; val r = q[(it + 1) % 4]; kotlin.math.hypot(r[0] - p[0], r[1] - p[1]) }
        val mn = s.minOrNull() ?: return false; val mx = s.maxOrNull() ?: return false
        return mn > 0 && mx / mn < 1.8f
    }

    /** Ring check: just outside each edge is bright paper, 5 mm inside is black ink. */
    private fun validate(q: List<FloatArray>, g: IntArray, w: Int, h: Int, t: Int): Boolean {
        val cx = q.sumOf { it[0].toDouble() }.toFloat() / 4; val cy = q.sumOf { it[1].toDouble() }.toFloat() / 4
        var ok = 0; var tot = 0
        for (e in 0..3) {
            val p = q[e]; val r = q[(e + 1) % 4]
            for (k in 1..19) {
                val f = k / 20f
                val mx = p[0] + (r[0] - p[0]) * f; val my = p[1] + (r[1] - p[1]) * f
                val ox = mx + (mx - cx) * 0.05f; val oy = my + (my - cy) * 0.05f
                val ix = mx + (cx - mx) * 0.14f; val iy = my + (cy - my) * 0.14f
                tot++
                val o = sample(g, w, h, ox, oy); val i = sample(g, w, h, ix, iy)
                if (o >= 0 && i >= 0 && o > t * 1.25f && i < t) ok++
            }
        }
        return ok >= tot * 0.7f
    }

    private fun sample(g: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xi = x.toInt(); val yi = y.toInt()
        if (xi < 0 || yi < 0 || xi >= w || yi >= h) return -1
        return g[yi * w + xi]
    }

    fun clampIndex(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
}

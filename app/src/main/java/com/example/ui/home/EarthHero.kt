package com.example.ui.home

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the globe shows: idle, looking for a path, or carrying traffic. */
enum class EarthMode { OFF, CONNECTING, ON }

/**
 * The home screen's centrepiece: a dotted Earth (Natural Earth land, public domain) with
 * sunlight, city lights, an atmosphere and four satellites. While connecting it turns faster
 * and draws trails from Iran to candidate exits; once connected it faces the route and pulses
 * along it. [overlay] sits on top (the power button).
 */
@Composable
fun EarthHero(
    mode: EarthMode,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val land = remember { EarthData.landDots(context) }
    val anim = remember { EarthAnim() }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock = (it - start) / 1e9f }
    }
    val colors = AppTheme.colors
    val dark = colors.isDark
    val accent = when (mode) {
        EarthMode.OFF -> colors.primary
        EarthMode.CONNECTING -> colors.statusWarning
        EarthMode.ON -> colors.statusConnected
    }
    Box(modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            anim.step(clock, mode, accent)
            drawEarth(anim, land, dark)
        }
        overlay()
    }
}

/** Frame-to-frame state: rotation, eased colour and the connect burst. */
private class EarthAnim {
    var t = 0f
    var dt = 0f
    var rot = -60f * DEG
    var rotSpeed = 0.09f
    var color: Color = Color.Unspecified
    var mode = EarthMode.OFF
    var burst = -1f

    fun step(clock: Float, newMode: EarthMode, accent: Color) {
        dt = (clock - t).coerceIn(0f, 0.05f)
        t = clock
        if (newMode == EarthMode.ON && mode != EarthMode.ON) burst = 0f
        mode = newMode
        color = if (color == Color.Unspecified) accent else lerp(color, accent, min(1f, dt * 2.5f))
        if (mode == EarthMode.ON) {
            // Turn to face Iran and Europe, then hold.
            var d = FACE - rot
            d = atan2(sin(d), cos(d))
            rot += d * min(1f, dt * 1.4f)
        } else {
            val target = if (mode == EarthMode.CONNECTING) 0.5f else 0.09f
            rotSpeed += (target - rotSpeed) * min(1f, dt * 2f)
            rot += rotSpeed * dt
        }
        if (burst >= 0f) { burst += dt; if (burst > 1.3f) burst = -1f }
    }
}

private const val DEG = (PI / 180).toFloat()
private const val FACE = -118f * DEG
private const val TILT = 28f * DEG

/** A unit vector for a latitude/longitude in degrees. */
private fun ll(lat: Float, lon: Float): FloatArray =
    floatArrayOf(cos(lat * DEG) * cos(lon * DEG), sin(lat * DEG), -cos(lat * DEG) * sin(lon * DEG))

internal object EarthData {
    private const val LATTICE = 16_000

    /**
     * Land points on a Fibonacci lattice. The asset holds one bit per lattice index (land or sea),
     * made from Natural Earth 1:50m land (public domain).
     */
    fun landDots(context: Context): List<FloatArray> {
        val bits = runCatching { context.assets.open("earth_land.bin").use { it.readBytes() } }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<FloatArray>(5_000)
        for (i in 0 until LATTICE) {
            if (i shr 3 >= bits.size) break
            if ((bits[i shr 3].toInt() shr (i and 7)) and 1 == 0) continue
            val y = 1.0 - 2.0 * (i + 0.5) / LATTICE
            val lat = Math.toDegrees(asin(y)).toFloat()
            val lon = ((i * 137.50776) % 360.0 - 180.0).toFloat()
            out += ll(lat, lon)
        }
        return out
    }

    val HOME = ll(35.7f, 51.4f)
    val EXIT = ll(50.1f, 8.7f)
    val CANDIDATES = listOf(
        ll(50.1f, 8.7f), ll(52.4f, 4.9f), ll(41f, 29f), ll(1.3f, 103.8f),
        ll(48.9f, 2.3f), ll(60.2f, 24.9f), ll(25.2f, 55.3f), ll(40.7f, -74f)
    )
    val CITIES = listOf(
        35.7f to 51.4f, 40.7f to -74f, 51.5f to -0.1f, 48.9f to 2.3f, 52.5f to 13.4f, 50.1f to 8.7f, 55.8f to 37.6f,
        41f to 29f, 30f to 31.2f, 25.2f to 55.3f, 19.1f to 72.9f, 28.6f to 77.2f, 31.2f to 121.5f, 39.9f to 116.4f,
        35.7f to 139.7f, 37.6f to 127f, 1.3f to 103.8f, 13.8f to 100.5f, -6.2f to 106.8f, -33.9f to 151.2f,
        -23.5f to -46.6f, 19.4f to -99.1f, 34f to -118.2f, 41.9f to -87.6f, 43.7f to -79.4f, 6.5f to 3.4f,
        -1.3f to 36.8f, -26.2f to 28f, 24.7f to 46.7f, 33.3f to 44.4f, 32.7f to 51.7f, 29.6f to 52.5f,
        38.1f to 46.3f, 36.3f to 59.6f, 45.5f to 9.2f, 40.4f to -3.7f, 59.3f to 18.1f, 52.2f to 21f, 47.5f to 19f
    ).map { (a, b) -> ll(a, b) }

    /** Satellites: inclination, ascending node, orbit radius (in globe radii), speed, phase. */
    val SATELLITES = listOf(
        floatArrayOf(52f * DEG, 20f * DEG, 1.22f, 0.55f, 0f),
        floatArrayOf(97f * DEG, -40f * DEG, 1.30f, 0.42f, 2.1f),
        floatArrayOf(28f * DEG, 110f * DEG, 1.40f, 0.33f, 4.0f),
        floatArrayOf(65f * DEG, 200f * DEG, 1.18f, 0.62f, 1.2f)
    )

    val SUN: FloatArray = floatArrayOf(-0.55f, 0.45f, 0.7f).let { v ->
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / m, v[1] / m, v[2] / m)
    }
}

/** A point projected to the canvas: x, y and depth (z > 0 faces the viewer). */
private class P(val x: Float, val y: Float, val z: Float, val vx: Float, val vy: Float)

private fun DrawScope.drawEarth(a: EarthAnim, land: List<FloatArray>, dark: Boolean) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.34f
    val col = a.color
    val t = a.t

    fun view(x0: Float, y0: Float, z0: Float): FloatArray {
        val ct = cos(TILT); val st = sin(TILT)
        return floatArrayOf(x0, y0 * ct - z0 * st, y0 * st + z0 * ct)
    }
    fun proj(v: FloatArray, scale: Float = 1f): P {
        val cr = cos(a.rot); val sr = sin(a.rot)
        val x = v[0] * cr + v[2] * sr; val z = -v[0] * sr + v[2] * cr
        val q = view(x * scale, v[1] * scale, z * scale)
        return P(c.x + q[0] * r, c.y - q[1] * r, q[2], q[0], q[1])
    }
    fun hidden(p: P) = p.z < 0 && hypot(p.x - c.x, p.y - c.y) < r
    fun slerp(p0: FloatArray, p1: FloatArray, k: Float): FloatArray {
        val dot = (p0[0] * p1[0] + p0[1] * p1[1] + p0[2] * p1[2]).coerceIn(-1f, 1f)
        val d = acos(dot); val s = sin(d)
        if (s < 1e-4f) return p0
        val w1 = sin((1 - k) * d) / s; val w2 = sin(k * d) / s
        val lift = 1 + sin(PI.toFloat() * k) * 0.32f * d
        return floatArrayOf((p0[0] * w1 + p1[0] * w2) * lift, (p0[1] * w1 + p1[1] * w2) * lift, (p0[2] * w1 + p1[2] * w2) * lift)
    }
    fun satPos(s: FloatArray, ang: Float): P {
        val inc = s[0]; val node = s[1]; val rad = s[2]
        var x = cos(ang); var z = sin(ang)
        val y = -z * sin(inc); z *= cos(inc)
        val cn = cos(node); val sn = sin(node)
        val x2 = x * cn + z * sn; val z2 = -x * sn + z * cn
        x = x2
        val q = view(x * rad, y * rad, z2 * rad)
        return P(c.x + q[0] * r, c.y - q[1] * r, q[2], q[0], q[1])
    }
    fun glowDot(at: Offset, radius: Float, color: Color, alpha: Float) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * 0.55f), Color.Transparent), at, radius * 4.5f), radius * 4.5f, at)
        drawCircle(color.copy(alpha = alpha), radius, at)
    }
    fun arc(p0: FloatArray, p1: FloatArray, head: Float, tail: Float, alpha: Float, w: Float) {
        val n = 40
        for (k in 0 until n) {
            val k0 = tail + (head - tail) * k / n; val k1 = tail + (head - tail) * (k + 1) / n
            if (k1 < 0f || k0 > 1f) continue
            val a0 = proj(slerp(p0, p1, max(0f, k0))); val a1 = proj(slerp(p0, p1, min(1f, k1)))
            if (hidden(a0)) continue
            drawLine(col.copy(alpha = alpha * (0.2f + 0.8f * k / n)), Offset(a0.x, a0.y), Offset(a1.x, a1.y), w, StrokeCap.Round)
        }
        val h = proj(slerp(p0, p1, head.coerceIn(0f, 1f)))
        if (!hidden(h)) glowDot(Offset(h.x, h.y), w * 1.1f, Color.White, alpha)
    }
    fun pin(v: FloatArray, sz: Float, alpha: Float, pulse: Float?) {
        val p = proj(v); if (p.z < 0.05f) return
        glowDot(Offset(p.x, p.y), sz, col, alpha)
        if (pulse != null) drawCircle(col.copy(alpha = (1 - pulse) * 0.9f), sz + pulse * 14.dp.toPx() / 2, Offset(p.x, p.y), style = Stroke(1.2f))
    }
    fun drawSat(p: P, heading: Float, glow: Float) {
        val u = 1.dp.toPx() * 0.9f
        rotate(Math.toDegrees(heading.toDouble()).toFloat(), Offset(p.x, p.y)) {
            val panel = if (dark) Color(0xD996AADC) else Color(0xD9465A8C)
            drawRect(panel, Offset(p.x - 9 * u, p.y - 1.6f * u), Size(6 * u, 3.2f * u))
            drawRect(panel, Offset(p.x + 3 * u, p.y - 1.6f * u), Size(6 * u, 3.2f * u))
            drawRect(if (dark) Color(0xFFE8EAF6) else Color(0xFF333344), Offset(p.x - 1.8f * u, p.y - 1.8f * u), Size(3.6f * u, 3.6f * u))
        }
        glowDot(Offset(p.x, p.y), u, col, glow)
    }
    fun orbitPath(s: FloatArray, front: Boolean): Path {
        val path = Path(); var open = false
        for (k in 0..96) {
            val p = satPos(s, k / 96f * 2 * PI.toFloat())
            val show = if (front) p.z >= 0 else p.z < 0 && hypot(p.x - c.x, p.y - c.y) > r
            if (show) { if (open) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y); open = true } else open = false
        }
        return path
    }

    val sky = Color(0xFF7896FF)
    // atmosphere halo
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent, 0.72f to lerp(col, sky, 0.5f).copy(alpha = if (dark) 0.38f else 0.25f),
            0.82f to col.copy(alpha = if (dark) 0.12f else 0.08f), 1f to Color.Transparent,
            center = c, radius = r * 1.32f
        ), r * 1.32f, c
    )

    val satSpeed = if (a.mode == EarthMode.CONNECTING) 2.2f else 1f
    val satAngles = EarthData.SATELLITES.map { it[4] + t * it[3] * satSpeed }
    EarthData.SATELLITES.forEach { drawPath(orbitPath(it, front = false), col.copy(alpha = 0.12f), style = Stroke(0.8f.dp.toPx())) }
    EarthData.SATELLITES.forEachIndexed { i, s ->
        val p = satPos(s, satAngles[i])
        if (p.z < 0 && hypot(p.x - c.x, p.y - c.y) >= r) {
            val q = satPos(s, satAngles[i] + 0.05f); drawSat(p, atan2(q.y - p.y, q.x - p.x) + PI.toFloat() / 2, 0.5f)
        }
    }

    // ocean, lit from the sun's side
    val sun = EarthData.SUN
    val ocean = if (dark) Color(0xFF0E1220) else Color(0xFFE2E8F4)
    val sunAt = Offset(c.x + sun[0] * r * 0.55f, c.y - sun[1] * r * 0.55f)
    drawCircle(
        Brush.radialGradient(
            0f to lerp(ocean, lerp(col, Color(0xFF5A82DC), 0.6f), if (dark) 0.32f else 0.18f),
            0.55f to lerp(ocean, Color(0xFF1E2D50), if (dark) 0.35f else 0.05f),
            1f to ocean, center = sunAt, radius = r * 1.3f
        ), r, c
    )

    // graticule
    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }) {
        val grid = col.copy(alpha = if (dark) 0.07f else 0.09f)
        fun line(points: List<FloatArray>) {
            var prev: P? = null
            for (v in points) {
                val p = proj(v)
                if (p.z >= 0 && prev != null && prev.z >= 0) drawLine(grid, Offset(prev.x, prev.y), Offset(p.x, p.y), 0.6f.dp.toPx())
                prev = p
            }
        }
        for (lat in -60..60 step 30) line((-180..180 step 6).map { ll(lat.toFloat(), it.toFloat()) })
        for (lon in -180 until 180 step 30) line((-90..90 step 6).map { ll(it.toFloat(), lon.toFloat()) })
    }

    // land dots, shaded by sunlight and by the limb, drawn in a few alpha bands
    val landColor = lerp(if (dark) Color(0xFFC4CEE8) else Color(0xFF465470), col, if (dark) 0.28f else 0.35f)
    val bands = Array(6) { ArrayList<Offset>(1200) }
    for (v in land) {
        val p = proj(v); if (p.z < 0) continue
        val lam = max(0f, p.vx * sun[0] + p.vy * sun[1] + p.z * sun[2])
        val alpha = (0.18f + 0.82f * lam) * (0.35f + 0.65f * p.z)
        bands[min(5, (alpha * 6).toInt())] += Offset(p.x, p.y)
    }
    val dotPx = 1.3f.dp.toPx() * 0.85f
    bands.forEachIndexed { i, pts ->
        if (pts.isNotEmpty()) drawPoints(pts, PointMode.Points, landColor.copy(alpha = (i + 0.5f) / 6f), dotPx, StrokeCap.Square)
    }

    // city lights on the night side
    val warm = Color(0xFFFFCD82)
    for (v in EarthData.CITIES) {
        val p = proj(v); if (p.z < 0.05f) continue
        val lam = p.vx * sun[0] + p.vy * sun[1] + p.z * sun[2]
        if (lam > 0.25f) continue
        val alpha = min(1f, (0.25f - lam) * 2.2f) * (0.6f + 0.4f * sin(t * 2 + v[0] * 9)) * (if (dark) 0.9f else 0.7f)
        glowDot(Offset(p.x, p.y), 1.2f.dp.toPx() * 0.8f, warm, alpha)
    }

    // night side and rim light
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent, 1f to (if (dark) Color(0x8C000000) else Color(0x1F141E3C)),
            center = Offset(c.x + sun[0] * r * 0.9f, c.y - sun[1] * r * 0.9f), radius = r * 1.25f
        ), r, c
    )
    drawCircle(
        Brush.radialGradient(0.82f to Color.Transparent, 1f to lerp(col, Color(0xFF8CB4FF), 0.4f).copy(alpha = if (dark) 0.45f else 0.35f), center = c, radius = r),
        r, c
    )

    when (a.mode) {
        EarthMode.CONNECTING -> {
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }) {
                val yy = c.y - r + ((t * 0.9f) % 1f) * 2 * r
                val band = 13.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = 0.28f)), yy - band, yy), Offset(0f, yy - band), Size(size.width, band))
                drawLine(col.copy(alpha = 0.8f), Offset(0f, yy), Offset(size.width, yy), 1f)
            }
            EarthData.CANDIDATES.forEachIndexed { i, cand ->
                val ph = (t * 0.65f + i.toFloat() / EarthData.CANDIDATES.size) % 1f
                arc(EarthData.HOME, cand, ph * 1.35f, ph * 1.35f - 0.5f, 0.9f, 1.4f.dp.toPx() * 0.8f)
                pin(cand, 1.6f.dp.toPx() * 0.8f, 0.85f, null)
            }
        }
        EarthMode.ON -> {
            arc(EarthData.HOME, EarthData.EXIT, 1f, 0f, 0.55f, 1.4f.dp.toPx() * 0.8f)
            val k = (t * 0.5f) % 1f
            arc(EarthData.HOME, EarthData.EXIT, k, k - 0.3f, 1f, 2.2f.dp.toPx() * 0.8f)
            pin(EarthData.EXIT, 2.6f.dp.toPx() * 0.8f, 1f, (t * 0.8f) % 1f)
        }
        EarthMode.OFF -> EarthData.CANDIDATES.forEach { pin(it, 1.3f.dp.toPx() * 0.8f, 0.4f, null) }
    }
    pin(EarthData.HOME, 2.6f.dp.toPx() * 0.8f, 1f, if (a.mode == EarthMode.OFF) null else (t * 1.1f) % 1f)

    // satellites in front, with signal beams to the ground while connecting or connected
    EarthData.SATELLITES.forEachIndexed { i, s ->
        val p = satPos(s, satAngles[i]); if (p.z < 0) return@forEachIndexed
        val q = satPos(s, satAngles[i] + 0.05f)
        if (a.mode != EarthMode.OFF) {
            val g = proj(if (i % 2 == 1) EarthData.HOME else EarthData.EXIT)
            if (g.z > 0.1f) {
                val pulse = (t * 1.5f + i * 0.37f) % 1f
                drawLine(col.copy(alpha = (if (a.mode == EarthMode.ON) 0.22f else 0.35f) * (1 - pulse * 0.6f)), Offset(p.x, p.y), Offset(g.x, g.y), 0.8f.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                drawCircle(col.copy(alpha = 0.9f), 1.2f.dp.toPx(), Offset(p.x + (g.x - p.x) * pulse, p.y + (g.y - p.y) * pulse))
            }
        }
        drawSat(p, atan2(q.y - p.y, q.x - p.x) + PI.toFloat() / 2, 1f)
    }
    EarthData.SATELLITES.forEach { drawPath(orbitPath(it, front = true), col.copy(alpha = 0.22f), style = Stroke(0.8f.dp.toPx())) }

    // shockwave when the tunnel comes up
    if (a.burst >= 0f) {
        val k = a.burst / 1.3f
        drawCircle(col.copy(alpha = (1 - k) * 0.9f), r + k * r * 0.4f, c, style = Stroke((2.5f * (1 - k) + 0.4f).dp.toPx()))
        drawCircle(col.copy(alpha = (1 - k) * 0.16f), r, c)
    }
}

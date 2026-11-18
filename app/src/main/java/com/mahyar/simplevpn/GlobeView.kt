package com.mahyar.simplevpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated holographic globe: layered orbital rings, wireframe grid,
 * continent node dots and telemetry arcs between beacons.
 */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var sphereShader: RadialGradient? = null
    private val arcRect = RectF()

    var isConnected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // 0..1 continuous phase used for dash motion and beacon pulses
    private var phase = 0f
    private var ringAngle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    private val ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 60000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ringAngle = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    // Continent dots: normalized (dx, dy) from center, radius, base alpha, color index
    private data class Dot(val dx: Float, val dy: Float, val r: Float, val alpha: Float, val bright: Boolean)

    private val dots = listOf(
        Dot(-0.074f, -0.284f, 0.017f, 0.95f, true),   // 88,68
        Dot(-0.137f, -0.316f, 0.014f, 0.80f, false),  // 82,65
        Dot(-0.011f, -0.242f, 0.013f, 0.75f, false),  // 94,72
        Dot(-0.116f, -0.221f, 0.013f, 0.70f, false),  // 84,74
        Dot(0.053f, -0.263f, 0.015f, 0.85f, false),   // 100,70
        Dot(0.347f, -0.242f, 0.015f, 0.80f, false),   // 128,72
        Dot(0.432f, -0.179f, 0.016f, 0.90f, false),   // 136,78
        Dot(0.526f, -0.221f, 0.017f, 0.95f, true),    // 145,74
        Dot(0.453f, -0.074f, 0.014f, 0.70f, false),   // 138,88
        Dot(-0.526f, -0.200f, 0.014f, 0.55f, false),  // 45,76
        Dot(-0.453f, -0.242f, 0.015f, 0.65f, false),  // 52,72
        Dot(-0.474f, -0.105f, 0.013f, 0.50f, false)   // 50,85
    )

    // Beacon anchors (normalized)
    private val frankfurt = floatArrayOf(-0.074f, -0.284f)
    private val tokyo = floatArrayOf(0.526f, -0.221f)
    private val london = floatArrayOf(-0.200f, -0.347f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = min(w, h) / 2f
        val r = c * 0.72f
        sphereShader = RadialGradient(
            c - r * 0.25f, c - r * 0.35f, r * 1.15f,
            intArrayOf(
                Color.argb(88, 2, 132, 199),
                Color.argb(215, 5, 7, 10),
                Color.argb(255, 2, 3, 6)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        spherePaint.shader = sphereShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val c = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val r = c * 0.72f
        val live = isConnected
        val dim = if (live) 1f else 0.32f

        // --- outer orbital rings ---
        ringPaint.strokeWidth = r * 0.006f

        // Ring A: rotateX(68deg) rotateZ(spin)
        val ra = r * 1.36f
        canvas.save()
        canvas.rotate(ringAngle + 20f, cx, cy)
        ringPaint.color = Color.argb((15 * dim * 255).toInt(), 0, 210, 255)
        canvas.drawOval(cx - ra, cy - ra * 0.37f, cx + ra, cy + ra * 0.37f, ringPaint)
        canvas.restore()

        // Ring B: rotateY(64deg) dashed
        val rb = r * 1.26f
        ringPaint.color = Color.argb((20 * dim * 255).toInt(), 56, 189, 248)
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(r * 0.05f, r * 0.045f), phase * r * 0.2f)
        canvas.save()
        canvas.rotate(-8f, cx, cy)
        canvas.drawOval(cx - rb * 0.44f, cy - rb, cx + rb * 0.44f, cy + rb, ringPaint)
        canvas.restore()
        ringPaint.pathEffect = null

        // Ring C: plain
        val rc = r * 1.16f
        ringPaint.color = Color.argb((10 * dim * 255).toInt(), 2, 132, 199)
        canvas.drawCircle(cx, cy, rc, ringPaint)

        // --- ambient glow behind sphere ---
        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.5f,
            Color.argb(if (live) 52 else 18, 14, 165, 233),
            Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.5f, glowPaint)

        // --- sphere body ---
        spherePaint.shader = sphereShader
        canvas.drawCircle(cx, cy, r, spherePaint)
        spherePaint.shader = null
        spherePaint.color = Color.argb((26 * dim * 255).toInt(), 34, 211, 238)
        spherePaint.style = Paint.Style.STROKE
        spherePaint.strokeWidth = r * 0.006f
        canvas.drawCircle(cx, cy, r, spherePaint)
        spherePaint.style = Paint.Style.FILL

        // --- wireframe grid ---
        gridPaint.strokeWidth = r * 0.0055f
        gridPaint.color = Color.argb((38 * dim * 255).toInt(), 56, 189, 248)

        // vertical ellipses (longitude)
        for (rxScale in floatArrayOf(0.98f, 0.63f, 0.30f)) {
            canvas.drawOval(cx - r * rxScale, cy - r * 0.97f, cx + r * rxScale, cy + r * 0.97f, gridPaint)
        }
        canvas.drawLine(cx, cy - r * 0.98f, cx, cy + r * 0.98f, gridPaint)

        // horizontal ellipses (latitude)
        val latDefs = arrayOf(
            floatArrayOf(-0.42f, 0.82f, 0.19f),
            floatArrayOf(0f, 0.97f, 0.25f),
            floatArrayOf(0.42f, 0.82f, 0.19f)
        )
        for (ld in latDefs) {
            val lcy = cy + r * ld[0]
            val lrx = r * ld[1]
            val lry = r * ld[2]
            arcRect.set(cx - lrx, lcy - lry, cx + lrx, lcy + lry)
            canvas.drawOval(arcRect, gridPaint)
        }

        // --- continent dots ---
        for (d in dots) {
            dotPaint.color = if (d.bright) Color.argb((d.alpha * dim * 255).toInt(), 224, 242, 254)
            else Color.argb((d.alpha * dim * 255).toInt(), 56, 189, 248)
            canvas.drawCircle(cx + d.dx * r, cy + d.dy * r, d.r * r, dotPaint)
        }

        // --- telemetry arcs ---
        if (live) {
            val fx = cx + frankfurt[0] * r
            val fy = cy + frankfurt[1] * r
            val tx = cx + tokyo[0] * r
            val ty = cy + tokyo[1] * r
            val lx = cx + london[0] * r
            val ly = cy + london[1] * r

            arcPaint.strokeWidth = r * 0.0095f
            arcPaint.color = Color.argb(200, 0, 210, 255)
            arcPaint.pathEffect = DashPathEffect(floatArrayOf(r * 0.075f, r * 0.06f), -phase * r * 0.55f)
            canvas.drawPath(bezier(fx, fy, cx + 0.11f * r, cy - 0.52f * r, tx, ty), arcPaint)

            arcPaint.strokeWidth = r * 0.006f
            arcPaint.color = Color.argb(120, 56, 189, 248)
            arcPaint.pathEffect = DashPathEffect(floatArrayOf(r * 0.04f, r * 0.05f), -phase * r * 0.35f)
            canvas.drawPath(bezier(fx, fy, fx - 0.06f * r, fy - 0.11f * r, lx, ly), arcPaint)
            arcPaint.pathEffect = null
        }

        // --- beacons ---
        drawBeacon(canvas, cx + frankfurt[0] * r, cy + frankfurt[1] * r, r, live, main = true)
        drawBeacon(canvas, cx + tokyo[0] * r, cy + tokyo[1] * r, r, live, main = false)
        if (!live) {
            beaconPaint.color = Color.argb((110 * dim * 255).toInt(), 129, 140, 248)
            canvas.drawCircle(cx + london[0] * r, cy + london[1] * r, r * 0.011f, beaconPaint)
        } else {
            beaconPaint.color = Color.argb(205, 129, 140, 248)
            canvas.drawCircle(cx + london[0] * r, cy + london[1] * r, r * 0.011f, beaconPaint)
        }
    }

    private fun drawBeacon(canvas: Canvas, x: Float, y: Float, r: Float, live: Boolean, main: Boolean) {
        // pulse ring
        val pulse = if (main) phase % 1f else (phase + 0.4f) % 1f
        val ringR = r * (if (main) 0.011f + pulse * 0.037f else 0.008f + pulse * 0.026f)
        val ringAlpha = (1f - pulse) * (if (live) 90 else 26)
        beaconPaint.color = Color.argb(ringAlpha.toInt(), if (main) 0 else 56, if (main) 210 else 189, if (main) 255 else 248)
        canvas.drawCircle(x, y, ringR, beaconPaint)

        // core
        val coreAlpha = if (live) 180 else 60
        beaconPaint.color = Color.argb(coreAlpha, if (main) 0 else 56, if (main) 210 else 189, if (main) 255 else 248)
        canvas.drawCircle(x, y, r * if (main) 0.018f else 0.012f, beaconPaint)
        beaconPaint.color = Color.argb(if (live) 255 else 120, 255, 255, 255)
        canvas.drawCircle(x, y, r * if (main) 0.0095f else 0.006f, beaconPaint)
    }

    private fun bezier(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val p = Path()
        p.moveTo(x0, y0)
        p.quadTo(x1, y1, x2, y2)
        return p
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
        ringAnimator.cancel()
    }
}

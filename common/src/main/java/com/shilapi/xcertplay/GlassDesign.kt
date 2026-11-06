package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Static glass materials: no screen capture, live blur or per-frame allocations on the head unit. */
internal object GlassDesign {
    const val PANEL_RADIUS_DP = 28
    const val CONTROL_RADIUS_DP = 20
    const val PANEL_INSET_DP = 24
    const val SECTION_GAP_DP = 20

    fun backdrop(palette: DiPlayPalette) = object : GradientDrawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glow: Shader? = null
        init { setColor(palette.background) }
        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) return
            glow = RadialGradient(bounds.width() * .12f, bounds.height() * .05f,
                maxOf(bounds.width(), bounds.height()) * .85f,
                ColorUtils.setAlphaComponent(palette.accent, 26), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            paint.shader = glow
            canvas.drawRect(bounds, paint)
        }
    }

    fun surface(context: Context, color: Int, border: Int, radiusDp: Int = PANEL_RADIUS_DP) =
        ContinuousGlassDrawable(color, border, context.resources.displayMetrics.density).apply {
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }

    fun styleButton(button: Button, palette: DiPlayPalette, primary: Boolean = false) = button.apply {
        isAllCaps = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(if (primary) palette.onAccent else palette.primaryText)
        backgroundTintList = null
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(palette.ripple),
            surface(context, if (primary) palette.accent else palette.button, palette.outline, CONTROL_RADIUS_DP), null)
        val inset = (16 * resources.displayMetrics.density).toInt()
        setPadding(inset, paddingTop, inset, paddingBottom)
    }
}

/** Superelliptic corners join the straight edges with gradually increasing curvature. */
internal class ContinuousGlassDrawable(
    private val fill: Int,
    private val border: Int,
    private val density: Float,
) : GradientDrawable() {
    private val outlinePath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: Shader? = null
    private var lastRadius = -1f
    init { setColor(fill) }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun rebuild() {
        if (bounds.isEmpty) return
        val inset = density * .5f
        val l = bounds.left + inset; val t = bounds.top + inset
        val r = bounds.right - inset; val b = bounds.bottom - inset
        val radius = min(cornerRadius * 1.6f, min(r - l, b - t) * .5f).coerceAtLeast(0f)
        lastRadius = cornerRadius
        outlinePath.reset()
        if (radius <= 0f) outlinePath.addRect(l, t, r, b, Path.Direction.CW)
        else {
            fun corner(cx: Float, cy: Float, from: Double) {
                for (step in 0..CORNER_STEPS) {
                    val angle = from + PI * .5 * step / CORNER_STEPS
                    fun smooth(v: Double) = (if (v < 0) -1 else 1) * abs(v).pow(.5).toFloat()
                    outlinePath.lineTo(cx + radius * smooth(cos(angle)), cy + radius * smooth(sin(angle)))
                }
            }
            outlinePath.moveTo(l + radius, t)
            corner(r - radius, t + radius, -PI * .5)
            corner(r - radius, b - radius, 0.0)
            corner(l + radius, b - radius, PI * .5)
            corner(l + radius, t + radius, PI)
            outlinePath.close()
        }
        val isDark = ColorUtils.calculateLuminance(fill) < .25
        val top = ColorUtils.blendARGB(fill, Color.WHITE, if (isDark) .055f else .22f)
        gradient = LinearGradient(0f, t, 0f, b, top, fill, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (lastRadius != cornerRadius) rebuild()
        if (Color.alpha(fill) == 0 && Color.alpha(border) == 0) return
        paint.style = Paint.Style.FILL; paint.shader = gradient; paint.alpha = (alpha * .96f).toInt()
        canvas.drawPath(outlinePath, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        val edge = ColorUtils.blendARGB(border, Color.WHITE, .10f)
        paint.color = edge; paint.alpha = (Color.alpha(edge) * alpha / 255f * .65f).toInt()
        canvas.drawPath(outlinePath, paint)
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if (lastRadius != cornerRadius) rebuild()
        if (!outlinePath.isEmpty) {
            @Suppress("DEPRECATION")
            outline.setConvexPath(outlinePath)
        }
    }

    private companion object { const val CORNER_STEPS = 32 }
}

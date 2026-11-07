package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Quiet, opaque surfaces with continuous corners; no live blur or per-frame effects. */
internal object GlassDesign {
    const val PANEL_RADIUS_DP = 24
    const val CONTROL_RADIUS_DP = 28
    const val PANEL_INSET_DP = 24
    const val SECTION_GAP_DP = 16
    const val NAV_HEIGHT_DP = 64
    const val NAV_ITEM_WIDTH_DP = 152
    const val NAV_MARGIN_DP = 16
    const val NAV_INSET_DP = 8
    const val NAV_ELEVATION_DP = 3

    fun backdrop(palette: DiPlayPalette) = GradientDrawable().apply { setColor(palette.background) }

    fun surface(context: Context, color: Int, border: Int, radiusDp: Int = PANEL_RADIUS_DP) =
        ContinuousGlassDrawable(color, border, context.resources.displayMetrics.density).apply {
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }

    fun connectionSurface(context: Context, palette: DiPlayPalette) = surface(context,
        palette.surface,
        Color.TRANSPARENT)

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

    }

    override fun draw(canvas: Canvas) {
        if (lastRadius != cornerRadius) rebuild()
        if (Color.alpha(fill) == 0 && Color.alpha(border) == 0) return
        paint.style = Paint.Style.FILL; paint.shader = null; paint.color = fill; paint.alpha = Color.alpha(fill) * alpha / 255
        canvas.drawPath(outlinePath, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        val edge = border
        paint.color = edge; paint.alpha = (Color.alpha(edge) * alpha / 255f * .32f).toInt()
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

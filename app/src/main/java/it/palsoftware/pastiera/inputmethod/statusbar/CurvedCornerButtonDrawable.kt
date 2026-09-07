package it.palsoftware.pastiera.inputmethod.statusbar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import it.palsoftware.pastiera.inputmethod.StatusBarController

/** Draws the button and its border inside the display contour without shrinking its touch target. */
internal class CurvedCornerButtonDrawable(
    private val view: View,
    private val normalColor: Int,
    private val pressedColor: Int,
    private val cornerRadius: Float,
    private val borderColor: Int?,
    private val borderWidth: Int,
    private val leftEdge: Boolean
) : Drawable() {
    private var lastTopMultiplier = -1
    private var drawableAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPath = Path()
    private val displayPath = Path()
    private val location = IntArray(2)
    private val chromeLocation = IntArray(2)
    private var lastPlacementKey: List<Any>? = null
    private var lastPlacement = ContentPlacement(0f, 0f, 1f)
    data class ContentPlacement(val x: Float, val y: Float, val scale: Float)


    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    private fun updatePath() {
        val halfStroke = borderWidth / 2f
        buttonPath.reset()
        val rect = RectF(bounds).apply { inset(halfStroke, halfStroke) }
        val bottomRadius = cornerRadius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        val multiplier = it.palsoftware.pastiera.SettingsManager.getTitan2EliteTopCornerMultiplier(view.context)
        if (multiplier != lastTopMultiplier) {
            lastTopMultiplier = multiplier
            invalidateSelf()
        }
        val topRadius = (cornerRadius * multiplier).coerceAtMost(minOf(rect.width() - bottomRadius, rect.height() - bottomRadius))
        buttonPath.addRoundRect(
            rect,
            floatArrayOf(if (leftEdge) 0f else bottomRadius, if (leftEdge) 0f else bottomRadius,
                if (leftEdge) bottomRadius else 0f, if (leftEdge) bottomRadius else 0f,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius), Path.Direction.CW
        )
        var ancestor = view.parent
        while (ancestor != null && ancestor !is StatusBarController.ImeChromeLayout) ancestor = ancestor.parent
        val chrome = ancestor as? StatusBarController.ImeChromeLayout
        val radii = chrome?.bottomCornerRadiiPx
        if (chrome != null && radii != null) {
            view.getLocationInWindow(location)
            chrome.getLocationInWindow(chromeLocation)
            val x = (location[0] - chromeLocation[0]).toFloat()
            val y = (location[1] - chromeLocation[1]).toFloat()
            val density = view.resources.displayMetrics.density
            // A uniform inset preserves circular, concentric display corners.
            val sideInset = 0.5f * density + halfStroke
            val bottomInset = sideInset
            val leftX = (radii.first - sideInset).coerceAtLeast(0f)
            val rightX = (radii.second - sideInset).coerceAtLeast(0f)
            val leftY = (radii.first - bottomInset).coerceAtLeast(0f)
            val rightY = (radii.second - bottomInset).coerceAtLeast(0f)
            displayPath.reset()
            displayPath.addRoundRect(
                RectF(sideInset - x, -2f * maxOf(radii.first, radii.second) - y,
                    chrome.width - sideInset - x, chrome.height - bottomInset - y),
                floatArrayOf(0f, 0f, 0f, 0f, rightX, rightY, leftX, leftY), Path.Direction.CW
            )
            buttonPath.op(displayPath, Path.Op.INTERSECT)
            // Blend the upper edge into the display circle with matching tangents.
            // Work in mirrored coordinates so both sides have identical geometry.
            val radius = if (leftEdge) leftX else rightX
            val circleBottom = chrome.height - bottomInset - y
            val circleTop = circleBottom - radius
            val outerX = if (leftEdge) sideInset - x else chrome.width - sideInset - x
            fun boundary(atY: Float): Float {
                val dy = (atY - circleTop).coerceIn(0f, radius)
                return if (radius > 0f) radius - kotlin.math.sqrt((radius * radius - dy * dy).coerceAtLeast(0f)) else 0f
            }
            val joinY = (rect.top + topRadius).coerceAtMost(rect.bottom - bottomRadius)
            val joinX = boundary(joinY)
            val dy = (joinY - circleTop).coerceIn(0f, radius)
            val tangentX = if (radius > 0f) dy / radius else 0f
            val tangentY = if (radius > 0f) kotlin.math.sqrt((1f - tangentX * tangentX).coerceAtLeast(0f)) else 1f
            val available = if (leftEdge) rect.right - outerX else outerX - rect.left
            val startX = (boundary(rect.top) + topRadius).coerceAtMost(available - bottomRadius)
            val handle = (joinY - rect.top) * 0.55f
            fun screenX(localX: Float) = outerX + if (leftEdge) localX else -localX
            val blend = Path().apply {
                moveTo(screenX(startX), rect.top)
                cubicTo(screenX(startX - handle), rect.top,
                    screenX(joinX - tangentX * handle), joinY - tangentY * handle,
                    screenX(joinX), joinY)
                // Continue outside the display contour; its circle supplies the rest.
                lineTo(screenX(-chrome.width.toFloat()), joinY + tangentY * chrome.width)
                lineTo(screenX(-chrome.width.toFloat()), rect.bottom + chrome.height)
                lineTo(screenX(chrome.width.toFloat() * 2), rect.bottom + chrome.height)
                lineTo(screenX(chrome.width.toFloat() * 2), rect.top)
                close()
            }
            buttonPath.op(blend, Path.Op.INTERSECT)
        }
    }

    /** Keep the complete icon/text box 2 dp inside the drawn shape, moving only inward/up. */
    fun contentCenter(contentWidth: Float, contentHeight: Float, preferredX: Float,
                      preferredY: Float, leftEdge: Boolean): ContentPlacement {
        setBounds(0, 0, view.width, view.height)
        updatePath()
        val minScale = 1f - it.palsoftware.pastiera.SettingsManager.getTitan2EliteMaxIconShrink(view.context) / 100f
        val key = listOf(minScale,view.width.toFloat(), view.height.toFloat(), contentWidth, contentHeight,
            preferredX, preferredY, location[0].toFloat(), location[1].toFloat(),
            chromeLocation[0].toFloat(), chromeLocation[1].toFloat(),
            buttonPath.approximate(0.5f).contentHashCode(), if (leftEdge) 1f else 0f)
        if (lastPlacementKey == key) return lastPlacement
        val region = android.graphics.Region().apply {
            setPath(buttonPath, android.graphics.Region(this@CurvedCornerButtonDrawable.bounds))
        }
        val gap = 2f * view.resources.displayMetrics.density + borderWidth / 2f
        fun findPlacement(scale: Float): ContentPlacement? {
            val halfWidth = contentWidth * scale / 2f + gap
            val halfHeight = contentHeight * scale / 2f + gap
            fun fits(x: Float, y: Float): Boolean =
                region.contains(kotlin.math.floor(x - halfWidth).toInt(), kotlin.math.floor(y - halfHeight).toInt()) &&
                region.contains(kotlin.math.ceil(x + halfWidth).toInt(), kotlin.math.floor(y - halfHeight).toInt()) &&
                region.contains(kotlin.math.floor(x - halfWidth).toInt(), kotlin.math.ceil(y + halfHeight).toInt()) &&
                region.contains(kotlin.math.ceil(x + halfWidth).toInt(), kotlin.math.ceil(y + halfHeight).toInt())
            if (fits(preferredX, preferredY)) return ContentPlacement(preferredX, preferredY, scale)
            var best: ContentPlacement? = null
            var bestDistance = Float.POSITIVE_INFINITY
            val maxUp = (preferredY - halfHeight).toInt().coerceAtLeast(0)
            val maxInward = (if (leftEdge) view.width - halfWidth - preferredX else preferredX - halfWidth)
                .toInt().coerceAtLeast(0)
            for (up in 0..maxUp) {
                for (inward in 0..maxInward) {
                    val distance = (up * up + inward * inward).toFloat()
                    if (distance >= bestDistance) continue
                    val x = preferredX + if (leftEdge) inward else -inward
                    val y = preferredY - up
                    if (fits(x, y)) {
                        bestDistance = distance
                        best = ContentPlacement(x, y, scale)
                    }
                }
            }
            return best
        }
        // Search permitted sizes first. If none fits the contour, honor the
        // user's size limit while keeping the content inside the button bounds.
        val minimumPercent = kotlin.math.round(minScale * 100f).toInt()
        val permitted = (100 downTo minimumPercent step 2).firstNotNullOfOrNull { findPlacement(it / 100f) }
            ?: findPlacement(minScale)
        val limited = permitted ?: run {
            val smaller = (minimumPercent downTo 10 step 2).firstNotNullOfOrNull { findPlacement(it / 100f) }
                ?: ContentPlacement(preferredX, preferredY, minScale)
            val halfWidth = (contentWidth * minScale / 2f).coerceAtMost(view.width / 2f)
            val halfHeight = (contentHeight * minScale / 2f).coerceAtMost(view.height / 2f)
            ContentPlacement(
                smaller.x.coerceIn(halfWidth, view.width - halfWidth),
                smaller.y.coerceIn(halfHeight, view.height - halfHeight), minScale)
        }
        lastPlacementKey = key
        lastPlacement = limited
        return limited
    }

    override fun draw(canvas: Canvas) {
        updatePath()
        paint.style = Paint.Style.FILL
        paint.color = if (state.contains(android.R.attr.state_pressed)) pressedColor else normalColor
        paint.alpha = android.graphics.Color.alpha(paint.color) * drawableAlpha / 255
        canvas.drawPath(buttonPath, paint)
        if (borderColor != null && borderWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderWidth.toFloat()
            paint.color = borderColor
            paint.alpha = android.graphics.Color.alpha(paint.color) * drawableAlpha / 255
            canvas.drawPath(buttonPath, paint)
        }
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

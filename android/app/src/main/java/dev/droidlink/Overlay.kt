package dev.droidlink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * What the laptop's brain is looking at, drawn on the live /camera/stream so
 * you can watch it think: target box, label, aim line and a status line.
 * Only drawn on the stream, never on snapshots the model or tracker use.
 */
class Overlay {
    private class State(
        val box: FloatArray?,        // x1, y1, x2, y2 as fractions, or null
        val label: String,
        val status: String,
        val color: Int,
        val ts: Long,
    )

    @Volatile private var state: State? = null

    fun set(box: FloatArray?, label: String, status: String, color: String) {
        state = if (color == "clear") null else State(box, label, status, when (color) {
            "red" -> Color.rgb(240, 60, 60)
            "yellow" -> Color.rgb(250, 200, 40)
            "blue" -> Color.rgb(80, 160, 255)
            else -> Color.rgb(60, 220, 90)
        }, System.currentTimeMillis())
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun draw(c: Canvas, w: Int, h: Int) {
        val s = state ?: return
        val stale = System.currentTimeMillis() - s.ts > STALE_MS
        val color = if (stale) Color.GRAY else s.color
        val ts = w / 24f
        text.textSize = ts
        line.strokeWidth = w / 160f

        // Centre line: where "straight ahead" is
        line.color = Color.argb(120, 255, 255, 255)
        c.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), line)

        s.box?.let { b ->
            val l = b[0] * w; val t = b[1] * h; val r = b[2] * w; val btm = b[3] * h
            line.color = color
            line.strokeWidth = w / 110f
            c.drawRect(l, t, r, btm, line)
            // Aim line from straight-ahead to the target's centre
            val cx = (l + r) / 2; val cy = (t + btm) / 2
            line.strokeWidth = w / 200f
            c.drawLine(w / 2f, cy, cx, cy, line)
            c.drawCircle(cx, cy, w / 80f, line)
            // Label above the box
            if (s.label.isNotEmpty()) {
                val tw = text.measureText(s.label)
                val ty = (t - ts * 0.3f).coerceAtLeast(ts * 1.2f)
                fill.color = color
                c.drawRect(l, ty - ts, l + tw + ts * 0.6f, ty + ts * 0.25f, fill)
                c.drawText(s.label, l + ts * 0.3f, ty, text)
            }
        }

        // Status line along the bottom
        if (s.status.isNotEmpty()) {
            fill.color = Color.argb(160, 0, 0, 0)
            c.drawRect(0f, h - ts * 1.6f, w.toFloat(), h.toFloat(), fill)
            c.drawText(s.status + if (stale) " (stale)" else "", ts * 0.4f, h - ts * 0.45f, text)
        }
    }

    companion object { private const val STALE_MS = 2_000L }
}

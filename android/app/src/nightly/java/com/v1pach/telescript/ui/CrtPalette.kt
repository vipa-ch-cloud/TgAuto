package com.v1pach.telescript.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure (no Compose/Android) palette for the nightly CRT console, derived from the theme accent and error
 * colors (ARGB ints). The screen is clearly darker than the panels, text and glow are light tints of the
 * accent, and the grid/scanline/vignette are tinted too. Every text color keeps contrast >= 4.5:1 against
 * the screen and the panels; error (reddish) and warning (amber) stay distinguishable from text and each other.
 */
class CrtPalette(
    val screen: Int, val panel: Int, val text: Int, val dim: Int, val prompt: Int, val error: Int, val warning: Int,
    val selection: Int, val glow: Int, val subR: Int, val subG: Int, val subB: Int, val scanline: Int, val vignette: Int,
) {
    companion object {
        const val MIN_CONTRAST = 4.5

        /** [accent] = theme primary, [errorSeed] = theme error; [dark] only nudges the screen a bit lighter on light themes. */
        fun from(accent: Int, errorSeed: Int, dark: Boolean): CrtPalette {
            val (h, s0, _) = toHsl(accent)
            val s = s0.coerceIn(0.25f, 0.8f)
            val screen = hsl(h, s * 0.55f, if (dark) 0.055f else 0.085f)
            val panel = hsl(h, s * 0.45f, if (dark) 0.15f else 0.19f)
            fun readable(hue: Float, sat: Float, l: Float): Int {
                var c = hsl(hue, sat, l); var ll = l
                while ((contrast(c, screen) < MIN_CONTRAST || contrast(c, panel) < MIN_CONTRAST) && ll < 1f) { ll += 0.02f; c = hsl(hue, sat, min(ll, 1f)) }
                return c
            }
            val text = readable(h, 0.55f, 0.86f)
            val dim = readable(h, 0.30f, 0.68f)
            val prompt = readable(h, max(s, 0.6f), 0.76f)
            val eh = toHsl(errorSeed).first.let { if (abs(it - 40f) < 25f) 355f else it }
            val error = readable(eh, 0.85f, 0.68f)
            val warning = readable(40f, 0.9f, 0.70f)
            val glow = withAlpha(hsl(h, 0.9f, 0.82f), 0.45f)
            val tint = hsl(h, 0.9f, 0.5f)
            fun sub(rgb: Int) = withAlpha(mix(rgb, tint, 0.25f), 0.07f)
            return CrtPalette(
                screen = screen, panel = panel, text = text, dim = dim, prompt = prompt, error = error, warning = warning,
                selection = withAlpha(hsl(h, s, 0.30f), 0.75f), glow = glow,
                subR = sub(0xFF0000), subG = sub(0x00FF00), subB = sub(0x0000FF),
                scanline = withAlpha(hsl(h, s * 0.6f, 0.02f), 0.30f),
                vignette = withAlpha(hsl(h, s * 0.6f, 0.015f), 0.45f),
            )
        }

        fun withAlpha(rgb: Int, a: Float) = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = (((a shr sh) and 255) * (1 - t) + ((b shr sh) and 255) * t + 0.5f).toInt().coerceIn(0, 255)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** WCAG contrast ratio of two opaque colors (alpha ignored). */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        fun luminance(c: Int): Double {
            fun f(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * f((c shr 16) and 255) + 0.7152 * f((c shr 8) and 255) + 0.0722 * f(c and 255)
        }

        /** Returns opaque ARGB. h in degrees, s and l in 0..1. */
        fun hsl(h: Float, s: Float, l: Float): Int {
            val hh = ((h % 360f) + 360f) % 360f
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((hh / 60f) % 2f - 1f))
            val m = l - c / 2f
            val (r, g, b) = when ((hh / 60f).toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            fun q(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
        }

        fun toHsl(c: Int): Triple<Float, Float, Float> {
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            val l = (mx + mn) / 2f
            if (d == 0f) return Triple(0f, 0f, l)
            val s = d / (1f - abs(2f * l - 1f))
            val h = 60f * when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
            return Triple((h + 360f) % 360f, s, l)
        }
    }
}

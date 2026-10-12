package com.kyant.backdrop

/**
 * Master switch for the liquid-glass effects. While false, [drawBackdrop] and [drawPlainBackdrop]
 * draw only their flat `onDrawSurface` fill inside the shape, and `layerBackdrop` stops re-recording
 * the content it wraps — nothing samples it any more.
 *
 * Off for now: on a mid-range phone (Redmi Note 13, 120 Hz) the glass passes pushed median scroll
 * frames on Settings to ~53 ms. Flip back to true to restore the full effect.
 */
const val GlassEffectsEnabled = false

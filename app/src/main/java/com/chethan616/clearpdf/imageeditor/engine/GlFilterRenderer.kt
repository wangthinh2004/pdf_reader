package com.chethan616.clearpdf.imageeditor.engine

import android.graphics.Bitmap
import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

/**
 * Renders a GPUImage filter onto a bitmap off-screen, in pure Kotlin.
 *
 * This replaces `GPUImage.getBitmapWithFilterApplied`, whose read-back goes through GPUImage's
 * native `libyuv-decoder.so` (`GPUImageNativeLibrary.adjustBitmap`). That library is only built
 * for 4 KB pages, which Google Play rejects for 16 KB devices, so the app excludes it and does the
 * same steps here: an EGL10 pbuffer (RGBA8888, GLES 2) as PixelBuffer sets up, two draws (the first
 * runs the renderer's queued uploads), then `glReadPixels` and a vertical flip — GL rows run
 * bottom-up — which is all `adjustBitmap` did.
 *
 * The EGL context is made current on the calling thread and torn down before returning.
 */
internal object GlFilterRenderer {

    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private const val EGL_OPENGL_ES2_BIT = 4

    fun render(input: Bitmap, filter: GPUImageFilter): Bitmap {
        val w = input.width
        val h = input.height
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        check(egl.eglInitialize(display, IntArray(2))) { "eglInitialize failed" }
        var context = EGL10.EGL_NO_CONTEXT
        var surface = EGL10.EGL_NO_SURFACE
        try {
            val config = chooseConfig(egl, display)
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE))
            check(context != EGL10.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = egl.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL10.EGL_WIDTH, w, EGL10.EGL_HEIGHT, h, EGL10.EGL_NONE))
            check(surface != EGL10.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(egl.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            val gl = context.gl as GL10

            val renderer = GPUImageRenderer(filter)
            renderer.onSurfaceCreated(gl, config)
            renderer.onSurfaceChanged(gl, w, h)
            renderer.setImageBitmap(input, false)
            // First frame runs the queued texture upload; second draws with it.
            renderer.onDrawFrame(gl)
            renderer.onDrawFrame(gl)
            val out = readPixels(w, h)

            filter.destroy()
            renderer.deleteImage()
            // deleteImage is queued for the next frame, as in PixelBuffer.destroy().
            renderer.onDrawFrame(gl)
            return out
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface)
            if (context != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
        }
    }

    private fun chooseConfig(egl: EGL10, display: javax.microedition.khronos.egl.EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_NONE
        )
        val count = IntArray(1)
        egl.eglChooseConfig(display, attribs, null, 0, count)
        check(count[0] > 0) { "no EGL config" }
        val configs = arrayOfNulls<EGLConfig>(count[0])
        egl.eglChooseConfig(display, attribs, configs, count[0], count)
        return configs[0]!!
    }

    /** glReadPixels into a bitmap, flipping rows (GL's origin is bottom-left). */
    private fun readPixels(w: Int, h: Int): Bitmap {
        val rowBytes = w * 4
        val gl = ByteBuffer.allocateDirect(rowBytes * h).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, gl)
        val flipped = ByteBuffer.allocateDirect(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in h - 1 downTo 0) {
            gl.position(y * rowBytes)
            gl.get(row)
            flipped.put(row)
        }
        flipped.rewind()
        // ARGB_8888 stores bytes as R, G, B, A in memory — the same order GL_RGBA returns.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(flipped) }
    }
}

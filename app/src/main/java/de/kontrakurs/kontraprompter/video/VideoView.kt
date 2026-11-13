package de.kontrakurs.kontraprompter.video

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TextureView remains below Compose controls and can itself be independently transformed. */
class VideoView(context: Context, sharedContext: EglBase.Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("KontraPreview")
    @Volatile private var frameAspect = 0f
    private var fill = false
    private var released = false
    init {
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        isOpaque = false
        surfaceTextureListener = this
    }
    fun setFill(value: Boolean) { fill = value; updateTransform() }
    private fun updateTransform() {
        val aspect = frameAspect
        if (width == 0 || height == 0 || aspect <= 0f) return
        val viewAspect = width.toFloat() / height
        val sx = if (fill) maxOf(1f, aspect / viewAspect) else minOf(1f, aspect / viewAspect)
        val sy = if (fill) maxOf(1f, viewAspect / aspect) else minOf(1f, viewAspect / aspect)
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }
    override fun onFrame(frame: VideoFrame) {
        val aspect = frame.rotatedWidth.toFloat() / frame.rotatedHeight.coerceAtLeast(1)
        if (aspect != frameAspect) { frameAspect = aspect; renderer.setLayoutAspectRatio(aspect); post { updateTransform() } }
        renderer.onFrame(frame)
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (!released) renderer.createEglSurface(surface); updateTransform() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { updateTransform() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (!released) { val done = CountDownLatch(1); renderer.releaseEglSurface { done.countDown() }; done.await(2, TimeUnit.SECONDS) }
        return true
    }
    fun release() { if (!released) { released = true; surfaceTextureListener = null; renderer.release() } }
}

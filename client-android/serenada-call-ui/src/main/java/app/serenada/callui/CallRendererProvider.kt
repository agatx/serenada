package app.serenada.callui

import org.webrtc.SurfaceViewRenderer

interface CallRendererProvider {
    fun attachLocalRenderer(renderer: SurfaceViewRenderer)
    fun detachLocalRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer, cid: String)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer, cid: String)
}

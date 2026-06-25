package app.serenada.callui

import org.webrtc.SurfaceViewRenderer

interface CallRendererProvider {
    fun attachLocalRenderer(renderer: SurfaceViewRenderer)
    fun detachLocalRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer, cid: String)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer, cid: String)

    // Independent CONTENT (screen share) renderers. Default no-ops keep existing
    // providers source-compatible; the bundled flow supplies real wiring.
    fun attachRemoteContentRenderer(renderer: SurfaceViewRenderer, cid: String) {}
    fun detachRemoteContentRenderer(renderer: SurfaceViewRenderer, cid: String) {}
    fun attachLocalContentRenderer(renderer: SurfaceViewRenderer) {}
    fun detachLocalContentRenderer(renderer: SurfaceViewRenderer) {}
}

import Foundation

@MainActor
protocol CallRendererProvider: AnyObject {
    func attachLocalRenderer(_ renderer: AnyObject)
    func detachLocalRenderer(_ renderer: AnyObject)
    func attachRemoteRenderer(_ renderer: AnyObject)
    func detachRemoteRenderer(_ renderer: AnyObject)
    func attachRemoteRenderer(_ renderer: AnyObject, forCid cid: String)
    func detachRemoteRenderer(_ renderer: AnyObject, forCid cid: String)

    // Independent CONTENT (screen share) renderers. The camera renderers above
    // stay on the camera track; these target the separate content track exposed
    // by the SDK in independent mode. They are no-ops when the SDK is flag-off
    // (no content track exists) so attaching them is always safe.
    func attachLocalContentRenderer(_ renderer: AnyObject)
    func detachLocalContentRenderer(_ renderer: AnyObject)
    func attachRemoteContentRenderer(_ renderer: AnyObject, forCid cid: String)
    func detachRemoteContentRenderer(_ renderer: AnyObject, forCid cid: String)
}

import Foundation

@MainActor
protocol CallRendererProvider: AnyObject {
    func attachLocalRenderer(_ renderer: AnyObject)
    func detachLocalRenderer(_ renderer: AnyObject)
    func attachRemoteRenderer(_ renderer: AnyObject)
    func detachRemoteRenderer(_ renderer: AnyObject)
    func attachRemoteRenderer(_ renderer: AnyObject, forCid cid: String)
    func detachRemoteRenderer(_ renderer: AnyObject, forCid cid: String)
}

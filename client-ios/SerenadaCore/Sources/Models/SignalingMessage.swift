import Foundation

public struct SignalingMessage: Codable, Equatable {
    public var v: Int = 1
    public let type: String
    public let rid: String?
    public let sid: String?
    public let cid: String?
    public let to: String?
    public let payload: JSONValue?

    public init(
        type: String,
        rid: String? = nil,
        sid: String? = nil,
        cid: String? = nil,
        to: String? = nil,
        payload: JSONValue? = nil
    ) {
        self.type = type
        self.rid = rid
        self.sid = sid
        self.cid = cid
        self.to = to
        self.payload = payload
    }

    public static func decode(from raw: String) throws -> SignalingMessage {
        let data = Data(raw.utf8)
        return try JSONDecoder().decode(SignalingMessage.self, from: data)
    }

    public func toJSONString() throws -> String {
        let encoder = JSONEncoder()
        let data = try encoder.encode(self)
        guard let json = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "SignalingMessage", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to encode JSON"])
        }
        return json
    }
}

import CryptoKit
import Foundation

final class SessionStore {
    static let defaultServerUrl = "http://127.0.0.1:8080"
    static let serverUrlKey = "server_url"
    static let tokenKey = "token"
    static let verifiedSessionKey = "verified_session"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var serverUrl: String {
        defaults.string(forKey: Self.serverUrlKey) ?? Self.defaultServerUrl
    }

    var token: String? {
        defaults.string(forKey: Self.tokenKey)
    }

    func matches(server: String, token: String) -> Bool {
        server == serverUrl && token == self.token
    }

    func verified(server: String, token: String) -> Session? {
        guard let cached = defaults.string(forKey: Self.verifiedSessionKey),
              !TextMetrics.trimSpace(token).isEmpty,
              matches(server: server, token: token),
              let data = cached.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        guard let savedServer = raw["server"] as? String,
              let savedCredential = raw["credential"] as? String,
              savedServer == server,
              savedCredential == Self.fingerprint(token) else {
            return nil
        }

        guard let student = Self.readStudent(from: raw) else {
            return nil
        }
        return Session(serverUrl: server, token: token, student: student)
    }

    func save(_ session: Session) {
        let entry: [String: Any] = [
            "server": session.serverUrl,
            "credential": Self.fingerprint(session.token),
            "id": session.student.id,
            "username": session.student.username
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: entry),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        defaults.set(session.token, forKey: Self.tokenKey)
        defaults.set(json, forKey: Self.verifiedSessionKey)
    }

    func clear() {
        defaults.removeObject(forKey: Self.tokenKey)
        defaults.removeObject(forKey: Self.verifiedSessionKey)
    }

    func changeServer(_ server: String) {
        defaults.set(server, forKey: Self.serverUrlKey)
        defaults.removeObject(forKey: Self.tokenKey)
        defaults.removeObject(forKey: Self.verifiedSessionKey)
    }

    static func fingerprint(_ token: String) -> String {
        let digest = SHA256.hash(data: Data(token.utf8))
        return Data(digest).base64EncodedString()
    }

    private static func readStudent(from raw: [String: Any]) -> Student? {
        guard let num = raw["id"] as? NSNumber,
              CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        let id = num.int64Value
        guard id > 0, Double(id) == num.doubleValue else {
            return nil
        }
        guard let username = raw["username"] as? String,
              !TextMetrics.trimSpace(username).isEmpty else {
            return nil
        }
        return Student(id: id, username: username)
    }
}

import Foundation

struct Student: Hashable, Sendable, Codable {
    let id: Int64
    let username: String
}

struct ArticleOwner: Hashable, Sendable {
    let serverUrl: String
    let studentId: Int64
}

struct Session: Hashable, Sendable {
    let serverUrl: String
    let token: String
    let student: Student
    var owner: ArticleOwner { ArticleOwner(serverUrl: serverUrl, studentId: student.id) }
}

struct HealthInfo: Hashable, Sendable {
    let status: String
    let mode: String?
    let version: String?
}

/// → §1.3。connectionFailure = 传输层失败（URLError 列表见 §1.1）；timeout = URLError.timedOut 或 HTTP 504 MODEL_TIMEOUT。
enum ApiError: Error, Equatable {
    case http(status: Int, code: String?, message: String)
    case connectionFailure
    case timeout
    case malformed
    case cancelled

    var isUnauthorized: Bool {
        if case .http(status: 401, code: _, message: _) = self { return true }
        return false
    }

    var isDemoUnsupported: Bool {
        if case .http(status: _, code: "DEMO_UNSUPPORTED", message: _) = self { return true }
        return false
    }
}

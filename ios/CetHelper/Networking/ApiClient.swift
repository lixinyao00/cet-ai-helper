import Foundation

enum BaseUrlError: Error, LocalizedError, Equatable {
    case invalid

    var errorDescription: String? {
        "服务端地址格式不正确，请输入合法的 http:// 或 https:// 地址。"
    }
}

private final class RedirectBlockingDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

final class ApiClient {
    let session: URLSession
    let streamSession: URLSession
    private let delegate = RedirectBlockingDelegate()

    init(configuration: URLSessionConfiguration = .ephemeral) {
        let config = (configuration.copy() as? URLSessionConfiguration) ?? .ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)

        let streamConfig = (configuration.copy() as? URLSessionConfiguration) ?? .ephemeral
        streamConfig.timeoutIntervalForRequest = 60
        streamConfig.timeoutIntervalForResource = 120
        self.streamSession = URLSession(configuration: streamConfig, delegate: delegate, delegateQueue: nil)
    }

    deinit {
        session.invalidateAndCancel()
        streamSession.invalidateAndCancel()
    }

    // MARK: - URL Normalization (§1.1)

    static func normalizeBaseUrl(_ raw: String) throws -> String {
        let trimmed = TextMetrics.trimSpace(raw)
        guard !trimmed.isEmpty else {
            throw BaseUrlError.invalid
        }
        if trimmed.unicodeScalars.contains(where: TextMetrics.isSpace) {
            throw BaseUrlError.invalid
        }
        guard !trimmed.contains("?"), !trimmed.contains("#") else {
            throw BaseUrlError.invalid
        }

        // Explicitly inspect authority for userinfo or invalid port (e.g., :0 or :65536).
        guard let schemeRange = trimmed.range(of: "://") else {
            throw BaseUrlError.invalid
        }
        let rawScheme = String(trimmed[..<schemeRange.lowerBound]).lowercased()
        guard rawScheme == "http" || rawScheme == "https" else {
            throw BaseUrlError.invalid
        }

        let afterScheme = String(trimmed[schemeRange.upperBound...])
        let authority: String
        if let slashIdx = afterScheme.firstIndex(of: "/") {
            authority = String(afterScheme[..<slashIdx])
        } else {
            authority = afterScheme
        }
        guard !authority.isEmpty, !authority.contains("@") else {
            throw BaseUrlError.invalid
        }

        // Check port syntax on authority (handling IPv6 brackets if present).
        if authority.hasPrefix("[") {
            guard let closeBracket = authority.firstIndex(of: "]") else {
                throw BaseUrlError.invalid
            }
            let afterBracket = authority.index(after: closeBracket)
            if afterBracket < authority.endIndex {
                guard authority[afterBracket] == ":" else { throw BaseUrlError.invalid }
                let portStr = String(authority[authority.index(after: afterBracket)...])
                guard let portNum = Int(portStr), (1...65535).contains(portNum), portStr.allSatisfy(\.isNumber) else {
                    throw BaseUrlError.invalid
                }
            }
        } else if let colonIdx = authority.lastIndex(of: ":") {
            let portStr = String(authority[authority.index(after: colonIdx)...])
            guard let portNum = Int(portStr), (1...65535).contains(portNum), portStr.allSatisfy(\.isNumber) else {
                throw BaseUrlError.invalid
            }
        }

        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.percentEncodedUser == nil,
              components.percentEncodedPassword == nil,
              components.query == nil,
              components.percentEncodedQuery == nil,
              components.fragment == nil,
              components.percentEncodedFragment == nil else {
            throw BaseUrlError.invalid
        }

        var port = components.port
        if let p = port {
            guard (1...65535).contains(p) else { throw BaseUrlError.invalid }
            if (scheme == "http" && p == 80) || (scheme == "https" && p == 443) {
                port = nil
            }
        }

        var path = components.percentEncodedPath
        if !path.isEmpty, let url = components.url {
            let standardized = url.standardized.path
            if standardized == "/" {
                path = ""
            } else {
                path = standardized
            }
        }
        while path.hasSuffix("/") {
            path.removeLast()
        }

        let formattedHost = host.contains(":") && !host.hasPrefix("[") ? "[\(host.lowercased())]" : host.lowercased()
        let portPart = port.map { ":\($0)" } ?? ""
        return "\(scheme)://\(formattedHost)\(portPart)\(path)"
    }

    // MARK: - HTTP Transport

    func request(
        baseUrl: String,
        method: String,
        path: String,
        token: String?,
        payload: [String: Any]?,
        timeoutInterval: TimeInterval = 30
    ) async throws -> ([String: Any], String) {
        guard let url = URL(string: "\(baseUrl)/api/v1\(path)") else {
            throw ApiError.connectionFailure
        }
        let (data, status) = try await requestRawData(
            url: url,
            method: method,
            token: token,
            payload: payload,
            timeoutInterval: timeoutInterval
        )
        if status == 204 {
            return ([:], "{}")
        }
        guard let rawJson = String(data: data, encoding: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ApiError.malformed
        }
        return (dict, rawJson)
    }

    func requestArray(
        url: URL,
        method: String,
        token: String?
    ) async throws -> [[String: Any]] {
        let (data, status) = try await requestRawData(url: url, method: method, token: token, payload: nil)
        if status == 204 || data.isEmpty {
            return []
        }
        guard let rawList = try? JSONSerialization.jsonObject(with: data) as? [Any] else {
            throw ApiError.malformed
        }
        var dicts: [[String: Any]] = []
        dicts.reserveCapacity(rawList.count)
        for item in rawList {
            guard let dict = item as? [String: Any] else {
                throw ApiError.malformed
            }
            dicts.append(dict)
        }
        return dicts
    }

    func requestRawData(
        url: URL,
        method: String,
        token: String?,
        payload: [String: Any]?,
        timeoutInterval: TimeInterval = 30
    ) async throws -> (Data, Int) {
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        urlRequest.timeoutInterval = timeoutInterval
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let payload {
            urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [])
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: urlRequest)
        } catch is CancellationError {
            throw ApiError.cancelled
        } catch let error as URLError {
            throw Self.mapURLError(error)
        } catch {
            throw ApiError.connectionFailure
        }

        guard let http = response as? HTTPURLResponse else {
            throw ApiError.connectionFailure
        }

        let status = http.statusCode
        if status == 204 {
            return (Data(), 204)
        }
        if status == 504 {
            throw ApiError.timeout
        }
        if status < 200 || status >= 300 {
            throw Self.buildHttpError(status: status, data: data)
        }

        return (data, status)
    }

    static func buildHttpError(status: Int, data: Data) -> ApiError {
        if status == 504 {
            return .timeout
        }
        var errorCode: String? = nil
        var errorMessage = "服务请求失败（HTTP \(status)）。"
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let code = obj["code"] as? String, !TextMetrics.trimSpace(code).isEmpty {
                errorCode = code
            }
            if let message = obj["message"] as? String, !TextMetrics.trimSpace(message).isEmpty {
                errorMessage = message
            }
        }
        return .http(status: status, code: errorCode, message: errorMessage)
    }


    static func mapURLError(_ error: URLError) -> ApiError {
        switch error.code {
        case .cancelled:
            return .cancelled
        case .timedOut:
            return .timeout
        case .notConnectedToInternet,
             .cannotFindHost,
             .cannotConnectToHost,
             .networkConnectionLost,
             .dnsLookupFailed,
             .internationalRoamingOff,
             .dataNotAllowed:
            return .connectionFailure
        default:
            return .connectionFailure
        }
    }

    // MARK: - JSON Helpers

    static func writingContextPayload(issue: ReviewIssue, exercise: WritingExercise) -> [String: Any] {
        [
            "issue": [
                "type": issue.type.rawValue,
                "quote": issue.quote,
                "detail": issue.detail,
                "suggestion": issue.suggestion
            ],
            "exercise": [
                "instruction": exercise.instruction,
                "item": exercise.item
            ]
        ]
    }

    static func requireOriginalIssue(_ body: [String: Any], matches issue: ReviewIssue) throws {
        guard let original = body["issue"] as? [String: Any] else {
            throw ApiError.malformed
        }
        let type = try requiredText(original, "type")
        let quote = try requiredText(original, "quote")
        let detail = try requiredText(original, "detail")
        let suggestion = try requiredText(original, "suggestion")
        guard type == issue.type.rawValue,
              quote == issue.quote,
              detail == issue.detail,
              suggestion == issue.suggestion else {
            throw ApiError.malformed
        }
    }

    static func readStudent(_ body: [String: Any]) throws -> Student {
        guard let id = parseExactInt64(body["id"]), id > 0 else {
            throw ApiError.malformed
        }
        let username = try requiredText(body, "username")
        return Student(id: id, username: username)
    }

    static func requireMode(_ body: [String: Any]) throws -> String {
        let mode = try requiredText(body, "mode")
        guard mode == "demo" || mode == "live" else {
            throw ApiError.malformed
        }
        return mode
    }

    static func requiredText(_ dict: [String: Any], _ key: String) throws -> String {
        guard let text = dict[key] as? String, !TextMetrics.trimSpace(text).isEmpty else {
            throw ApiError.malformed
        }
        return text
    }

    static func optClean(_ dict: [String: Any], _ key: String) -> String {
        guard let text = dict[key] as? String else { return "" }
        return TextMetrics.trimSpace(text)
    }

    static func parseOptionalScore(_ dict: [String: Any], _ key: String) -> Int {
        guard let value = parseOptionalInt(dict, key), value >= 0 else {
            return -1
        }
        return value
    }

    static func parseOptionalInt(_ dict: [String: Any], _ key: String) -> Int? {
        guard let raw = dict[key], !(raw is NSNull) else { return nil }
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        return num.intValue
    }

    static func parseExactInt(_ raw: Any?) -> Int? {
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        let intVal = num.intValue
        guard Double(intVal) == num.doubleValue else { return nil }
        return intVal
    }

    static func parseExactInt64(_ raw: Any?) -> Int64? {
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        let intVal = num.int64Value
        guard Double(intVal) == num.doubleValue else { return nil }
        return intVal
    }
}

// MARK: - Scene-Aware Error Formatting (§1.3)

enum ApiErrorFormatter {
    static let connectionError = "无法连接服务，请检查网络和服务端地址。"
    static let invalidResponse = "服务返回了无法读取的数据。"
    static let signInAgain = "会话已失效，请重新登录。"

    static func defaultFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            switch apiError {
            case .http(_, let code, let message):
                let isUnsupported = (code == "DEMO_UNSUPPORTED")
                return (message, !isUnsupported)
            case .connectionFailure:
                return (connectionError, true)
            case .timeout:
                return ("请求超时，请重试。", true)
            case .malformed:
                return (invalidResponse, true)
            case .cancelled:
                return ("", false)
            }
        }
        return (connectionError, true)
    }

    static func generalMessage(_ error: Error) -> String {
        defaultFormat(error).message
    }

    static func lookupFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError, apiError.isDemoUnsupported {
            return ("演示模式暂未收录该选词。", false)
        }
        return defaultFormat(error)
    }

    static func sentenceAnalysisFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            if apiError.isDemoUnsupported {
                return ("演示模式暂未收录该句子。", false)
            }
            if apiError == .timeout {
                return ("整句分析超时，请重试。", true)
            }
        }
        return defaultFormat(error)
    }

    static func translationHintFormat(_ error: Error, typeTitle: String) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            if apiError.isDemoUnsupported {
                return ("演示模式只收录内置的中文材料。可以返回原文页使用演示材料，或连接 live 服务练习自选材料。", false)
            }
            if apiError == .timeout {
                return ("获取提示超时。\n再次点击「\(typeTitle)」重试。", true)
            }
            let base = defaultFormat(error).message
            return ("\(base)\n再次点击「\(typeTitle)」重试。", true)
        }
        return ("\(connectionError)\n再次点击「\(typeTitle)」重试。", true)
    }

    static func translationReviewFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            if apiError.isDemoUnsupported {
                return ("演示模式只批改内置原文与预设译文的逐字匹配组合，你的原文和草稿已保留。可用服务端文档中的样例联调，或连接 live 服务批改自己的译文。", false)
            }
            if apiError == .timeout {
                return ("批改超时，请重新提交。", true)
            }
        }
        return defaultFormat(error)
    }

    static func writingReviewFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            if apiError.isDemoUnsupported {
                return ("演示模式仅支持校园志愿服务题的内置草稿和修订稿。自选题目或作文可连接 live 服务批改。题目和作文已保留。", false)
            }
            if apiError == .timeout {
                return ("写作批改超时，请重试。", true)
            }
        }
        return defaultFormat(error)
    }

    static func writingFollowUpFormat(_ error: Error) -> (message: String, canRetry: Bool) {
        if let apiError = error as? ApiError {
            if case .http(_, "DEMO_UNSUPPORTED", let serverMessage) = apiError {
                return ("\(serverMessage)\n本轮题面和答案已保留。", false)
            }
            if apiError == .timeout {
                return ("请求超时，请重试。", true)
            }
        }
        return defaultFormat(error)
    }
}

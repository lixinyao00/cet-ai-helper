import Foundation

extension ApiClient {
    // MARK: - 13 REST Endpoints (§1.2)

    func health(baseUrl: String) async throws -> HealthInfo {
        let (body, _) = try await request(baseUrl: baseUrl, method: "GET", path: "/health", token: nil, payload: nil)
        let rawStatus = Self.optClean(body, "status")
        let status = rawStatus.isEmpty ? "UP" : rawStatus
        let mode = Self.optClean(body, "mode")
        let version = Self.optClean(body, "version")
        return HealthInfo(
            status: status,
            mode: mode.isEmpty ? nil : mode,
            version: version.isEmpty ? nil : version
        )
    }

    func register(baseUrl: String, username: String, password: String) async throws {
        _ = try await request(
            baseUrl: baseUrl,
            method: "POST",
            path: "/auth/register",
            token: nil,
            payload: ["username": username, "password": password]
        )
    }

    func login(baseUrl: String, username: String, password: String) async throws -> String {
        let (body, _) = try await request(
            baseUrl: baseUrl,
            method: "POST",
            path: "/auth/login",
            token: nil,
            payload: ["username": username, "password": password]
        )
        return try Self.requiredText(body, "accessToken")
    }

    func me(baseUrl: String, token: String) async throws -> Student {
        let (body, _) = try await request(baseUrl: baseUrl, method: "GET", path: "/me", token: token, payload: nil)
        return try Self.readStudent(body)
    }

    func logout(baseUrl: String, token: String) async throws {
        _ = try await request(baseUrl: baseUrl, method: "POST", path: "/auth/logout", token: token, payload: nil)
    }

    // MARK: - Dictionary & AI Tutor SSE Endpoints (v4)

    func dictionarySuggest(
        _ session: Session,
        query: String,
        level: String? = nil,
        limit: Int = 10
    ) async throws -> [DictionarySuggestion] {
        guard var components = URLComponents(string: "\(session.serverUrl)/api/v1/dictionary/suggest") else {
            throw ApiError.connectionFailure
        }
        var items: [URLQueryItem] = [URLQueryItem(name: "q", value: query)]
        if let level {
            let trimmedLevel = TextMetrics.trimSpace(level)
            if !trimmedLevel.isEmpty {
                items.append(URLQueryItem(name: "level", value: trimmedLevel))
            }
        }
        items.append(URLQueryItem(name: "limit", value: String(limit)))
        components.queryItems = items
        guard let url = components.url else {
            throw ApiError.connectionFailure
        }

        let rawArray = try await requestArray(url: url, method: "GET", token: session.token)
        var suggestions: [DictionarySuggestion] = []
        suggestions.reserveCapacity(rawArray.count)
        for item in rawArray {
            let word = try Self.requiredText(item, "word")
            suggestions.append(
                DictionarySuggestion(
                    word: word,
                    phonetic: Self.optClean(item, "phonetic"),
                    partOfSpeech: Self.optClean(item, "partOfSpeech"),
                    cetLevel: Self.optClean(item, "cetLevel"),
                    meaning: Self.optClean(item, "meaning")
                )
            )
        }
        return suggestions
    }

    func dictionaryLookup(
        _ session: Session,
        word: String
    ) async throws -> DictionaryEntry {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/dictionary/lookup",
            token: session.token,
            payload: ["word": word]
        )
        let resolvedWord = try Self.requiredText(body, "word")
        let meaning = try Self.requiredText(body, "meaning")
        let rawLemma = Self.optClean(body, "lemma")
        let rawMode = Self.optClean(body, "mode")
        return DictionaryEntry(
            word: resolvedWord,
            lemma: rawLemma.isEmpty ? resolvedWord : rawLemma,
            phonetic: Self.optClean(body, "phonetic"),
            partOfSpeech: Self.optClean(body, "partOfSpeech"),
            cetLevel: Self.optClean(body, "cetLevel"),
            meaning: meaning,
            explanation: Self.optClean(body, "explanation"),
            exampleSentence: Self.optClean(body, "exampleSentence"),
            exampleTranslation: Self.optClean(body, "exampleTranslation"),
            mode: rawMode.isEmpty ? "demo" : rawMode
        )
    }

    func organizeArticle(
        _ session: Session,
        rawText: String?,
        imageBase64: String?,
        imageMimeType: String?,
        target: PageImportTarget
    ) async throws -> OrganizedArticle {
        var payload: [String: Any] = [:]
        if let rawText, !TextMetrics.trimSpace(rawText).isEmpty {
            payload["rawText"] = rawText
        }
        if let imageBase64, !imageBase64.isEmpty {
            payload["imageBase64"] = imageBase64
            if let imageMimeType {
                let trimmedMime = TextMetrics.trimSpace(imageMimeType)
                if !trimmedMime.isEmpty {
                    payload["imageMimeType"] = trimmedMime
                }
            }
        }
        payload["target"] = target.rawValue

        let (res, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/articles/organize",
            token: session.token,
            payload: payload,
            timeoutInterval: 45
        )
        let body = try Self.requiredText(res, "body")
        return OrganizedArticle(
            title: Self.optClean(res, "title"),
            body: body,
            summary: Self.optClean(res, "summary"),
            mode: Self.optClean(res, "mode")
        )
    }

    /// 独立流式 URLSession（request 60s / resource 120s），按字节迭代 AsyncBytes（严禁使用 AsyncBytes.lines）。
    /// 取消后严禁再触发任何 onEvent / onError 回调。
}

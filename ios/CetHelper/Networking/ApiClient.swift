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
    private let session: URLSession
    private let streamSession: URLSession
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
    func streamTutorChat(
        _ session: Session,
        messages: [TutorMessage],
        context: TutorContext,
        onEvent: @escaping @MainActor (TutorSseEvent) -> Void,
        onError: @escaping @MainActor (Error) -> Void
    ) -> TutorStreamHandle {
        let handle = TutorStreamHandle()
        let recentMessages = Array(messages.suffix(20)).map { msg -> [String: String] in
            ["role": msg.role.rawValue, "content": msg.content]
        }
        let payload: [String: Any] = [
            "messages": recentMessages,
            "context": context.toPayloadDictionary()
        ]
        let serverUrl = session.serverUrl
        let token = session.token
        let streamURLSession = self.streamSession

        let task = Task {
            if handle.isCancelled { return }
            guard let url = URL(string: "\(serverUrl)/api/v1/tutor/chat/stream") else {
                if !handle.isCancelled {
                    await onError(ApiError.connectionFailure)
                }
                return
            }

            let bodyData: Data
            do {
                bodyData = try JSONSerialization.data(withJSONObject: payload, options: [])
            } catch {
                if !handle.isCancelled {
                    await onError(ApiError.malformed)
                }
                return
            }

            var urlRequest = URLRequest(url: url)
            urlRequest.httpMethod = "POST"
            urlRequest.timeoutInterval = 60
            urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            urlRequest.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            urlRequest.httpBody = bodyData

            do {
                let (asyncBytes, response) = try await streamURLSession.bytes(for: urlRequest)
                if handle.isCancelled { return }

                guard let http = response as? HTTPURLResponse else {
                    if !handle.isCancelled {
                        await onError(ApiError.connectionFailure)
                    }
                    return
                }

                let status = http.statusCode
                if status < 200 || status >= 300 {
                    var errorData = Data()
                    for try await byte in asyncBytes {
                        if handle.isCancelled { return }
                        errorData.append(byte)
                    }
                    if handle.isCancelled { return }
                    let apiErr = Self.buildHttpError(status: status, data: errorData)
                    await onError(apiErr)
                    return
                }

                var parser = SseLineParser()
                var lineBytes: [UInt8] = []
                lineBytes.reserveCapacity(256)

                for try await byte in asyncBytes {
                    if handle.isCancelled { return }
                    if byte == 0x0A {
                        if lineBytes.last == 0x0D {
                            lineBytes.removeLast()
                        }
                        let line = String(decoding: lineBytes, as: UTF8.self)
                        lineBytes.removeAll(keepingCapacity: true)

                        let outcome = parser.consumeLine(line)
                        if let event = outcome.event {
                            if !handle.isCancelled {
                                await onEvent(event)
                            }
                        } else if let error = outcome.error {
                            if !handle.isCancelled {
                                await onError(error)
                            }
                        }
                        if outcome.terminated || handle.isCancelled {
                            return
                        }
                    } else {
                        lineBytes.append(byte)
                    }
                }

                if handle.isCancelled { return }
                if !lineBytes.isEmpty {
                    if lineBytes.last == 0x0D {
                        lineBytes.removeLast()
                    }
                    let tailLine = String(decoding: lineBytes, as: UTF8.self)
                    let outcome = parser.consumeLine(tailLine)
                    if let event = outcome.event, !handle.isCancelled {
                        await onEvent(event)
                    } else if let error = outcome.error, !handle.isCancelled {
                        await onError(error)
                    }
                    if outcome.terminated || handle.isCancelled {
                        return
                    }
                }

                let eofOutcome = parser.finishAtEOF()
                if let event = eofOutcome.event, !handle.isCancelled {
                    await onEvent(event)
                }
                if let error = eofOutcome.error, !handle.isCancelled {
                    await onError(error)
                }
            } catch is CancellationError {
                return
            } catch let urlError as URLError {
                if handle.isCancelled || urlError.code == .cancelled {
                    return
                }
                await onError(Self.mapURLError(urlError))
            } catch let apiError as ApiError {
                if handle.isCancelled || apiError == .cancelled {
                    return
                }
                await onError(apiError)
            } catch {
                if handle.isCancelled { return }
                await onError(ApiError.connectionFailure)
            }
        }

        handle.attach(task: task)
        return handle
    }

    /// 供单元测试直接喂入原始 SSE 字节流的纯同步解析接缝。
    static func parseTutorSseStream(
        data: Data,
        isCancelled: () -> Bool = { false },
        onEvent: (TutorSseEvent) -> Void,
        onError: (Error) -> Void
    ) {
        var parser = SseLineParser()
        var lineBytes: [UInt8] = []
        lineBytes.reserveCapacity(256)

        for byte in data {
            if isCancelled() { return }
            if byte == 0x0A {
                if lineBytes.last == 0x0D {
                    lineBytes.removeLast()
                }
                let line = String(decoding: lineBytes, as: UTF8.self)
                lineBytes.removeAll(keepingCapacity: true)

                let outcome = parser.consumeLine(line)
                if isCancelled() { return }
                if let event = outcome.event {
                    onEvent(event)
                } else if let error = outcome.error {
                    onError(error)
                }
                if outcome.terminated || isCancelled() {
                    return
                }
            } else {
                lineBytes.append(byte)
            }
        }

        if isCancelled() { return }
        if !lineBytes.isEmpty {
            if lineBytes.last == 0x0D {
                lineBytes.removeLast()
            }
            let tailLine = String(decoding: lineBytes, as: UTF8.self)
            let outcome = parser.consumeLine(tailLine)
            if isCancelled() { return }
            if let event = outcome.event {
                onEvent(event)
            } else if let error = outcome.error {
                onError(error)
            }
            if outcome.terminated || isCancelled() {
                return
            }
        }

        let eofOutcome = parser.finishAtEOF()
        if isCancelled() { return }
        if let event = eofOutcome.event {
            onEvent(event)
        }
        if isCancelled() { return }
        if let error = eofOutcome.error {
            onError(error)
        }
    }

    func explain(_ session: Session, selection: String, sentence: String) async throws -> ExplanationResult {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/explanations",
            token: session.token,
            payload: ["selection": selection, "sentence": sentence]
        )
        guard let meaning = body["meaning"] as? String,
              let explanation = body["explanation"] as? String else {
            throw ApiError.malformed
        }
        var mode = Self.optClean(body, "mode")
        if mode.isEmpty {
            let demo = (body["demo"] as? Bool) ?? false
            mode = demo ? "demo" : "live"
        }
        let contextual: Bool
        if let rawContextual = body["contextual"] as? Bool {
            contextual = rawContextual
        } else {
            contextual = true
        }
        return ExplanationResult(
            selection: selection,
            sentence: sentence,
            meaning: meaning,
            explanation: explanation,
            mode: mode,
            phonetic: Self.optClean(body, "phonetic"),
            partOfSpeech: Self.optClean(body, "partOfSpeech"),
            lemma: Self.optClean(body, "lemma"),
            cetLevel: Self.optClean(body, "cetLevel"),
            contextual: contextual
        )
    }

    func analyzeSentence(_ session: Session, sentence: String) async throws -> SentenceAnalysis {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/sentence-analyses",
            token: session.token,
            payload: ["sentence": sentence]
        )
        let echoedSentence = try Self.requiredText(body, "sentence")
        let mode = try Self.requireMode(body)
        guard echoedSentence == sentence,
              let rawChunks = body["chunks"] as? [[String: Any]],
              !rawChunks.isEmpty,
              rawChunks.count <= 20 else {
            throw ApiError.malformed
        }

        let translation = try Self.requiredText(body, "translation")
        let structure = try Self.requiredText(body, "structure")

        var parsedChunks: [(text: String, role: String, explanation: String)] = []
        parsedChunks.reserveCapacity(rawChunks.count)
        for chunk in rawChunks {
            parsedChunks.append((
                text: try Self.requiredText(chunk, "text"),
                role: try Self.requiredText(chunk, "role"),
                explanation: try Self.requiredText(chunk, "explanation")
            ))
        }

        let located = try SentenceAnalysis.locate(sentence: echoedSentence, chunks: parsedChunks)
        return SentenceAnalysis(
            sentence: echoedSentence,
            translation: translation,
            structure: structure,
            mode: mode,
            chunks: located
        )
    }

    func translationHint(
        _ session: Session,
        sourceText: String,
        type: TranslationHintType
    ) async throws -> TranslationHint {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/translation-hints",
            token: session.token,
            payload: ["sourceText": sourceText, "hintType": type.rawValue]
        )
        let echoedSource = try Self.requiredText(body, "sourceText")
        let echoedType = try Self.requiredText(body, "hintType")
        let mode = try Self.requireMode(body)
        guard echoedSource == sourceText, echoedType == type.rawValue else {
            throw ApiError.malformed
        }

        var items: [TranslationHintItem] = []
        switch type {
        case .structure:
            guard let structure = body[type.responseField] as? [String: Any] else {
                throw ApiError.malformed
            }
            let mainClause = try Self.requiredText(structure, "mainClause")
            items.append(TranslationHintItem(id: 0, title: "主干", body: mainClause))

            guard let clauses = structure["clauses"] as? [[String: Any]],
                  let connectives = structure["connectives"] as? [[String: Any]] else {
                throw ApiError.malformed
            }

            for clause in clauses {
                let role = try Self.requiredText(clause, "role")
                let source = try Self.requiredText(clause, "source")
                let guidance = try Self.requiredText(clause, "guidance")
                items.append(
                    TranslationHintItem(id: items.count, title: "\(role) · \(source)", body: guidance)
                )
            }
            for connective in connectives {
                let source = try Self.requiredText(connective, "source")
                let suggestion = try Self.requiredText(connective, "suggestion")
                items.append(
                    TranslationHintItem(id: items.count, title: source, body: suggestion)
                )
            }
            guard items.count > 1 else {
                throw ApiError.malformed
            }

        case .keyword, .phrase:
            guard let entries = body[type.responseField] as? [[String: Any]], !entries.isEmpty else {
                throw ApiError.malformed
            }
            for entry in entries {
                let source = try Self.requiredText(entry, "source")
                let note = try Self.requiredText(entry, "note")
                guard let rawExpressions = entry["expressions"] as? [Any],
                      !rawExpressions.isEmpty,
                      rawExpressions.count <= 5 else {
                    throw ApiError.malformed
                }
                var expressions: [String] = []
                expressions.reserveCapacity(rawExpressions.count)
                for rawExpr in rawExpressions {
                    guard let expr = rawExpr as? String, !TextMetrics.trimSpace(expr).isEmpty else {
                        throw ApiError.malformed
                    }
                    expressions.append(expr)
                }
                let joined = expressions.joined(separator: " / ")
                items.append(
                    TranslationHintItem(id: items.count, title: source, body: "\(joined)\n\(note)")
                )
            }
        }

        return TranslationHint(type: type, sourceText: echoedSource, mode: mode, items: items)
    }

    func reviewTranslation(
        _ session: Session,
        sourceText: String,
        studentTranslation: String
    ) async throws -> TranslationFeedback {
        let (body, rawJson) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/translation-reviews",
            token: session.token,
            payload: ["sourceText": sourceText, "studentTranslation": studentTranslation]
        )
        let feedback = try Self.parseTranslationReview(body: body, rawJson: rawJson)
        guard feedback.sourceText == sourceText, feedback.studentTranslation == studentTranslation else {
            throw ApiError.malformed
        }
        return feedback
    }

    func reviewWriting(_ session: Session, prompt: String, essay: String) async throws -> WritingFeedback {
        let (body, rawJson) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-reviews",
            token: session.token,
            payload: ["prompt": prompt, "essay": essay]
        )
        let feedback = try Self.parseWritingReview(body: body, rawJson: rawJson)
        guard feedback.prompt == prompt, feedback.essay == essay else {
            throw ApiError.malformed
        }
        return feedback
    }

    func attemptWritingExercise(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise,
        answer: String
    ) async throws -> WritingAssessment {
        let payload: [String: Any] = [
            "context": Self.writingContextPayload(issue: issue, exercise: exercise),
            "answer": answer
        ]
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-exercise-attempts",
            token: session.token,
            payload: payload
        )
        return try Self.parseWritingAssessment(body: body, expectedIssue: issue, sentAnswer: answer, retest: false)
    }

    func requestWritingRetest(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise
    ) async throws -> WritingTask {
        let payload = Self.writingContextPayload(issue: issue, exercise: exercise)
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-retests",
            token: session.token,
            payload: payload
        )
        try Self.requireOriginalIssue(body, matches: issue)
        _ = try Self.requireMode(body)
        guard let task = body["task"] as? [String: Any] else {
            throw ApiError.malformed
        }
        return WritingTask(
            instruction: try Self.requiredText(task, "instruction"),
            item: try Self.requiredText(task, "item")
        )
    }

    func attemptWritingRetest(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise,
        task: WritingTask,
        answer: String
    ) async throws -> WritingAssessment {
        let payload: [String: Any] = [
            "context": Self.writingContextPayload(issue: issue, exercise: exercise),
            "task": ["instruction": task.instruction, "item": task.item],
            "answer": answer
        ]
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-retest-attempts",
            token: session.token,
            payload: payload
        )
        return try Self.parseWritingAssessment(body: body, expectedIssue: issue, sentAnswer: answer, retest: true)
    }

    // MARK: - Static Review Parsers (also used for PracticeRecord history)

    static func parseTranslationReview(json: String) throws -> TranslationFeedback {
        guard let data = json.data(using: .utf8),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ApiError.malformed
        }
        return try parseTranslationReview(body: body, rawJson: json)
    }

    static func parseWritingReview(json: String) throws -> WritingFeedback {
        guard let data = json.data(using: .utf8),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ApiError.malformed
        }
        return try parseWritingReview(body: body, rawJson: json)
    }

    private static func parseTranslationReview(body: [String: Any], rawJson: String) throws -> TranslationFeedback {
        let source = try requiredText(body, "sourceText")
        let draft = try requiredText(body, "studentTranslation")
        let mode = try requireMode(body)
        let overallFeedback = try requiredText(body, "overallFeedback")
        guard let rawIssues = body["issues"] as? [[String: Any]] else {
            throw ApiError.malformed
        }

        var issues: [ReviewIssue] = []
        issues.reserveCapacity(rawIssues.count)
        for (index, item) in rawIssues.enumerated() {
            let rawType = try requiredText(item, "type")
            guard let type = ReviewIssueType(rawValue: rawType),
                  type == .omission || type == .unnatural else {
                throw ApiError.malformed
            }
            let quote = try requiredText(item, "quote")
            let detail = try requiredText(item, "detail")
            let suggestion = try requiredText(item, "suggestion")
            let target: QuoteTarget = type == .omission ? .source : .draft
            let snapshot = target == .source ? source : draft
            let matches = ReviewQuote.findMatches(in: snapshot, quote: quote)
            guard !matches.isEmpty else {
                throw ApiError.malformed
            }
            issues.append(
                ReviewIssue(
                    index: index,
                    type: type,
                    quote: quote,
                    detail: detail,
                    suggestion: suggestion,
                    target: target,
                    matches: matches
                )
            )
        }

        let estimatedScore = parseOptionalScore(body, "estimatedScore")
        let scoreMax = parseOptionalInt(body, "scoreMax") ?? 15
        let scoreBandRaw = optClean(body, "scoreBand")
        let refRaw = optClean(body, "referenceTranslation")

        return TranslationFeedback(
            sourceText: source,
            studentTranslation: draft,
            mode: mode,
            overallFeedback: overallFeedback,
            issues: issues,
            estimatedScore: estimatedScore,
            scoreMax: scoreMax,
            scoreBand: scoreBandRaw.isEmpty ? nil : scoreBandRaw,
            referenceTranslation: refRaw.isEmpty ? nil : refRaw,
            rawJson: rawJson
        )
    }

    private static func parseWritingReview(body: [String: Any], rawJson: String) throws -> WritingFeedback {
        let prompt = try requiredText(body, "prompt")
        let essay = try requiredText(body, "essay")
        let mode = try requireMode(body)
        let overallFeedback = try requiredText(body, "overallFeedback")
        guard let rawIssues = body["issues"] as? [[String: Any]], rawIssues.count <= 3 else {
            throw ApiError.malformed
        }

        var issues: [ReviewIssue] = []
        issues.reserveCapacity(rawIssues.count)
        for (index, item) in rawIssues.enumerated() {
            let rawType = try requiredText(item, "type")
            guard let type = ReviewIssueType(rawValue: rawType),
                  type == .grammar || type == .wordChoice || type == .mechanics || type == .clarity else {
                throw ApiError.malformed
            }
            let quote = try requiredText(item, "quote")
            let detail = try requiredText(item, "detail")
            let suggestion = try requiredText(item, "suggestion")
            let matches = ReviewQuote.findMatches(in: essay, quote: quote)
            guard quote.utf16.count <= 240, !matches.isEmpty else {
                throw ApiError.malformed
            }
            issues.append(
                ReviewIssue(
                    index: index,
                    type: type,
                    quote: quote,
                    detail: detail,
                    suggestion: suggestion,
                    target: .draft,
                    matches: matches
                )
            )
        }

        var exercise: WritingExercise? = nil
        if let rawExercise = body["exercise"], !(rawExercise is NSNull) {
            guard let exDict = rawExercise as? [String: Any],
                  let issueIndex = parseExactInt(exDict["issueIndex"]),
                  issueIndex >= 0, issueIndex < issues.count else {
                throw ApiError.malformed
            }
            exercise = WritingExercise(
                issueIndex: issueIndex,
                instruction: try requiredText(exDict, "instruction"),
                item: try requiredText(exDict, "item")
            )
        }

        let estimatedScore = parseOptionalScore(body, "estimatedScore")
        let scoreMax = parseOptionalInt(body, "scoreMax") ?? 15
        let scoreBandRaw = optClean(body, "scoreBand")

        return WritingFeedback(
            prompt: prompt,
            essay: essay,
            mode: mode,
            overallFeedback: overallFeedback,
            issues: issues,
            exercise: exercise,
            estimatedScore: estimatedScore,
            scoreMax: scoreMax,
            scoreBand: scoreBandRaw.isEmpty ? nil : scoreBandRaw,
            rawJson: rawJson
        )
    }

    private static func parseWritingAssessment(
        body: [String: Any],
        expectedIssue: ReviewIssue,
        sentAnswer: String,
        retest: Bool
    ) throws -> WritingAssessment {
        try requireOriginalIssue(body, matches: expectedIssue)
        _ = try requireMode(body)
        let answer = try requiredText(body, "answer")
        guard answer == sentAnswer else {
            throw ApiError.malformed
        }
        let rawVerdict = try requiredText(body, "verdict")
        guard let verdict = WritingVerdict(rawValue: rawVerdict),
              verdict.isRetest == retest else {
            throw ApiError.malformed
        }
        let feedback = try requiredText(body, "feedback")
        let nextStep = try requiredText(body, "nextStep")

        var quote: String? = nil
        var matches: [TextRange] = []
        if verdict == .targetUsed || verdict == .sameError {
            let q = try requiredText(body, "quote")
            let found = ReviewQuote.findMatches(in: answer, quote: q)
            guard q.utf16.count <= 240, !found.isEmpty else {
                throw ApiError.malformed
            }
            quote = q
            matches = found
        }

        return WritingAssessment(
            verdict: verdict,
            answer: answer,
            feedback: feedback,
            nextStep: nextStep,
            quote: quote,
            matches: matches
        )
    }

    // MARK: - HTTP Transport

    private func request(
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

    private func requestArray(
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

    private func requestRawData(
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

    private static func buildHttpError(status: Int, data: Data) -> ApiError {
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

    // MARK: - SSE Line State Machine

    private struct SseStepOutcome {
        let event: TutorSseEvent?
        let error: Error?
        let terminated: Bool

        static let none = SseStepOutcome(event: nil, error: nil, terminated: false)
    }

    private struct SseLineParser {
        private var currentEvent: String? = nil
        private var dataBuffer: String = ""
        private var terminated: Bool = false

        mutating func consumeLine(_ line: String) -> SseStepOutcome {
            if terminated {
                return SseStepOutcome(event: nil, error: nil, terminated: true)
            }
            if line.isEmpty {
                if currentEvent != nil || !dataBuffer.isEmpty {
                    let ev = currentEvent
                    let payload = dataBuffer
                    currentEvent = nil
                    dataBuffer = ""
                    let outcome = dispatch(eventName: ev, data: payload)
                    if outcome.terminated {
                        terminated = true
                    }
                    return outcome
                }
                return .none
            }
            if line.hasPrefix(":") {
                return .none
            }
            if line.hasPrefix("event:") {
                currentEvent = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("data:") {
                let afterColon = line.dropFirst(5)
                let value: Substring
                if afterColon.first == " " {
                    value = afterColon.dropFirst()
                } else {
                    value = afterColon
                }
                if !dataBuffer.isEmpty {
                    dataBuffer.append("\n")
                }
                dataBuffer.append(contentsOf: value)
            }
            return .none
        }

        mutating func finishAtEOF() -> SseStepOutcome {
            if terminated {
                return SseStepOutcome(event: nil, error: nil, terminated: true)
            }
            if currentEvent != nil || !dataBuffer.isEmpty {
                let ev = currentEvent
                let payload = dataBuffer
                currentEvent = nil
                dataBuffer = ""
                let outcome = dispatch(eventName: ev, data: payload)
                if outcome.terminated {
                    terminated = true
                    return outcome
                }
                // Trailing non-terminal event without done/error: emit event then report premature EOF.
                terminated = true
                return SseStepOutcome(event: outcome.event, error: ApiError.connectionFailure, terminated: true)
            }
            terminated = true
            return SseStepOutcome(event: nil, error: ApiError.connectionFailure, terminated: true)
        }

        private func dispatch(eventName: String?, data: String) -> SseStepOutcome {
            let ev = (eventName ?? "").trimmingCharacters(in: .whitespaces)
            switch ev {
            case "meta":
                guard let obj = Self.parseJsonObject(data) else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let mode = ApiClient.optClean(obj, "mode")
                let contextType = ApiClient.optClean(obj, "contextType")
                return SseStepOutcome(
                    event: .meta(mode: mode, contextType: contextType),
                    error: nil,
                    terminated: false
                )

            case "delta":
                guard let obj = Self.parseJsonObject(data) else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let text = (obj["text"] as? String) ?? ""
                if text.isEmpty {
                    return .none
                }
                return SseStepOutcome(event: .delta(text: text), error: nil, terminated: false)

            case "done":
                var followUps: [String] = []
                if !data.isEmpty {
                    guard let obj = Self.parseJsonObject(data) else {
                        return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                    }
                    if let rawList = obj["followUps"] as? [Any] {
                        for entry in rawList {
                            if let item = entry as? String, !TextMetrics.trimSpace(item).isEmpty {
                                followUps.append(item)
                            }
                        }
                    }
                }
                return SseStepOutcome(event: .done(followUps: followUps), error: nil, terminated: true)

            case "error":
                let obj: [String: Any]
                if data.isEmpty {
                    obj = [:]
                } else if let parsed = Self.parseJsonObject(data) {
                    obj = parsed
                } else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let rawCode = ApiClient.optClean(obj, "code")
                let code: String? = rawCode.isEmpty ? nil : rawCode
                let rawMessage = (obj["message"] as? String) ?? ""
                let message = TextMetrics.trimSpace(rawMessage).isEmpty
                    ? "AI 助教暂时不可用，请稍后再试。"
                    : rawMessage
                return SseStepOutcome(
                    event: .error(code: code, message: message),
                    error: nil,
                    terminated: true
                )

            default:
                return .none
            }
        }

        private static func parseJsonObject(_ raw: String) -> [String: Any]? {
            guard let bytes = raw.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                return nil
            }
            return obj
        }
    }

    private static func mapURLError(_ error: URLError) -> ApiError {
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

    private static func writingContextPayload(issue: ReviewIssue, exercise: WritingExercise) -> [String: Any] {
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

    private static func requireOriginalIssue(_ body: [String: Any], matches issue: ReviewIssue) throws {
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

    private static func readStudent(_ body: [String: Any]) throws -> Student {
        guard let id = parseExactInt64(body["id"]), id > 0 else {
            throw ApiError.malformed
        }
        let username = try requiredText(body, "username")
        return Student(id: id, username: username)
    }

    private static func requireMode(_ body: [String: Any]) throws -> String {
        let mode = try requiredText(body, "mode")
        guard mode == "demo" || mode == "live" else {
            throw ApiError.malformed
        }
        return mode
    }

    private static func requiredText(_ dict: [String: Any], _ key: String) throws -> String {
        guard let text = dict[key] as? String, !TextMetrics.trimSpace(text).isEmpty else {
            throw ApiError.malformed
        }
        return text
    }

    private static func optClean(_ dict: [String: Any], _ key: String) -> String {
        guard let text = dict[key] as? String else { return "" }
        return TextMetrics.trimSpace(text)
    }

    private static func parseOptionalScore(_ dict: [String: Any], _ key: String) -> Int {
        guard let value = parseOptionalInt(dict, key), value >= 0 else {
            return -1
        }
        return value
    }

    private static func parseOptionalInt(_ dict: [String: Any], _ key: String) -> Int? {
        guard let raw = dict[key], !(raw is NSNull) else { return nil }
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        return num.intValue
    }

    private static func parseExactInt(_ raw: Any?) -> Int? {
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        let intVal = num.intValue
        guard Double(intVal) == num.doubleValue else { return nil }
        return intVal
    }

    private static func parseExactInt64(_ raw: Any?) -> Int64? {
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

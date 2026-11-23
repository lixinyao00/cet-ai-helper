import XCTest
@testable import CetHelper

final class ApiClientTests: XCTestCase {
    private var api: ApiClient!
    private let session = Session(
        serverUrl: "http://127.0.0.1:8080",
        token: "tok-abc",
        student: Student(id: 1, username: "stu")
    )

    override func setUp() {
        StubURLProtocol.setHandler(nil)
        api = ApiClient(configuration: StubURLProtocol.makeConfiguration())
    }

    override func tearDown() {
        StubURLProtocol.setHandler(nil)
        api = nil
    }

    func testNormalizeBaseUrlTable() throws {
        XCTAssertEqual(try ApiClient.normalizeBaseUrl("  HTTP://Example.COM:80/ "), "http://example.com")
        XCTAssertEqual(try ApiClient.normalizeBaseUrl("HTTPS://API.Example.com:443/sub///"), "https://api.example.com/sub")
        XCTAssertEqual(try ApiClient.normalizeBaseUrl("http://127.0.0.1:8080/"), "http://127.0.0.1:8080")
        XCTAssertEqual(try ApiClient.normalizeBaseUrl("https://host.local:8443/v1/"), "https://host.local:8443/v1")

        let invalid = [
            "",
            "   ",
            "ftp://example.com",
            "http://",
            "http://user:pass@example.com",
            "http://example.com?a=1",
            "http://example.com#frag",
            "http://example.com:0",
            "http://example.com:65536",
            "http://example.com:abc",
            "not-a-url"
        ]
        for raw in invalid {
            XCTAssertThrowsError(try ApiClient.normalizeBaseUrl(raw), "Expected failure for \(raw)")
        }
    }

    func testHeadersPathsNoContentErrorFallbackRedirectAndTransportErrors() async throws {
        StubURLProtocol.setHandler { req in
            XCTAssertEqual(req.url?.absoluteString, "http://127.0.0.1:8080/api/v1/auth/logout")
            XCTAssertEqual(req.httpMethod, "POST")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Accept"), "application/json")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer tok-abc")
            let resp = HTTPURLResponse(url: req.url!, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)!
            return (resp, Data())
        }
        try await api.logout(baseUrl: session.serverUrl, token: session.token)

        // Fallback error message on HTTP 500 with non-JSON body
        StubURLProtocol.setHandler { req in
            let resp = HTTPURLResponse(url: req.url!, statusCode: 500, httpVersion: "HTTP/1.1", headerFields: nil)!
            return (resp, Data("plain text".utf8))
        }
        do {
            _ = try await api.health(baseUrl: session.serverUrl)
            XCTFail("Should throw")
        } catch let err as ApiError {
            XCTAssertEqual(err, .http(status: 500, code: nil, message: "服务请求失败（HTTP 500）。"))
        }

        // 302 redirect is NOT followed
        StubURLProtocol.setHandler { req in
            let resp = HTTPURLResponse(
                url: req.url!,
                statusCode: 302,
                httpVersion: "HTTP/1.1",
                headerFields: ["Location": "http://127.0.0.1:8080/elsewhere"]
            )!
            return (resp, Data())
        }
        do {
            _ = try await api.health(baseUrl: session.serverUrl)
            XCTFail("Should throw 302")
        } catch let err as ApiError {
            if case .http(let status, _, _) = err {
                XCTAssertEqual(status, 302)
            } else {
                XCTFail("Expected http 302, got \(err)")
            }
        }

        // URLError mapping -> connectionFailure & timeout; HTTP 504 -> timeout
        let connCodes: [URLError.Code] = [
            .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost,
            .networkConnectionLost, .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed
        ]
        for code in connCodes {
            StubURLProtocol.setHandler { _ in throw URLError(code) }
            do {
                _ = try await api.health(baseUrl: session.serverUrl)
                XCTFail("Expected connectionFailure")
            } catch let err as ApiError {
                XCTAssertEqual(err, .connectionFailure)
            }
        }

        StubURLProtocol.setHandler { _ in throw URLError(.timedOut) }
        do {
            _ = try await api.health(baseUrl: session.serverUrl)
            XCTFail("Expected timeout")
        } catch let err as ApiError {
            XCTAssertEqual(err, .timeout)
        }

        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, status: 504, json: ["code": "MODEL_TIMEOUT", "message": "timeout"])
        }
        do {
            _ = try await api.health(baseUrl: session.serverUrl)
            XCTFail("Expected timeout on 504")
        } catch let err as ApiError {
            XCTAssertEqual(err, .timeout)
        }
    }

    func testContractValidationMalformedCasesAndOptionalScoreRawJson() async throws {
        // 1. Sentence analysis echo mismatch & 0 chunks & 21 chunks & bad mode
        let badSentencePayloads: [[String: Any]] = [
            ["sentence": "Wrong.", "translation": "错", "structure": "S", "mode": "demo", "chunks": [["text": "Wrong.", "role": "S", "explanation": "e"]]],
            ["sentence": "Hi.", "translation": "嗨", "structure": "S", "mode": "demo", "chunks": []],
            ["sentence": "Hi.", "translation": "嗨", "structure": "S", "mode": "invalid", "chunks": [["text": "Hi.", "role": "S", "explanation": "e"]]],
            ["sentence": String(repeating: "a ", count: 25), "translation": "t", "structure": "s", "mode": "demo", "chunks": (0..<21).map { _ in ["text": "a", "role": "r", "explanation": "e"] }]
        ]
        for (idx, payload) in badSentencePayloads.enumerated() {
            let sent = idx == 3 ? String(repeating: "a ", count: 25) : "Hi."
            StubURLProtocol.setHandler { req in makeJSONResponse(url: req.url!, json: payload) }
            do {
                _ = try await api.analyzeSentence(session, sentence: sent)
                XCTFail("Expected malformed for sentence payload #\(idx)")
            } catch let err as ApiError {
                XCTAssertEqual(err, .malformed)
            }
        }

        // 2. Translation hint empty keywords / structure with <= 1 item
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "sourceText": "原文", "hintType": "keyword", "mode": "demo", "keywords": []
            ])
        }
        do {
            _ = try await api.translationHint(session, sourceText: "原文", type: .keyword)
            XCTFail("Expected malformed for empty keywords")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }

        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "sourceText": "原文", "hintType": "structure", "mode": "demo",
                "structure": ["mainClause": "Main", "clauses": [], "connectives": []]
            ])
        }
        do {
            _ = try await api.translationHint(session, sourceText: "原文", type: .structure)
            XCTFail("Expected malformed for structure with <= 1 item")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }

        // 3. Translation review missing quote -> malformed; missing/negative score -> -1, scoreMax default 15, rawJson kept
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "sourceText": "中文原文",
                "studentTranslation": "English draft.",
                "mode": "live",
                "overallFeedback": "Good",
                "issues": [[
                    "type": "OMISSION", "quote": "不存在", "detail": "d", "suggestion": "s"
                ]]
            ])
        }
        do {
            _ = try await api.reviewTranslation(session, sourceText: "中文原文", studentTranslation: "English draft.")
            XCTFail("Expected malformed for missing quote")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }

        let validTransJson: [String: Any] = [
            "sourceText": "中文原文",
            "studentTranslation": "English draft.",
            "mode": "demo",
            "overallFeedback": "整体不错",
            "estimatedScore": -5,
            "issues": [[
                "type": "OMISSION", "quote": "中文", "detail": "漏了中文", "suggestion": "补上 Chinese"
            ]]
        ]
        StubURLProtocol.setHandler { req in makeJSONResponse(url: req.url!, json: validTransJson) }
        let transFeedback = try await api.reviewTranslation(session, sourceText: "中文原文", studentTranslation: "English draft.")
        XCTAssertEqual(transFeedback.estimatedScore, -1)
        XCTAssertEqual(transFeedback.scoreMax, 15)
        XCTAssertFalse(transFeedback.hasScore)
        XCTAssertTrue(transFeedback.rawJson.contains("整体不错"))
        XCTAssertEqual(transFeedback.issues.first?.target, .source)

        // 4. Writing review: issues > 3, quote > 240, exercise out of bounds -> malformed
        let longEssay = String(repeating: "a", count: 250)
        let badWritingCases: [[String: Any]] = [
            [
                "prompt": "P", "essay": "a b c d", "mode": "demo", "overallFeedback": "ok",
                "issues": (0..<4).map { _ in ["type": "GRAMMAR", "quote": "a", "detail": "d", "suggestion": "s"] }
            ],
            [
                "prompt": "P", "essay": longEssay, "mode": "demo", "overallFeedback": "ok",
                "issues": [["type": "GRAMMAR", "quote": String(repeating: "a", count: 241), "detail": "d", "suggestion": "s"]]
            ],
            [
                "prompt": "P", "essay": "a b c d", "mode": "demo", "overallFeedback": "ok",
                "issues": [["type": "GRAMMAR", "quote": "a", "detail": "d", "suggestion": "s"]],
                "exercise": ["issueIndex": 1, "instruction": "fix", "item": "x"]
            ]
        ]
        for (idx, payload) in badWritingCases.enumerated() {
            let essay = idx == 1 ? longEssay : "a b c d"
            StubURLProtocol.setHandler { req in makeJSONResponse(url: req.url!, json: payload) }
            do {
                _ = try await api.reviewWriting(session, prompt: "P", essay: essay)
                XCTFail("Expected malformed for writing case #\(idx)")
            } catch let err as ApiError {
                XCTAssertEqual(err, .malformed)
            }
        }

        // 5. Writing assessment: verdict mismatched with retest, TARGET_USED missing quote
        let issue = ReviewIssue(
            index: 0, type: .grammar, quote: "prefers", detail: "d", suggestion: "s",
            target: .draft, matches: [TextRange(start: 0, end: 7)]
        )
        let exercise = WritingExercise(issueIndex: 0, instruction: "Fix verb", item: "Students prefers")
        let task = WritingTask(instruction: "Write new", item: "Prompt")

        // Exercise attempt returning retest verdict -> malformed
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "issue": ["type": "GRAMMAR", "quote": "prefers", "detail": "d", "suggestion": "s"],
                "mode": "demo",
                "answer": "Students prefer",
                "verdict": "TARGET_USED",
                "quote": "prefer",
                "feedback": "fb",
                "nextStep": "ns"
            ])
        }
        do {
            _ = try await api.attemptWritingExercise(session, issue: issue, exercise: exercise, answer: "Students prefer")
            XCTFail("Expected malformed for retest verdict in exercise attempt")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }

        // Retest attempt with TARGET_USED missing quote -> malformed
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "issue": ["type": "GRAMMAR", "quote": "prefers", "detail": "d", "suggestion": "s"],
                "mode": "demo",
                "answer": "Students prefer",
                "verdict": "TARGET_USED",
                "feedback": "fb",
                "nextStep": "ns"
            ])
        }
        do {
            _ = try await api.attemptWritingRetest(session, issue: issue, exercise: exercise, task: task, answer: "Students prefer")
            XCTFail("Expected malformed for TARGET_USED without quote")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }
    }

    func testOrganizeArticleRequestPayloadResponseAndMalformed() async throws {
        // 1. Reading target with rawText + imageBase64 + imageMimeType
        StubURLProtocol.setHandler { req in
            XCTAssertEqual(req.url?.absoluteString, "http://127.0.0.1:8080/api/v1/articles/organize")
            XCTAssertEqual(req.httpMethod, "POST")
            XCTAssertEqual(req.timeoutInterval, 45, accuracy: 0.001)
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer tok-abc")

            let bodyData = readBodyData(from: req)
            let json = try JSONSerialization.jsonObject(with: bodyData) as? [String: Any]
            XCTAssertEqual(json?["rawText"] as? String, "Raw OCR text")
            XCTAssertEqual(json?["imageBase64"] as? String, "AAAA")
            XCTAssertEqual(json?["imageMimeType"] as? String, "image/jpeg")
            XCTAssertEqual(json?["target"] as? String, "reading")

            return makeJSONResponse(url: req.url!, json: [
                "title": "  Clean Title  ",
                "body": "Paragraph 1.\n\nParagraph 2.",
                "summary": "  已自动整理  ",
                "mode": " live "
            ])
        }

        let organized = try await api.organizeArticle(
            session,
            rawText: "Raw OCR text",
            imageBase64: "AAAA",
            imageMimeType: " image/jpeg ",
            target: .reading
        )
        XCTAssertEqual(organized.title, "Clean Title")
        XCTAssertEqual(organized.body, "Paragraph 1.\n\nParagraph 2.")
        XCTAssertEqual(organized.summary, "已自动整理")
        XCTAssertEqual(organized.mode, "live")

        // 2. Translation target with text only (blank image omitted, blank rawText omitted when nil)
        StubURLProtocol.setHandler { req in
            let bodyData = readBodyData(from: req)
            let json = try JSONSerialization.jsonObject(with: bodyData) as? [String: Any]
            XCTAssertEqual(json?["rawText"] as? String, "中文段落。")
            XCTAssertNil(json?["imageBase64"])
            XCTAssertNil(json?["imageMimeType"])
            XCTAssertEqual(json?["target"] as? String, "translation")

            return makeJSONResponse(url: req.url!, json: [
                "body": "中文段落。",
                "mode": "demo"
            ])
        }

        let translationOrganized = try await api.organizeArticle(
            session,
            rawText: "中文段落。",
            imageBase64: nil,
            imageMimeType: "image/jpeg",
            target: .translation
        )
        XCTAssertEqual(translationOrganized.title, "")
        XCTAssertEqual(translationOrganized.body, "中文段落。")
        XCTAssertEqual(translationOrganized.summary, "")
        XCTAssertEqual(translationOrganized.mode, "demo")

        // 3. Missing or blank body -> malformed
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: [
                "title": "Only Title",
                "body": "   ",
                "summary": "s",
                "mode": "demo"
            ])
        }
        do {
            _ = try await api.organizeArticle(
                session,
                rawText: "Raw text",
                imageBase64: nil,
                imageMimeType: nil,
                target: .reading
            )
            XCTFail("Expected malformed when body is blank")
        } catch let err as ApiError {
            XCTAssertEqual(err, .malformed)
        }
    }
}

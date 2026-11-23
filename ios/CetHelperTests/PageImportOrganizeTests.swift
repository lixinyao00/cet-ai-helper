import UIKit
import XCTest
@testable import CetHelper

@MainActor
final class PageImportOrganizeTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var tempDir: URL!

    private let session = Session(
        serverUrl: "http://127.0.0.1:8080",
        token: "tok-import",
        student: Student(id: 7, username: "reader")
    )

    override func setUpWithError() throws {
        StubURLProtocol.setHandler(nil)
        suiteName = "com.cethelper.tests.pageimport.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("cet-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        StubURLProtocol.setHandler(nil)
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    private func makeEnv(
        recognizer: @escaping PageOcrService.ImageRecognizer
    ) throws -> AppEnvironment {
        let db = try StudyDatabase(path: tempDir.appendingPathComponent("study.db").path)
        return AppEnvironment(
            database: db,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults, debounce: .milliseconds(20)),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient(configuration: StubURLProtocol.makeConfiguration()),
            ocr: PageOcrService(recognizer: recognizer)
        )
    }

    private func makeSampleImage(width: CGFloat = 120, height: CGFloat = 80) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            UIColor.darkGray.setFill()
            ctx.fill(CGRect(x: 10, y: 10, width: 40, height: 20))
        }
    }

    private func waitUntil(timeout: TimeInterval = 3.0, _ condition: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            try? await Task.sleep(for: .milliseconds(15))
        }
    }

    func testAutoOrganizeSuccessFillsTruncatedTitleUpdatesBodyAndSupportsRestoreRaw() async throws {
        let rawOcr = "Page 3\n\nModern cities face grow-\ning traffic pressure."
        let longTitle = String(repeating: "A", count: 140)
        let organizedBody = "Modern cities face growing traffic pressure."

        var capturedRaw: String?
        var capturedImageBase64: String?
        var capturedMime: String?
        var capturedTarget: String?

        StubURLProtocol.setHandler { req in
            let json = try JSONSerialization.jsonObject(with: readBodyData(from: req)) as? [String: Any]
            capturedRaw = json?["rawText"] as? String
            capturedImageBase64 = json?["imageBase64"] as? String
            capturedMime = json?["imageMimeType"] as? String
            capturedTarget = json?["target"] as? String
            return makeJSONResponse(url: req.url!, json: [
                "title": longTitle,
                "body": organizedBody,
                "summary": "已过滤页码并合并断词。",
                "mode": "live"
            ])
        }

        let env = try makeEnv { _, _ in rawOcr }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})
        let vm = PageImportViewModel(
            target: .reading,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: {},
            onReplaceTop: { _ in }
        )

        XCTAssertEqual(vm.statusMessage, "等待相册选择…")
        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }

        XCTAssertEqual(capturedRaw, rawOcr)
        XCTAssertNotNil(capturedImageBase64)
        XCTAssertFalse(capturedImageBase64?.isEmpty ?? true)
        XCTAssertEqual(capturedMime, "image/jpeg")
        XCTAssertEqual(capturedTarget, "reading")

        XCTAssertEqual(vm.bodyText, organizedBody)
        XCTAssertEqual(vm.title, String(repeating: "A", count: PageImportViewModel.maxTitleLength))
        XCTAssertEqual(vm.notice, "已过滤页码并合并断词。")
        XCTAssertTrue(vm.isRestoreRawVisible)

        // Restore raw resets bodyText and hides restore button.
        vm.restoreRaw()
        XCTAssertEqual(vm.bodyText, rawOcr)
        XCTAssertEqual(vm.notice, "已还原为本机初始识别文本。")
        XCTAssertFalse(vm.isRestoreRawVisible)
    }

    func testAutoOrganizeEmptySummaryUsesDefaultNoticeAndTranslationDoesNotSetTitle() async throws {
        StubURLProtocol.setHandler { req in
            let json = try JSONSerialization.jsonObject(with: readBodyData(from: req)) as? [String: Any]
            XCTAssertEqual(json?["target"] as? String, "translation")
            return makeJSONResponse(url: req.url!, json: [
                "title": "不应填入翻译标题",
                "body": "绿色出行逐渐成为日常选择。",
                "summary": "",
                "mode": "demo"
            ])
        }

        let env = try makeEnv { _, _ in "绿色出行逐渐成为\n日常选择。" }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})
        let vm = PageImportViewModel(
            target: .translation,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: {},
            onReplaceTop: { _ in }
        )

        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }

        XCTAssertEqual(vm.bodyText, "绿色出行逐渐成为日常选择。")
        XCTAssertEqual(vm.title, "")
        XCTAssertEqual(vm.notice, "已自动整理正文，可点「还原原文」撤销。")
        XCTAssertTrue(vm.isRestoreRawVisible)
    }

    func testAutoOrganizeFailureOversizedBodyAnd401Fallback() async throws {
        let rawOcr = "Raw recognized English passage."
        var unauthorizedCount = 0

        // 1. Network/HTTP error -> fallback to rawBody
        StubURLProtocol.setHandler { _ in throw URLError(.timedOut) }
        let env = try makeEnv { _, _ in rawOcr }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})
        let vm = PageImportViewModel(
            target: .reading,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: {},
            onReplaceTop: { _ in },
            onUnauthorized: { unauthorizedCount += 1 }
        )

        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }
        XCTAssertEqual(vm.bodyText, rawOcr)
        XCTAssertEqual(vm.notice, "AI 整理暂不可用，已使用本机排版结果（可手动微调）。")
        XCTAssertFalse(vm.isRestoreRawVisible)
        XCTAssertEqual(unauthorizedCount, 0)

        // 2. Oversized body (> 50_000) during auto-organize -> fallback to rawBody
        let oversized = String(repeating: "x", count: PageImportViewModel.maxBodyLength + 1)
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: ["title": "", "body": oversized, "summary": "ok", "mode": "live"])
        }
        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }
        XCTAssertEqual(vm.bodyText, rawOcr)
        XCTAssertEqual(vm.notice, "AI 整理暂不可用，已使用本机排版结果（可手动微调）。")

        // 3. 401 -> invokes onUnauthorized and falls back to rawBody
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, status: 401, json: ["code": "UNAUTHORIZED", "message": "expired"])
        }
        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }
        XCTAssertEqual(vm.bodyText, rawOcr)
        XCTAssertEqual(unauthorizedCount, 1)
    }

    func testAppendPageMarksMultiPageSkipsAutoOrganizeAndManualOrganizeOmitsImage() async throws {
        var ocrCallCount = 0
        let env = try makeEnv { _, _ in
            ocrCallCount += 1
            return ocrCallCount == 1 ? "First page text." : "Second page text."
        }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})

        var apiCalls = 0
        var lastPayloadHadImage = false
        var lastPayloadRawText: String?
        StubURLProtocol.setHandler { req in
            apiCalls += 1
            let json = try JSONSerialization.jsonObject(with: readBodyData(from: req)) as? [String: Any]
            lastPayloadHadImage = (json?["imageBase64"] != nil)
            lastPayloadRawText = json?["rawText"] as? String
            return makeJSONResponse(url: req.url!, json: [
                "title": "Combined Title",
                "body": apiCalls == 1 ? "First page organized." : "Combined organized body.",
                "summary": "整理完成",
                "mode": "live"
            ])
        }

        let vm = PageImportViewModel(
            target: .reading,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: {},
            onReplaceTop: { _ in }
        )

        // First page: auto-organizes with image
        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && !vm.isOrganizing }
        XCTAssertEqual(apiCalls, 1)
        XCTAssertTrue(lastPayloadHadImage)
        XCTAssertEqual(vm.bodyText, "First page organized.")

        // Append second page: does NOT auto-call organizeArticle
        vm.appendPage(from: .photoLibrary)
        vm.didPick(image: makeSampleImage())
        await waitUntil { vm.stage == .review && vm.notice == "已追加新的一页识别内容。" }
        XCTAssertEqual(apiCalls, 1)
        XCTAssertEqual(vm.bodyText, "First page organized.\n\nSecond page text.")

        // Manual organize on multi-page: sends text only, NO imageBase64
        vm.organizeManually()
        await waitUntil { !vm.isOrganizing }
        XCTAssertEqual(apiCalls, 2)
        XCTAssertFalse(lastPayloadHadImage, "多页手动整理不应携带单页原图")
        XCTAssertEqual(lastPayloadRawText, "First page organized.\n\nSecond page text.")
        XCTAssertEqual(vm.bodyText, "Combined organized body.")

        // Restore raw brings back combined raw pages
        vm.restoreRaw()
        XCTAssertEqual(vm.bodyText, "First page text.\n\nSecond page text.")
    }

    func testManualOrganizeEmptyGuardOversizedBodyAndConfirmBlockedWhileOrganizing() async throws {
        let env = try makeEnv { _, _ in "Initial OCR text." }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})

        var replacedScreen: AppScreen?
        var popped = false
        let vm = PageImportViewModel(
            target: .reading,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: { popped = true },
            onReplaceTop: { replacedScreen = $0 }
        )

        // Empty body & no image -> guard message
        vm.organizeManually()
        XCTAssertEqual(vm.notice, "请先输入或识别正文后再整理。")
        XCTAssertFalse(vm.isOrganizing)

        // Populate body without image, then return oversized body from manual organize
        vm.title = "Keep Existing Title"
        vm.bodyText = "Manual draft text."
        let oversized = String(repeating: "z", count: PageImportViewModel.maxBodyLength + 1)
        StubURLProtocol.setHandler { req in
            makeJSONResponse(url: req.url!, json: ["title": "New Title", "body": oversized, "summary": "s", "mode": "live"])
        }
        vm.organizeManually()
        await waitUntil { !vm.isOrganizing }
        XCTAssertEqual(vm.bodyText, "Manual draft text.")
        XCTAssertEqual(vm.title, "Keep Existing Title")
        XCTAssertEqual(vm.notice, "整理结果超出长度限制，已保留当前正文。")

        // Confirm while organizing is ignored, and cancel cancels in-flight organize task
        let startedRequest = expectation(description: "organize-started")
        let releaseRequest = DispatchSemaphore(value: 0)
        StubURLProtocol.setHandler { req in
            startedRequest.fulfill()
            _ = releaseRequest.wait(timeout: .now() + 2)
            return makeJSONResponse(url: req.url!, json: ["title": "Late", "body": "Late body", "summary": "s", "mode": "live"])
        }

        vm.organizeManually()
        await fulfillment(of: [startedRequest], timeout: 2.0)
        XCTAssertTrue(vm.isOrganizing)
        vm.confirm()
        XCTAssertNil(replacedScreen, "整理进行中 confirm() 应被忽略")

        vm.cancel()
        releaseRequest.signal()
        XCTAssertTrue(popped)
        XCTAssertFalse(vm.isOrganizing)
    }

    func testStatusMessageDuringOrganizingAndSaving() async throws {
        let requestStarted = expectation(description: "auto-organize-started")
        let releaseRequest = DispatchSemaphore(value: 0)
        StubURLProtocol.setHandler { req in
            requestStarted.fulfill()
            _ = releaseRequest.wait(timeout: .now() + 2)
            return makeJSONResponse(url: req.url!, json: [
                "title": "Sample Title",
                "body": "Cleaned English passage.",
                "summary": "整理完成",
                "mode": "demo"
            ])
        }

        let env = try makeEnv { _, _ in "Raw English passage." }
        let translation = TranslationViewModel(session: session, environment: env, onUnauthorized: {})
        let vm = PageImportViewModel(
            target: .reading,
            initialSource: .photoLibrary,
            session: session,
            environment: env,
            translation: translation,
            onPop: {},
            onReplaceTop: { _ in }
        )

        vm.didPick(image: makeSampleImage())
        await fulfillment(of: [requestStarted], timeout: 2.0)
        XCTAssertEqual(vm.stage, .recognizing)
        XCTAssertTrue(vm.isOrganizing)
        XCTAssertEqual(vm.statusMessage, "正在 AI 提取正文并整理排版…")

        releaseRequest.signal()
        await waitUntil { vm.stage == .review && !vm.isOrganizing }
        XCTAssertEqual(vm.bodyText, "Cleaned English passage.")
    }
}

import Foundation
import UIKit

final class AppEnvironment {
    let database: StudyDatabase
    let sessions: SessionStore
    let drafts: DraftStore
    let readerPrefs: ReaderPreferences
    let api: ApiClient
    let ocr: PageOcrService
    let explanationCache: LruCache<String, ExplanationResult>
    let analysisCache: LruCache<String, SentenceAnalysis>
    let reverifyDelay: Duration
    let nowMs: () -> Int64
    let speech: SpeechService

    init(
        database: StudyDatabase,
        sessions: SessionStore,
        drafts: DraftStore,
        readerPrefs: ReaderPreferences,
        api: ApiClient,
        ocr: PageOcrService = PageOcrService(),
        speech: SpeechService = SpeechService(),
        explanationCache: LruCache<String, ExplanationResult> = LruCache(capacity: 200),
        analysisCache: LruCache<String, SentenceAnalysis> = LruCache(capacity: 100),
        reverifyDelay: Duration = .seconds(10),
        nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.database = database
        self.sessions = sessions
        self.drafts = drafts
        self.readerPrefs = readerPrefs
        self.api = api
        self.ocr = ocr
        self.speech = speech
        self.explanationCache = explanationCache
        self.analysisCache = analysisCache
        self.reverifyDelay = reverifyDelay
        self.nowMs = nowMs
    }

    /// 读取启动参数：-server_url <url>（UserDefaults 参数域覆盖）、-cet_reset_state YES（清空 UserDefaults 键与 study.db）。→ §5.4.10
    static func live() -> AppEnvironment {
        let defaults = UserDefaults.standard
        let dbPath = defaultDatabasePath()

        if shouldResetState(defaults: defaults) {
            resetLocalState(defaults: defaults, dbPath: dbPath)
        }

        let database: StudyDatabase
        do {
            database = try StudyDatabase(path: dbPath)
        } catch {
            // Fallback to in-memory database if filesystem fails unexpectedly.
            database = try! StudyDatabase(path: ":memory:")
        }

        return AppEnvironment(
            database: database,
            sessions: SessionStore(defaults: defaults),
            drafts: DraftStore(defaults: defaults),
            readerPrefs: ReaderPreferences(defaults: defaults),
            api: ApiClient()
        )
    }

    private static func defaultDatabasePath() -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return docs.appendingPathComponent("study.db").path
    }

    private static func shouldResetState(defaults: UserDefaults) -> Bool {
        if defaults.bool(forKey: "cet_reset_state") {
            return true
        }
        let args = ProcessInfo.processInfo.arguments
        if let idx = args.firstIndex(of: "-cet_reset_state"), idx + 1 < args.count {
            let val = args[idx + 1].uppercased()
            return val == "YES" || val == "TRUE" || val == "1"
        }
        return false
    }

    private static func resetLocalState(defaults: UserDefaults, dbPath: String) {
        if let bundleId = Bundle.main.bundleIdentifier {
            defaults.removePersistentDomain(forName: bundleId)
        }
        defaults.removeObject(forKey: SessionStore.tokenKey)
        defaults.removeObject(forKey: SessionStore.verifiedSessionKey)
        defaults.removeObject(forKey: SessionStore.serverUrlKey)
        defaults.removeObject(forKey: ReaderPreferences.fontTierKey)
        defaults.synchronize()

        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm"] {
            let target = dbPath + suffix
            if fm.fileExists(atPath: target) {
                try? fm.removeItem(atPath: target)
            }
        }
    }
}

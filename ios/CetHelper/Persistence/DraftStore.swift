import Foundation

struct TranslationDraft: Hashable, Sendable {
    let source: String
    let draft: String
}

struct WritingDraft: Hashable, Sendable {
    let prompt: String
    let essay: String
}

final class DraftStore {
    private let defaults: UserDefaults
    private let debounceInterval: TimeInterval
    private let queue = DispatchQueue(label: "com.cethelper.draftstore")
    private let lock = NSLock()

    private var pendingTranslationWork: DispatchWorkItem?
    private var pendingTranslationWrite: (() -> Void)?
    private var pendingWritingWork: DispatchWorkItem?
    private var pendingWritingWrite: (() -> Void)?

    init(defaults: UserDefaults = .standard, debounce: Duration = .milliseconds(400)) {
        self.defaults = defaults
        let components = debounce.components
        self.debounceInterval = Double(components.seconds) + Double(components.attoseconds) / 1e18
    }

    deinit {
        flush()
    }

    func translation(for owner: ArticleOwner) -> TranslationDraft {
        flushTranslation()
        let key = Self.prefix(owner)
        return TranslationDraft(
            source: defaults.string(forKey: key + "translation.source") ?? "",
            draft: defaults.string(forKey: key + "translation.draft") ?? ""
        )
    }

    func saveTranslation(owner: ArticleOwner, source: String, draft: String, immediate: Bool = false) {
        let writeAction: () -> Void = { [defaults] in
            let key = Self.prefix(owner)
            defaults.set(source, forKey: key + "translation.source")
            defaults.set(draft, forKey: key + "translation.draft")
        }

        if immediate || debounceInterval <= 0 {
            lock.lock()
            cancelTranslationLocked()
            lock.unlock()
            writeAction()
            return
        }

        lock.lock()
        cancelTranslationLocked()
        pendingTranslationWrite = writeAction
        var item: DispatchWorkItem!
        item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.lock.lock()
            guard self.pendingTranslationWork === item, let action = self.pendingTranslationWrite else {
                self.lock.unlock()
                return
            }
            self.pendingTranslationWork = nil
            self.pendingTranslationWrite = nil
            self.lock.unlock()
            action()
        }
        pendingTranslationWork = item
        lock.unlock()
        queue.asyncAfter(deadline: .now() + debounceInterval, execute: item)
    }

    func writing(for owner: ArticleOwner) -> WritingDraft {
        flushWriting()
        let key = Self.prefix(owner)
        return WritingDraft(
            prompt: defaults.string(forKey: key + "writing.prompt") ?? "",
            essay: defaults.string(forKey: key + "writing.essay") ?? ""
        )
    }

    func saveWriting(owner: ArticleOwner, prompt: String, essay: String, immediate: Bool = false) {
        let writeAction: () -> Void = { [defaults] in
            let key = Self.prefix(owner)
            defaults.set(prompt, forKey: key + "writing.prompt")
            defaults.set(essay, forKey: key + "writing.essay")
        }

        if immediate || debounceInterval <= 0 {
            lock.lock()
            cancelWritingLocked()
            lock.unlock()
            writeAction()
            return
        }

        lock.lock()
        cancelWritingLocked()
        pendingWritingWrite = writeAction
        var item: DispatchWorkItem!
        item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.lock.lock()
            guard self.pendingWritingWork === item, let action = self.pendingWritingWrite else {
                self.lock.unlock()
                return
            }
            self.pendingWritingWork = nil
            self.pendingWritingWrite = nil
            self.lock.unlock()
            action()
        }
        pendingWritingWork = item
        lock.unlock()
        queue.asyncAfter(deadline: .now() + debounceInterval, execute: item)
    }

    func flush() {
        flushTranslation()
        flushWriting()
    }

    private func flushTranslation() {
        lock.lock()
        let action = pendingTranslationWrite
        cancelTranslationLocked()
        lock.unlock()
        action?()
    }

    private func flushWriting() {
        lock.lock()
        let action = pendingWritingWrite
        cancelWritingLocked()
        lock.unlock()
        action?()
    }

    private func cancelTranslationLocked() {
        pendingTranslationWork?.cancel()
        pendingTranslationWork = nil
        pendingTranslationWrite = nil
    }

    private func cancelWritingLocked() {
        pendingWritingWork?.cancel()
        pendingWritingWork = nil
        pendingWritingWrite = nil
    }

    static func prefix(_ owner: ArticleOwner) -> String {
        "\(owner.studentId)|\(owner.serverUrl)|"
    }
}

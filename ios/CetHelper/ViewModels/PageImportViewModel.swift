import Foundation
import Observation
import UIKit

@MainActor @Observable final class PageImportViewModel {
    static let maxTitleLength = 120
    static let maxBodyLength = 50_000

    let target: PageImportTarget
    private(set) var stage: PageImportStage = .external
    var pickerSource: PageImportSource? = nil
    var isCameraAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }
    var showsTitleField: Bool { target == .reading }
    var title: String = ""
    var bodyText: String = ""
    private(set) var rawBody: String = ""
    private(set) var previewImage: UIImage? = nil
    var isPreviewExpanded: Bool = true
    private(set) var isOrganizing: Bool = false
    private(set) var notice: String? = nil
    private(set) var validationMessage: String? = nil
    var confirmation: ConfirmationRequest? = nil

    var isRestoreRawVisible: Bool { !rawBody.isEmpty && rawBody != bodyText }

    var statusMessage: String {
        switch stage {
        case .external:
            switch pickerSource ?? lastSource {
            case .camera:
                return "等待系统相机…"
            case .photoLibrary:
                return "等待相册选择…"
            }
        case .recognizing:
            if isOrganizing {
                return "正在 AI 提取正文并整理排版…"
            }
            return target == .reading ? "正在本机识别英文文字…" : "正在本机识别中文文字…"
        case .saving:
            return "正在保存…"
        case .error(let message):
            return message
        case .review:
            return ""
        }
    }

    private var lastSource: PageImportSource
    @ObservationIgnored private let session: Session
    @ObservationIgnored private let environment: AppEnvironment
    @ObservationIgnored private let translation: TranslationViewModel
    @ObservationIgnored private let onPop: () -> Void
    @ObservationIgnored private let onReplaceTop: (AppScreen) -> Void
    @ObservationIgnored private let onUnauthorized: (() -> Void)?
    @ObservationIgnored private var isAppending: Bool = false
    @ObservationIgnored private var isMultiPage: Bool = false
    @ObservationIgnored private var originalImage: UIImage? = nil
    @ObservationIgnored private var organizeGeneration: Int = 0
    @ObservationIgnored private var ocrTask: Task<Void, Never>?
    @ObservationIgnored private var organizeTask: Task<Void, Never>?
    @ObservationIgnored private var tempCaptureURLs: [URL] = []

    init(
        target: PageImportTarget,
        initialSource: PageImportSource,
        session: Session,
        environment: AppEnvironment,
        translation: TranslationViewModel,
        onPop: @escaping () -> Void,
        onReplaceTop: @escaping (AppScreen) -> Void,
        onUnauthorized: (() -> Void)? = nil
    ) {
        self.target = target
        self.lastSource = initialSource
        self.session = session
        self.environment = environment
        self.translation = translation
        self.onPop = onPop
        self.onReplaceTop = onReplaceTop
        self.onUnauthorized = onUnauthorized

        if initialSource == .camera && !UIImagePickerController.isSourceTypeAvailable(.camera) {
            self.stage = .error(message: "无法打开系统相机，请重拍或改从相册选择。")
            self.pickerSource = nil
        } else {
            self.stage = .external
            self.pickerSource = initialSource
        }
    }

    deinit {
        ocrTask?.cancel()
        organizeTask?.cancel()
        let urls = tempCaptureURLs
        for url in urls {
            try? FileManager.default.removeItem(at: url)
        }
    }

    func appendPage(from source: PageImportSource) {
        cancelOrganizeInFlight()
        validationMessage = nil
        if rawBody.isEmpty && !bodyText.isEmpty {
            rawBody = bodyText
        }
        if source == .camera && !isCameraAvailable {
            notice = "无法打开系统相机，请重拍或改从相册选择。"
            return
        }
        notice = nil
        isAppending = true
        lastSource = source
        pickerSource = source
    }

    func didPick(image: UIImage) {
        pickerSource = nil
        validationMessage = nil
        cancelOrganizeInFlight()
        stage = .recognizing

        let preview = environment.ocr.makePreview(from: image) ?? image
        persistTempCapture(image: preview)
        let appendingNow = isAppending
        let currentBody = bodyText
        let currentTarget = target
        let generation = organizeGeneration

        ocrTask?.cancel()
        ocrTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let recognized = await self.environment.ocr.recognize(image: image, target: currentTarget)
            guard !Task.isCancelled, self.organizeGeneration == generation else { return }
            self.handleOcrResult(
                recognized,
                preview: preview,
                sourceImage: image,
                appending: appendingNow,
                existingBody: currentBody
            )
        }
    }

    func didPick(imageData: Data) {
        pickerSource = nil
        validationMessage = nil
        cancelOrganizeInFlight()
        stage = .recognizing

        let appendingNow = isAppending
        let currentBody = bodyText
        let currentTarget = target
        let generation = organizeGeneration

        ocrTask?.cancel()
        ocrTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let result = await self.environment.ocr.recognize(imageData: imageData, target: currentTarget)
            guard !Task.isCancelled, self.organizeGeneration == generation else { return }
            if let preview = result.preview {
                self.persistTempCapture(image: preview)
            }
            let sourceImage = PageOcrService.decodeImage(
                from: imageData,
                maxEdge: PageOcrService.organizeImageMaxEdge
            ) ?? result.preview
            self.handleOcrResult(
                result.text,
                preview: result.preview,
                sourceImage: sourceImage,
                appending: appendingNow,
                existingBody: currentBody
            )
        }
    }

    /// 首次 .external 阶段取消 → 自动 pop；追加时取消 → 回到 .review。
    func pickerCancelled() {
        pickerSource = nil
        if isAppending {
            isAppending = false
            stage = .review
        } else {
            cancel()
        }
    }

    /// .error 阶段：重新拍/选（同 source）。
    func retry(with source: PageImportSource) {
        ocrTask?.cancel()
        ocrTask = nil
        cancelOrganizeInFlight()
        isAppending = false
        isMultiPage = false
        rawBody = ""
        originalImage = nil
        validationMessage = nil
        notice = nil
        lastSource = source
        if source == .camera && !isCameraAvailable {
            stage = .error(message: "无法打开系统相机，请重拍或改从相册选择。")
            pickerSource = nil
        } else {
            stage = .external
            pickerSource = source
        }
    }

    /// 手动触发 AI 智能整理（多页场景仅传正文不传图）。
    func organizeManually() {
        guard !isOrganizing else { return }
        validationMessage = nil

        let currentText = bodyText
        let trimmedCurrent = TextMetrics.trimSpace(currentText)
        let imageToSend: UIImage? = isMultiPage ? nil : originalImage
        if trimmedCurrent.isEmpty && imageToSend == nil {
            notice = "请先输入或识别正文后再整理。"
            return
        }
        guard hasActiveSession else {
            notice = Self.organizeFallbackNotice
            return
        }
        if rawBody.isEmpty && !trimmedCurrent.isEmpty {
            rawBody = currentText
        }
        let textToSend = !trimmedCurrent.isEmpty ? currentText : rawBody
        let capturedTarget = target

        organizeTask?.cancel()
        organizeGeneration += 1
        let generation = organizeGeneration
        isOrganizing = true
        notice = "正在 AI 智能整理…"

        organizeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let encodedImage = await Self.encodeImagePayload(imageToSend)
            guard !Task.isCancelled, self.organizeGeneration == generation else { return }

            do {
                let organized = try await self.environment.api.organizeArticle(
                    self.session,
                    rawText: textToSend,
                    imageBase64: encodedImage?.base64,
                    imageMimeType: encodedImage?.mimeType,
                    target: capturedTarget
                )
                guard !Task.isCancelled, self.organizeGeneration == generation else { return }
                self.isOrganizing = false
                self.organizeTask = nil

                if organized.body.utf16.count <= Self.maxBodyLength {
                    self.bodyText = organized.body
                    self.applySuggestedTitleIfNeeded(organized.title)
                    self.notice = !organized.summary.isEmpty
                        ? organized.summary
                        : Self.organizeDoneNotice
                } else {
                    self.notice = "整理结果超出长度限制，已保留当前正文。"
                }
            } catch {
                guard !Task.isCancelled, self.organizeGeneration == generation else { return }
                self.isOrganizing = false
                self.organizeTask = nil
                if let apiError = error as? ApiError, apiError.isUnauthorized {
                    self.onUnauthorized?()
                }
                self.notice = Self.organizeFallbackNotice
            }
        }
    }

    /// 撤销整理或手动修改，还原为本机初始识别文本。
    func restoreRaw() {
        guard !isOrganizing, !rawBody.isEmpty else { return }
        organizeGeneration += 1
        organizeTask?.cancel()
        organizeTask = nil
        bodyText = rawBody
        notice = "已还原为本机初始识别文本。"
    }

    /// reading → 存库并 replaceTop(.reader)；translation → 必要时 confirmation 后写入 TranslationViewModel 并 replaceTop(.translationAnswer)。
    func confirm() {
        guard !isOrganizing else { return }
        organizeGeneration += 1
        organizeTask?.cancel()
        organizeTask = nil

        validationMessage = nil
        let correctedTitle = TextMetrics.trimSpace(title)
        let normalizedBody = bodyText
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let correctedBody = TextMetrics.trimSpace(normalizedBody)

        switch target {
        case .translation:
            if correctedBody.isEmpty {
                validationMessage = "请输入中文原文。"
                return
            }
            if correctedBody.utf16.count > TranslationViewModel.maxSource {
                validationMessage = "中文原文最多 \(TranslationViewModel.maxSource) 个字符，请精简后再试。"
                return
            }
            if translation.hasInput {
                confirmation = ConfirmationRequest(
                    title: "替换本次练习",
                    message: "替换中文原文并清空当前英文草稿。",
                    confirmTitle: "替换",
                    isDestructive: true,
                    onConfirm: { [weak self] in
                        self?.finishTranslationImport(correctedBody)
                    }
                )
            } else {
                finishTranslationImport(correctedBody)
            }

        case .reading:
            if correctedTitle.isEmpty {
                validationMessage = "请输入文章标题。"
                return
            }
            if correctedBody.isEmpty {
                validationMessage = "请输入文章正文。"
                return
            }
            title = correctedTitle
            bodyText = correctedBody
            stage = .saving

            let articleId = UUID().uuidString.lowercased()
            let article = ImportedArticle(
                serverUrl: session.serverUrl,
                studentId: session.student.id,
                articleId: articleId,
                title: correctedTitle,
                body: correctedBody,
                createdAt: environment.nowMs()
            )
            do {
                try environment.database.insertArticle(article)
                cleanupTempCaptures()
                onReplaceTop(.reader(articleId: articleId, focus: nil))
            } catch {
                stage = .review
                validationMessage = "保存失败，请重试。"
            }
        }
    }

    /// 删除临时图片并 pop。
    func cancel() {
        ocrTask?.cancel()
        ocrTask = nil
        cancelOrganizeInFlight()
        originalImage = nil
        pickerSource = nil
        cleanupTempCaptures()
        onPop()
    }

    // MARK: - Private Helpers

    private static let organizeDoneNotice = "已自动整理正文，可点「还原原文」撤销。"
    private static let organizeFallbackNotice = "AI 整理暂不可用，已使用本机排版结果（可手动微调）。"

    private var hasActiveSession: Bool {
        !TextMetrics.trimSpace(session.serverUrl).isEmpty && !TextMetrics.trimSpace(session.token).isEmpty
    }

    private func cancelOrganizeInFlight() {
        organizeGeneration += 1
        organizeTask?.cancel()
        organizeTask = nil
        isOrganizing = false
    }

    private func handleOcrResult(
        _ recognized: String?,
        preview: UIImage?,
        sourceImage: UIImage?,
        appending: Bool,
        existingBody: String
    ) {
        guard let recognized else {
            handleRecognizeError("无法读取或识别这张图片，请重拍或重选。", appending: appending)
            return
        }
        if let validationError = PageOcrLayout.validate(
            recognized,
            target: target,
            existingBody: appending ? existingBody : nil
        ) {
            handleRecognizeError(validationError, appending: appending)
            return
        }

        if let preview {
            previewImage = preview
        }
        if appending {
            cancelOrganizeInFlight()
            if rawBody.isEmpty && !existingBody.isEmpty {
                rawBody = existingBody
            }
            let updatedBody = existingBody.isEmpty ? recognized : "\(existingBody)\n\n\(recognized)"
            bodyText = updatedBody
            rawBody = rawBody.isEmpty ? updatedBody : "\(rawBody)\n\n\(recognized)"
            isMultiPage = true
            notice = "已追加新的一页识别内容。"
            isAppending = false
            stage = .review
        } else {
            rawBody = recognized
            bodyText = recognized
            originalImage = sourceImage
            isMultiPage = false
            notice = nil
            guard hasActiveSession else {
                stage = .review
                return
            }
            organizeAfterRecognize(capturedRaw: recognized, sourceImage: sourceImage)
        }
    }

    private func organizeAfterRecognize(capturedRaw: String, sourceImage: UIImage?) {
        organizeTask?.cancel()
        organizeGeneration += 1
        let generation = organizeGeneration
        let capturedTarget = target
        stage = .recognizing
        isOrganizing = true

        organizeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let encodedImage = await Self.encodeImagePayload(sourceImage)
            guard !Task.isCancelled, self.organizeGeneration == generation else { return }

            do {
                let organized = try await self.environment.api.organizeArticle(
                    self.session,
                    rawText: capturedRaw,
                    imageBase64: encodedImage?.base64,
                    imageMimeType: encodedImage?.mimeType,
                    target: capturedTarget
                )
                guard !Task.isCancelled, self.organizeGeneration == generation else { return }
                self.isOrganizing = false
                self.organizeTask = nil

                if organized.body.utf16.count <= Self.maxBodyLength {
                    self.bodyText = organized.body
                    self.applySuggestedTitleIfNeeded(organized.title)
                    self.notice = !organized.summary.isEmpty
                        ? organized.summary
                        : Self.organizeDoneNotice
                } else {
                    self.bodyText = self.rawBody
                    self.notice = Self.organizeFallbackNotice
                }
                self.stage = .review
            } catch {
                guard !Task.isCancelled, self.organizeGeneration == generation else { return }
                self.isOrganizing = false
                self.organizeTask = nil
                if let apiError = error as? ApiError, apiError.isUnauthorized {
                    self.onUnauthorized?()
                }
                self.bodyText = self.rawBody
                self.notice = Self.organizeFallbackNotice
                self.stage = .review
            }
        }
    }

    private func applySuggestedTitleIfNeeded(_ suggestedTitle: String) {
        guard target == .reading,
              TextMetrics.trimSpace(title).isEmpty else {
            return
        }
        let truncated = Self.truncateTitle(suggestedTitle)
        if !truncated.isEmpty {
            title = truncated
        }
    }

    private static func truncateTitle(_ value: String) -> String {
        let trimmed = TextMetrics.trimSpace(value)
        if trimmed.count <= maxTitleLength {
            return trimmed
        }
        return String(trimmed.prefix(maxTitleLength))
    }

    private static func encodeImagePayload(_ image: UIImage?) async -> (base64: String, mimeType: String)? {
        guard let image else { return nil }
        return await Task.detached(priority: .userInitiated) {
            guard let base64 = PageOcrService.encodeJpegBase64(from: image), !base64.isEmpty else {
                return nil
            }
            return (base64, "image/jpeg")
        }.value
    }

    private func handleRecognizeError(_ reason: String, appending: Bool) {
        if appending {
            isAppending = false
            notice = "\(reason) 已保留原有内容。"
            stage = .review
        } else {
            cleanupTempCaptures()
            previewImage = nil
            originalImage = nil
            stage = .error(message: reason)
        }
    }

    private func finishTranslationImport(_ sourceText: String) {
        cleanupTempCaptures()
        translation.applyImportedSource(sourceText)
        onReplaceTop(.translationAnswer)
    }

    private func persistTempCapture(image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.85) else { return }
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let dir = caches.appendingPathComponent("page-captures", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let fileURL = dir.appendingPathComponent("page-\(UUID().uuidString.lowercased()).jpg")
            try data.write(to: fileURL, options: .atomic)
            tempCaptureURLs.append(fileURL)
        } catch {
            // Non-fatal: previewImage is already held in memory.
        }
    }

    private func cleanupTempCaptures() {
        for url in tempCaptureURLs {
            try? FileManager.default.removeItem(at: url)
        }
        tempCaptureURLs.removeAll()

        if let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first {
            let dir = caches.appendingPathComponent("page-captures", isDirectory: true)
            if let entries = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
                for entry in entries {
                    try? FileManager.default.removeItem(at: entry)
                }
            }
        }
    }
}

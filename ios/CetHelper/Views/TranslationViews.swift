import SwiftUI
import UIKit

struct TranslationSourceView: View {
    let scope: SessionScope
    @Bindable var viewModel: TranslationViewModel

    var body: some View {
        let cameraAvailable = UIImagePickerController.isSourceTypeAvailable(.camera)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center, spacing: 8) {
                    Button {
                        dismissKeyboard()
                        scope.pop()
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.left")
                                .font(.system(size: 15, weight: .medium))
                            Text("返回")
                        }
                    }
                    .buttonStyle(TranslationTopTextButtonStyle(leading: 4, trailing: 10))
                    .accessibilityIdentifier("nav.back")

                    Spacer(minLength: 8)

                    Button {
                        dismissKeyboard()
                        viewModel.presentHistory()
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.system(size: 15, weight: .medium))
                            Text("历史批改")
                        }
                    }
                    .buttonStyle(TranslationTopTextButtonStyle(leading: 8, trailing: 4))
                    .accessibilityIdentifier("translation.history")
                }

                Text("汉译英")
                    .font(StudyFont.screenTitle)
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)

                HStack(spacing: 12) {
                    Button {
                        scope.push(.pageImport(target: .translation, source: .camera))
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "camera")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(cameraAvailable ? Color.studyAccent : Color.studyMuted)
                            Text("拍照导入")
                                .font(.system(size: 13, weight: .medium))
                        }
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                    .disabled(!cameraAvailable)

                    Button {
                        scope.push(.pageImport(target: .translation, source: .photoLibrary))
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "photo.on.rectangle")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(Color.studyAccent)
                            Text("从相册导入")
                                .font(.system(size: 13, weight: .medium))
                        }
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                }
                .padding(.top, 12)

                Text("中文原文（可输入或粘贴）")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.studyMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 14)

                InlineHighlightTextEditor(
                    text: $viewModel.source,
                    field: .translationSource,
                    inlineHighlight: viewModel.inlineHighlight,
                    placeholder: "请输入或粘贴四六级汉译英中文段落…",
                    isSerif: false,
                    minHeight: 150,
                    accessibilityId: "translation.source"
                )
                .padding(.top, 6)

                if let validation = viewModel.validationMessage {
                    StudyNoticeBanner(message: validation, tone: .danger)
                        .padding(.top, 8)
                }

                HStack(spacing: 12) {
                    Button("使用演示材料") {
                        dismissKeyboard()
                        viewModel.useDemoSource()
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                    .accessibilityIdentifier("translation.useDemo")

                    Button("清空本练习") {
                        dismissKeyboard()
                        viewModel.clearSource()
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                }
                .padding(.top, 10)

                Button(viewModel.draft.isEmpty ? "开始作答" : "继续作答") {
                    dismissKeyboard()
                    viewModel.startAnswering()
                }
                .buttonStyle(StudyPrimaryButtonStyle())
                .padding(.top, 12)
                .accessibilityIdentifier("translation.start")
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onDisappear {
            viewModel.flushDraft()
        }
        .studyConfirmationAlert($viewModel.confirmation)
        .sheet(isPresented: $viewModel.isHistoryPresented) {
            TranslationHistorySheet(viewModel: viewModel)
        }
        .sheet(item: $viewModel.selectedRecord) { record in
            TranslationRecordDetailSheet(viewModel: viewModel, record: record) {
                scope.push(.translationAnswer)
            }
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

struct TranslationAnswerView: View {
    let scope: SessionScope
    @Bindable var viewModel: TranslationViewModel
    @State private var draftFocusToken: UUID? = nil

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .center, spacing: 8) {
                        Button {
                            dismissKeyboard()
                            scope.pop()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.left")
                                    .font(.system(size: 15, weight: .medium))
                                Text("编辑原文")
                            }
                        }
                        .buttonStyle(TranslationTopTextButtonStyle(leading: 4, trailing: 10))
                        .accessibilityIdentifier("nav.back")

                        Spacer(minLength: 8)

                        Button {
                            dismissKeyboard()
                            viewModel.presentHistory()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .font(.system(size: 15, weight: .medium))
                                Text("历史批改")
                            }
                        }
                        .buttonStyle(TranslationTopTextButtonStyle(leading: 8, trailing: 4))
                        .accessibilityIdentifier("translation.history")
                    }

                    Text("汉译英")
                        .font(StudyFont.screenTitle)
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)

                    // Source card
                    VStack(alignment: .leading, spacing: 8) {
                        StudyBadge(text: "中文原文", tone: .accent)

                        Text(highlightedSourceText)
                            .font(.system(size: 17))
                            .foregroundStyle(Color.studyInk)
                            .lineSpacing(4)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .studyCard(padding: 16)
                    .padding(.top, 14)
                    .id("translation.source.anchor")

                    // Draft section (above hints, matching Android screen_translation_answer.xml)
                    draftSection(proxy: proxy)
                        .padding(.top, 18)
                        .id("translation.draft.anchor")

                    // Review feedback outer white card
                    reviewSection(proxy: proxy)

                    // On-demand hint white card at bottom
                    hintSection(proxy: proxy)
                        .padding(.top, 20)
                        .id("translation.hint.anchor")
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 28)
            }
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onDisappear {
            viewModel.flushDraft()
        }
        .studyConfirmationAlert($viewModel.confirmation)
        .sheet(item: $viewModel.quoteSnapshot) { snapshot in
            QuoteSnapshotSheet(snapshot: snapshot)
        }
        .sheet(isPresented: $viewModel.isHistoryPresented) {
            TranslationHistorySheet(viewModel: viewModel)
        }
        .sheet(item: $viewModel.selectedRecord) { record in
            TranslationRecordDetailSheet(viewModel: viewModel, record: record)
        }
    }

    private var highlightedSourceText: AttributedString {
        if let inline = viewModel.inlineHighlight, inline.field == .translationSource {
            return viewModel.source.attributedWithMarkerHighlights(inline.ranges)
        }
        return AttributedString(viewModel.source)
    }

    private func draftSection(proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                Text("英文草稿")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.studyMuted)
                Spacer(minLength: 8)
                StudyBadge(text: viewModel.draftWordBadge, tone: .accent)
            }

            InlineHighlightTextEditor(
                text: $viewModel.draft,
                field: .translationDraft,
                inlineHighlight: viewModel.inlineHighlight,
                placeholder: "请输入你的英文译文…",
                isSerif: false,
                minHeight: 150,
                accessibilityId: "translation.draft",
                focusToken: draftFocusToken
            )
            .padding(.top, 6)

            if let validation = viewModel.validationMessage {
                StudyNoticeBanner(message: validation, tone: .danger)
                    .padding(.top, 8)
            }

            Button {
                dismissKeyboard()
                viewModel.submitReview()
                if viewModel.validationMessage == nil {
                    withAnimation {
                        proxy.scrollTo("translation.review.anchor", anchor: .top)
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    if viewModel.review.isPending {
                        ProgressView()
                            .tint(Color.studyOnAccent)
                    }
                    Text(viewModel.review.hasValue || viewModel.review.errorMessage != nil ? "重新提交译文" : "提交译文")
                }
            }
            .buttonStyle(StudyPrimaryButtonStyle())
            .disabled(viewModel.review.isPending)
            .padding(.top, 12)
            .accessibilityIdentifier("translation.submit")
        }
    }

    @ViewBuilder
    private func reviewSection(proxy: ScrollViewProxy) -> some View {
        if viewModel.review.isPending || viewModel.review.errorMessage != nil || viewModel.review.hasValue {
            let feedback = viewModel.review.value

            VStack(alignment: .leading, spacing: 0) {
                Text(feedback?.mode == "demo" ? "演示批改" : "批改反馈")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if viewModel.review.isPending {
                    Text("正在批改本次提交…")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .padding(.top, 8)
                } else if let error = viewModel.review.errorMessage {
                    Text(error)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)

                    if viewModel.review.canRetry {
                        Button("重试") {
                            viewModel.submitReview()
                        }
                        .buttonStyle(TranslationInlineSecondaryButtonStyle())
                        .padding(.top, 10)
                    }

                    Button("返回修改草稿") {
                        focusDraft(proxy: proxy)
                    }
                    .buttonStyle(TranslationInlineSecondaryButtonStyle())
                    .padding(.top, 10)
                } else if let feedback {
                    if feedback.hasScore {
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(alignment: .center, spacing: 8) {
                                Text("四六级预估分")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(Color.studyAccent)
                                Spacer(minLength: 8)
                                Text("\(feedback.estimatedScore) / \(feedback.scoreMax) 分")
                                    .font(.system(size: 22, weight: .medium))
                                    .foregroundStyle(Color.studyAccent)
                                    .monospacedDigit()
                                    .accessibilityIdentifier("translation.score")
                            }

                            if let band = feedback.scoreBand, !band.isEmpty {
                                Text(band)
                                    .font(.system(size: 14))
                                    .foregroundStyle(Color.studyInk)
                                    .lineSpacing(3)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.top, 6)
                            }
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                                .fill(
                                    LinearGradient(
                                        colors: [Color.studyAccentSoft, Color.studySurface],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                                .stroke(Color.studyOutline, lineWidth: 1)
                        )
                        .padding(.top, 10)
                    }

                    Text(feedback.overallFeedback)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)

                    if TextMetrics.trimSpace(viewModel.source) != feedback.sourceText
                        || TextMetrics.trimSpace(viewModel.draft) != feedback.studentTranslation {
                        Text("草稿已修改。下方反馈对应上一次提交。")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .studyToneCard(padding: 8)
                            .padding(.top, 6)
                    }

                    if let ref = feedback.referenceTranslation, !ref.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            StudyBadge(text: "参考译文", tone: .accent)
                            Text(ref)
                                .font(.system(size: 15))
                                .foregroundStyle(Color.studyInk)
                                .lineSpacing(3)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .studyToneCard(padding: 12)
                        .padding(.top, 12)
                    }

                    ForEach(feedback.issues) { issue in
                        ReviewIssueCard(issue: issue) { selected in
                            viewModel.locate(selected)
                            let targetAnchor = selected.target == .source
                                ? "translation.source.anchor"
                                : "translation.draft.anchor"
                            withAnimation {
                                proxy.scrollTo(targetAnchor, anchor: .top)
                            }
                        }
                        .padding(.top, 12)
                    }

                    Button("返回修改草稿") {
                        focusDraft(proxy: proxy)
                    }
                    .buttonStyle(TranslationInlineSecondaryButtonStyle())
                    .padding(.top, 10)

                    if viewModel.canAskTutor {
                        Button {
                            dismissKeyboard()
                            viewModel.askTutor()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 14, weight: .medium))
                                    .accessibilityHidden(true)
                                Text("追问 AI 助教")
                            }
                        }
                        .buttonStyle(StudyPrimaryButtonStyle())
                        .padding(.top, 10)
                        .accessibilityIdentifier("translation.askTutor")
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .studyCard(padding: 16)
            .padding(.top, 20)
            .id("translation.review.anchor")
        }
    }

    private func hintSection(proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("按需查看提示")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.studyMuted)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                hintButton(type: .keyword, identifier: "translation.hint.keyword", proxy: proxy)
                hintButton(type: .phrase, identifier: "translation.hint.phrase", proxy: proxy)
                hintButton(type: .structure, identifier: "translation.hint.structure", proxy: proxy)
            }
            .padding(.top, 8)

            if viewModel.hint.isPending {
                Text("正在获取\(viewModel.activeHintType?.title ?? "")提示…")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .padding(.top, 8)
            } else if let error = viewModel.hint.errorMessage {
                Text(error)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 8)
            } else if let hint = viewModel.hint.value {
                if hint.mode == "demo" {
                    Text("演示提示 · \(hint.type.title)")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .padding(.top, 8)
                }

                VStack(alignment: .leading, spacing: 0) {
                    ForEach(hint.items) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            if !item.title.isEmpty {
                                Text(item.title)
                                    .font(StudyFont.caption)
                                    .foregroundStyle(Color.studyMuted)
                                    .lineSpacing(3)
                            }
                            Text(item.body)
                                .font(StudyFont.caption)
                                .foregroundStyle(Color.studyMuted)
                                .lineSpacing(3)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                        .padding(.bottom, 6)
                    }
                }
                .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 14)
    }

    private func hintButton(type: TranslationHintType, identifier: String, proxy: ScrollViewProxy) -> some View {
        let isActive = viewModel.activeHintType == type
        return Button(type.title) {
            dismissKeyboard()
            viewModel.requestHint(type)
            withAnimation {
                proxy.scrollTo("translation.hint.anchor", anchor: .top)
            }
        }
        .buttonStyle(TranslationHintButtonStyle(isSelected: isActive))
        .disabled(viewModel.hint.isPending)
        .accessibilityIdentifier(identifier)
    }

    private func focusDraft(proxy: ScrollViewProxy) {
        withAnimation {
            proxy.scrollTo("translation.draft.anchor", anchor: .top)
        }
        draftFocusToken = UUID()
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

private struct TranslationTopTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var leading: CGFloat = 4
    var trailing: CGFloat = 10

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyAccent : Color.studyMuted)
            .frame(minHeight: 44)
            .padding(.leading, leading)
            .padding(.trailing, trailing)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.65 : 1.0)
    }
}

private struct TranslationInlineSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyInk : Color.studyMuted)
            .frame(minHeight: StudyLayout.minButtonHeight)
            .padding(.horizontal, 14)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .fill(Color.studySurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.85 : 1.0)
    }
}

private struct TranslationHintButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: isSelected ? .bold : .medium))
            .foregroundStyle(!isEnabled ? Color.studyMuted : (isSelected ? Color.studyAccent : Color.studyInk))
            .lineLimit(1)
            .minimumScaleFactor(0.85)
            .frame(maxWidth: .infinity)
            .frame(minHeight: StudyLayout.minButtonHeight)
            .padding(.horizontal, 6)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .fill(isSelected ? Color.studyAccentSoft : Color.studySurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .stroke(isSelected ? Color.studyAccent : Color.studyOutline, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.85 : 1.0)
    }
}

private struct TranslationHistorySheet: View {
    @Bindable var viewModel: TranslationViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.history.isEmpty {
                    VStack(spacing: 12) {
                        Text("还没有保存的翻译批改记录。")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(viewModel.history) { record in
                            Button {
                                viewModel.isHistoryPresented = false
                                viewModel.selectedRecord = record
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack {
                                        Text(record.promptOrSource)
                                            .font(.system(size: 15, weight: .medium))
                                            .foregroundStyle(Color.studyInk)
                                            .lineLimit(2)
                                        Spacer()
                                        if record.estimatedScore >= 0 {
                                            StudyBadge(text: "\(record.estimatedScore) / 15 分")
                                        }
                                    }
                                    Text(record.draft)
                                        .font(.system(size: 13))
                                        .foregroundStyle(Color.studyMuted)
                                        .lineLimit(2)
                                }
                                .padding(.vertical, 4)
                            }
                            .swipeActions(edge: .trailing) {
                                Button("删除", role: .destructive) {
                                    viewModel.deleteRecord(record)
                                }
                            }
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .background(Color.studyPaper.ignoresSafeArea())
            .navigationTitle("翻译批改历史")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}

private struct TranslationRecordDetailSheet: View {
    let viewModel: TranslationViewModel
    let record: PracticeRecord
    var onRestored: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("中文原文")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color.studyMuted)
                        Text(record.promptOrSource)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.studyInk)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyToneCard()

                    VStack(alignment: .leading, spacing: 6) {
                        Text("提交译文")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color.studyMuted)
                        Text(record.draft)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.studyInk)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyCard()

                    if let feedback = viewModel.feedback(for: record) {
                        if feedback.hasScore {
                            StudyBadge(text: "预估分：\(feedback.estimatedScore) / \(feedback.scoreMax) 分")
                        }
                        Text(feedback.overallFeedback)
                            .font(.system(size: 14))
                            .foregroundStyle(Color.studyInk)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .studyCard()
                    }

                    Button("回填到编辑器") {
                        viewModel.restore(from: record)
                        dismiss()
                        onRestored?()
                    }
                    .buttonStyle(StudyPrimaryButtonStyle())

                    Button("删除此记录", role: .destructive) {
                        viewModel.deleteRecord(record)
                        dismiss()
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                }
                .padding(20)
            }
            .background(Color.studyPaper.ignoresSafeArea())
            .navigationTitle("历史批改详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}

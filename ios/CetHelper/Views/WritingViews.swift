import SwiftUI
import UIKit

struct WritingView: View {
    let scope: SessionScope
    @Bindable var viewModel: WritingViewModel
    @State private var essayFocusToken: UUID? = nil

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
                                Text("返回")
                            }
                        }
                        .buttonStyle(WritingTopTextButtonStyle(leading: 4, trailing: 10))
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
                        .buttonStyle(WritingTopTextButtonStyle(leading: 8, trailing: 4))
                        .accessibilityIdentifier("writing.history")
                    }

                    Text("写作练习")
                        .font(StudyFont.screenTitle)
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)

                    HStack(spacing: 12) {
                        Button("使用演示草稿") {
                            dismissKeyboard()
                            viewModel.useDemo()
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .accessibilityIdentifier("writing.useDemo")

                        Button("清空本练习") {
                            dismissKeyboard()
                            viewModel.clearAll()
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                    }
                    .padding(.top, 10)

                    Text("作文题目")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.studyMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 14)

                    InlineHighlightTextEditor(
                        text: $viewModel.prompt,
                        field: .writingPrompt,
                        inlineHighlight: viewModel.inlineHighlight,
                        placeholder: "请输入或粘贴英文写作题目…",
                        isSerif: false,
                        minHeight: 84,
                        accessibilityId: "writing.prompt"
                    )
                    .padding(.top, 6)

                    HStack(alignment: .center, spacing: 8) {
                        Text("英文作文")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Color.studyMuted)
                        Spacer(minLength: 8)
                        StudyBadge(text: viewModel.wordBadge, tone: .accent)
                            .accessibilityIdentifier("writing.wordBadge")
                    }
                    .padding(.top, 16)
                    .id("writing.essay.anchor")

                    InlineHighlightTextEditor(
                        text: $viewModel.essay,
                        field: .writingEssay,
                        inlineHighlight: viewModel.inlineHighlight,
                        placeholder: "请输入你的英文作文…",
                        isSerif: false,
                        minHeight: 200,
                        accessibilityId: "writing.essay",
                        focusToken: essayFocusToken
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
                                proxy.scrollTo("writing.status.anchor", anchor: .top)
                            }
                        }
                    } label: {
                        HStack(spacing: 8) {
                            if viewModel.review.isPending {
                                ProgressView()
                                    .tint(Color.studyOnAccent)
                            }
                            Text(viewModel.review.hasValue || viewModel.review.errorMessage != nil ? "重新提交作文" : "提交作文")
                        }
                    }
                    .buttonStyle(StudyPrimaryButtonStyle())
                    .disabled(viewModel.review.isPending)
                    .padding(.top, 12)
                    .accessibilityIdentifier("writing.submit")

                    requestStatusSection
                        .id("writing.status.anchor")

                    reviewSection(proxy: proxy)
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
            WritingHistorySheet(viewModel: viewModel)
        }
        .sheet(item: $viewModel.selectedRecord) { record in
            WritingRecordDetailSheet(viewModel: viewModel, record: record)
        }
    }

    @ViewBuilder
    private var requestStatusSection: some View {
        if viewModel.review.isPending {
            Text("正在批改本次提交…")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)
        } else if let error = viewModel.review.errorMessage {
            Text(error)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)

            if viewModel.review.canRetry {
                Button("重试") {
                    viewModel.submitReview()
                }
                .buttonStyle(WritingInlineSecondaryButtonStyle())
                .padding(.top, 6)
            }
        }
    }

    @ViewBuilder
    private func reviewSection(proxy: ScrollViewProxy) -> some View {
        if let feedback = viewModel.review.value {
            VStack(alignment: .leading, spacing: 0) {
                Text(feedback.mode == "demo" ? "演示批改" : "写作反馈")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)

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
                                .accessibilityIdentifier("writing.score")
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

                if TextMetrics.trimSpace(viewModel.prompt) != feedback.prompt
                    || TextMetrics.trimSpace(viewModel.essay) != feedback.essay {
                    Text("当前输入已修改。下方反馈对应上一次已批改的作文。")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .studyToneCard(padding: 8)
                        .padding(.top, 6)
                }

                ForEach(feedback.issues) { issue in
                    ReviewIssueCard(issue: issue) { selected in
                        viewModel.locate(selected)
                        withAnimation {
                            proxy.scrollTo("writing.essay.anchor", anchor: .top)
                        }
                    } footer: {
                        if let followUp = viewModel.followUp, followUp.issue.index == issue.index {
                            WritingFollowUpPanel(followUp: followUp)
                        }
                    }
                    .padding(.top, 12)
                }

                Button("返回修改作文") {
                    withAnimation {
                        proxy.scrollTo("writing.essay.anchor", anchor: .top)
                    }
                    essayFocusToken = UUID()
                }
                .buttonStyle(WritingInlineSecondaryButtonStyle())
                .padding(.top, 12)

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
                    .accessibilityIdentifier("writing.askTutor")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .studyCard(padding: 16)
            .padding(.top, 20)
            .id("writing.review.anchor")
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

private struct WritingFollowUpPanel: View {
    @Bindable var followUp: WritingFollowUp

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("针对性小练习")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Color.studyInk)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(followUp.exercise.instruction)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 2)

            Text(followUp.exercise.item)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyInk)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 4)

            WritingAttemptSection(
                attempt: followUp.exerciseAttempt,
                label: "你的答案（本轮暂存）",
                inputId: "followup.exercise.input",
                submitId: "followup.exercise.submit",
                isBlocked: false,
                onSubmit: {
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    followUp.submitExercise()
                }
            )

            Button {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                followUp.requestRetest()
            } label: {
                Text("换题复测")
            }
            .buttonStyle(WritingTopTextButtonStyle(leading: 0, trailing: 8))
            .disabled(!followUp.canRequestRetest)
            .padding(.top, 6)
            .accessibilityIdentifier("followup.retest.request")

            if followUp.taskRequest.isPending {
                Text("正在生成复测题…")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if let error = followUp.taskRequest.errorMessage {
                Text(error)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let task = followUp.retestTask {
                VStack(alignment: .leading, spacing: 0) {
                    Text("新情境复测")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text(task.instruction)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 2)

                    Text(task.item)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyInk)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 4)

                    WritingAttemptSection(
                        attempt: followUp.retestAttempt,
                        label: "你的复测答案",
                        inputId: "followup.retest.input",
                        submitId: "followup.retest.submit",
                        isBlocked: !followUp.canSubmitRetest,
                        onSubmit: {
                            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                            followUp.submitRetest()
                        }
                    )
                }
                .padding(.top, 12)
            }
        }
        .padding(.top, 12)
    }
}

private struct WritingAttemptSection: View {
    @Bindable var attempt: WritingAttempt
    let label: String
    let inputId: String
    let submitId: String
    let isBlocked: Bool
    let onSubmit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 8)

            // Must remain TextField(..., axis: .vertical) so XCUITest app.textFields queries keep working.
            TextField(label, text: $attempt.input, axis: .vertical)
                .lineLimit(2...5)
                .font(.system(size: 16))
                .foregroundStyle(Color.studyInk)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(minHeight: StudyLayout.minInputHeight, alignment: .topLeading)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .fill(Color.studySurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .stroke(Color.studyOutline, lineWidth: 1)
                )
                .padding(.top, 4)
                .accessibilityIdentifier(inputId)

            if let validation = attempt.validationMessage {
                Text(validation)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.studyDanger)
                    .padding(.top, 6)
            }

            Button("提交答案") {
                onSubmit()
            }
            .buttonStyle(StudyPrimaryButtonStyle())
            .disabled(isBlocked || !attempt.canSubmit || attempt.request.isPending)
            .padding(.top, 8)
            .accessibilityIdentifier(submitId)

            if attempt.request.isPending {
                Text("正在判答…")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)
            } else if let error = attempt.request.errorMessage {
                Text(error)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)
            }

            if let assessment = attempt.assessment {
                VStack(alignment: .leading, spacing: 0) {
                    Text(assessment.verdict.label)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color.studyAccent)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("followup.verdict")

                    Text("上次提交的答案")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)

                    Text(assessment.answer.attributedWithMarkerHighlights(assessment.matches))
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyInk)
                        .lineSpacing(3)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .studyToneCard(padding: 10)
                        .padding(.top, 4)

                    if let notice = assessment.candidateNotice {
                        Text(notice)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 4)
                    }

                    Text(assessment.feedback)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)

                    Text("下一步：\(assessment.nextStep)")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.studyInk)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .studyCard(padding: 14)
                .padding(.top, 10)
            }
        }
    }
}

private struct WritingTopTextButtonStyle: ButtonStyle {
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

private struct WritingInlineSecondaryButtonStyle: ButtonStyle {
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

private struct WritingHistorySheet: View {
    @Bindable var viewModel: WritingViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.history.isEmpty {
                    VStack(spacing: 12) {
                        Text("还没有保存的写作批改记录。")
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
            .navigationTitle("写作批改历史")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}

private struct WritingRecordDetailSheet: View {
    let viewModel: WritingViewModel
    let record: PracticeRecord
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("作文题目")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color.studyMuted)
                        Text(record.promptOrSource)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.studyInk)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyToneCard()

                    VStack(alignment: .leading, spacing: 6) {
                        Text("提交作文")
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

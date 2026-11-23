import SwiftUI
import UIKit

struct TutorView: View {
    @Bindable var viewModel: TutorViewModel
    var asTab: Bool = true
    @Environment(\.dismiss) private var dismiss

    @State private var throttledStreamingText: String = ""
    @State private var throttleTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !asTab {
                StudyTopBar(onBack: {
                    dismissKeyboard()
                    dismiss()
                })
                .padding(.horizontal, 16)
                .padding(.top, 4)
            }

            StudyHeading("AI 助教")
                .padding(.horizontal, 20)
                .padding(.top, asTab ? 12 : 6)

            contextCard
                .padding(.horizontal, 20)
                .padding(.top, 10)

            messageStreamSection
                .padding(.top, 10)

            promptChipsSection
                .padding(.top, 6)

            bottomInputBar
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 10)
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .studyConfirmationAlert($viewModel.confirmation)
        .onChange(of: viewModel.streamingText) { _, newValue in
            syncThrottledStreamingText(newValue)
        }
        .onChange(of: viewModel.isGenerating) { _, generating in
            if !generating {
                throttleTask?.cancel()
                throttleTask = nil
                throttledStreamingText = ""
            }
        }
        .onDisappear {
            throttleTask?.cancel()
            throttleTask = nil
        }
    }

    private var contextCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                StudyBadge(text: viewModel.context.type.badgeTitle, tone: .accent)
                    .accessibilityIdentifier("tutor.context.type")

                Spacer(minLength: 8)

                if viewModel.context.type != .free {
                    Button("清除上下文") {
                        viewModel.clearContext()
                    }
                    .buttonStyle(StudyTextButtonStyle(minHeight: 30, horizontalPadding: 6, fontSize: 13))
                    .accessibilityIdentifier("tutor.context.clear")
                }
            }

            Text(viewModel.context.displayTitle)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.studyInk)
                .lineLimit(1)
                .accessibilityIdentifier("tutor.context.title")

            Text(viewModel.context.summary())
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineLimit(2)
                .lineSpacing(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 12)
    }

    private var messageStreamSection: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if viewModel.messages.isEmpty && !viewModel.isGenerating {
                        Text("选择下方快捷提问，或输入你的问题开始对话。")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .studyCard(padding: 16)
                    }

                    ForEach(viewModel.messages) { message in
                        messageBubble(message)
                    }

                    if viewModel.isGenerating {
                        streamingBubble
                    }

                    if let error = viewModel.errorMessage {
                        StudyNoticeBanner(message: error, tone: .danger)
                    }

                    Color.clear
                        .frame(height: 1)
                        .id("tutor.bottom")
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 4)
            }
            .accessibilityIdentifier("tutor.messages")
            .onChange(of: viewModel.messages.count) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: viewModel.isGenerating) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: throttledStreamingText) { _, _ in
                scrollToBottom(proxy)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private func messageBubble(_ message: TutorMessage) -> some View {
        if message.role == .user {
            HStack {
                Spacer(minLength: 44)
                Text(message.content)
                    .font(.system(size: 15))
                    .foregroundStyle(Color.studyOnAccent)
                    .lineSpacing(3)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                            .fill(Color.studyAccent)
                    )
            }
        } else {
            HStack {
                assistantBubbleContainer(mode: message.mode) {
                    Text(message.content)
                        .font(.system(size: 15))
                        .foregroundStyle(Color.studyInk)
                        .lineSpacing(4)
                        .textSelection(.enabled)
                }
                Spacer(minLength: 36)
            }
        }
    }

    private var streamingBubble: some View {
        HStack {
            assistantBubbleContainer(mode: viewModel.streamMode) {
                if throttledStreamingText.isEmpty {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在思考…")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                    }
                } else {
                    Text(throttledStreamingText)
                        .font(.system(size: 15))
                        .foregroundStyle(Color.studyInk)
                        .lineSpacing(4)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 36)
        }
    }

    private func assistantBubbleContainer<Content: View>(
        mode: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if !mode.isEmpty {
                StudyBadge(
                    text: mode == "demo" ? "演示助教" : "AI 助教",
                    tone: mode == "demo" ? .warning : .accent
                )
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 12)
    }

    @ViewBuilder
    private var promptChipsSection: some View {
        if !viewModel.isGenerating && !viewModel.followUps.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(viewModel.followUps.enumerated()), id: \.offset) { index, prompt in
                        StudyFilterChip(title: prompt, isSelected: true) {
                            dismissKeyboard()
                            viewModel.send(prompt)
                        }
                        .accessibilityIdentifier("tutor.followUp.\(index)")
                    }
                }
                .padding(.horizontal, 20)
            }
        }

        if !viewModel.quickPrompts.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(viewModel.quickPrompts.enumerated()), id: \.offset) { index, prompt in
                        StudyFilterChip(
                            title: prompt,
                            isSelected: false,
                            isDisabled: viewModel.isGenerating
                        ) {
                            dismissKeyboard()
                            viewModel.send(prompt)
                        }
                        .accessibilityIdentifier("tutor.quickPrompt.\(index)")
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private var bottomInputBar: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Button {
                viewModel.requestClearSession()
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(viewModel.canClear ? Color.studyDanger : Color.studyMuted)
                    .frame(width: 40, height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                            .fill(Color.studySurface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                            .stroke(Color.studyOutline, lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canClear)
            .accessibilityLabel("清空会话")
            .accessibilityIdentifier("tutor.clear")

            TextField("向 AI 助教提问…", text: $viewModel.draftInput, axis: .vertical)
                .lineLimit(1...4)
                .font(.system(size: 15))
                .foregroundStyle(Color.studyInk)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(minHeight: 44)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                        .fill(Color.studySurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                        .stroke(Color.studyOutline, lineWidth: 1)
                )
                .accessibilityIdentifier("tutor.input")

            if viewModel.isGenerating {
                Button("停止") {
                    viewModel.stop()
                }
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.studyDanger)
                .frame(minWidth: 60, minHeight: 44)
                .padding(.horizontal, 10)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                        .fill(Color.studyDangerSoft)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                        .stroke(Color.studyDanger.opacity(0.45), lineWidth: 1)
                )
                .accessibilityIdentifier("tutor.stop")
            } else {
                Button("发送") {
                    dismissKeyboard()
                    viewModel.send()
                }
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(viewModel.canSend ? Color.studyOnAccent : Color.studyMuted)
                .frame(minWidth: 60, minHeight: 44)
                .padding(.horizontal, 10)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                        .fill(viewModel.canSend ? Color.studyAccent : Color.studyTone)
                )
                .disabled(!viewModel.canSend)
                .accessibilityIdentifier("tutor.send")
            }
        }
    }

    private func syncThrottledStreamingText(_ latest: String) {
        if latest.isEmpty {
            throttleTask?.cancel()
            throttleTask = nil
            throttledStreamingText = ""
            return
        }
        if throttledStreamingText.isEmpty {
            throttledStreamingText = latest
            return
        }
        guard throttleTask == nil else { return }
        throttleTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(50))
            guard !Task.isCancelled else { return }
            throttledStreamingText = viewModel.streamingText
            throttleTask = nil
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.15)) {
            proxy.scrollTo("tutor.bottom", anchor: .bottom)
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

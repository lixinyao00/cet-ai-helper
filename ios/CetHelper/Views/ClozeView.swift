import SwiftUI
import UIKit

struct ClozeView: View {
    @Bindable var viewModel: ClozeViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if viewModel.isFinished {
                summaryView
            } else {
                practiceView
            }
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task {
            if viewModel.round == nil && viewModel.emptyMessage == nil {
                viewModel.start()
            }
        }
    }

    private var practiceView: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                Button {
                    dismissKeyboard()
                    dismiss()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.left")
                            .font(.system(size: 15, weight: .medium))
                        Text("返回生词本")
                    }
                }
                .buttonStyle(ClozeTopTextButtonStyle())
                .accessibilityIdentifier("nav.back")

                Spacer(minLength: 8)

                if viewModel.round != nil, !viewModel.progressText.isEmpty {
                    StudyBadge(text: viewModel.progressText, tone: .accent)
                }
            }
            .padding(.top, 4)

            if viewModel.round != nil {
                StudyProgressBar(value: viewModel.progress, height: 6)
                    .padding(.top, 4)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("原句填空")
                        .font(StudyFont.screenTitle)
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 12)

                    if let skipped = viewModel.skippedNotice {
                        StudyNoticeBanner(message: skipped, tone: .warning)
                            .padding(.top, 12)
                    }

                    if let empty = viewModel.emptyMessage, viewModel.round == nil {
                        emptyView(message: empty)
                            .padding(.top, 16)
                    } else if let question = viewModel.current {
                        questionView(question)
                    } else {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在准备原句填空…")
                                .font(StudyFont.caption)
                                .foregroundStyle(Color.studyMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(32)
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .padding(.horizontal, 20)
    }

    private func emptyView(message: String) -> some View {
        VStack(spacing: 14) {
            Text(message)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
        }
        .frame(maxWidth: .infinity)
        .studyCard(padding: 24)
    }

    private func questionView(_ question: ClozeQuestion) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(viewModel.displayTitle)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundStyle(Color.studyMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("cloze.title")

                Text(viewModel.displaySentence)
                    .font(StudyFont.serif(22))
                    .foregroundStyle(Color.studyInk)
                    .lineSpacing(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 10)
                    .accessibilityIdentifier("cloze.sentence")
            }
            .studyCard(padding: 18)
            .padding(.top, 16)

            if !viewModel.isAnswered {
                entrySection(question)
                    .padding(.top, 14)
            } else {
                reviewSection(question)
                    .padding(.top, 14)
            }
        }
    }

    private func entrySection(_ question: ClozeQuestion) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("输入单词或词组", text: $viewModel.input)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 16))
                .foregroundStyle(Color.studyInk)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(minHeight: StudyLayout.minInputHeight)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .fill(Color.studySurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .stroke(Color.studyOutline, lineWidth: 1)
                )
                .accessibilityIdentifier("cloze.input")
                .onSubmit {
                    dismissKeyboard()
                    viewModel.submit()
                }

            if let inputError = viewModel.inputError {
                Text(inputError)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.studyDanger)
                    .padding(.top, 6)
            }

            if viewModel.isHintShown {
                Text("提示：\(question.hint)")
                    .font(StudyFont.hintMono)
                    .foregroundStyle(Color.studyAccent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyToneCard(padding: 10)
                    .padding(.top, 8)
                    .accessibilityIdentifier("cloze.hint")
            }

            Button("提交答案") {
                dismissKeyboard()
                viewModel.submit()
            }
            .buttonStyle(StudyPrimaryButtonStyle())
            .padding(.top, 12)
            .accessibilityIdentifier("cloze.submit")

            HStack(spacing: 12) {
                Button {
                    viewModel.showHint()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "lightbulb")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Color.studyAccent)
                        Text("首字母提示")
                    }
                }
                .buttonStyle(StudySecondaryButtonStyle())
                .accessibilityIdentifier("cloze.hintButton")

                Button("查看答案") {
                    dismissKeyboard()
                    viewModel.reveal()
                }
                .buttonStyle(StudySecondaryButtonStyle())
                .accessibilityIdentifier("cloze.reveal")
            }
            .padding(.top, 8)
        }
    }

    private func reviewSection(_ question: ClozeQuestion) -> some View {
        let isLast = (viewModel.round?.currentIndex ?? 0) + 1 >= (viewModel.round?.questions.count ?? 0)

        return VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(viewModel.outcomeText)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Color.studyAccent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("cloze.feedback")

                Text(question.answer)
                    .font(StudyFont.serif(23))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 8)

                Text(question.favorite.meaning)
                    .font(.system(size: 16))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)

                Text(question.favorite.sentence)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyToneCard(padding: 10)
                    .padding(.top, 10)
            }
            .studyCard(padding: 18)

            Button(isLast ? "完成练习" : "下一题") {
                viewModel.next()
            }
            .buttonStyle(StudyPrimaryButtonStyle())
            .padding(.top, 12)
            .accessibilityIdentifier("cloze.next")

            Button(viewModel.currentSourceButtonTitle) {
                viewModel.openCurrentSource()
            }
            .buttonStyle(StudySecondaryButtonStyle())
            .padding(.top, 8)
            .accessibilityIdentifier("cloze.backToReader")
        }
    }

    private var summaryView: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)

                    Text("练习完成")
                        .font(StudyFont.screenTitle)
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text(viewModel.summaryText)
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color.studyInk)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(20)
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
                        .padding(.top, 16)
                        .accessibilityIdentifier("cloze.summary")

                    if viewModel.hasWrongQuestions {
                        Button("仅重练错题") {
                            viewModel.retryWrong()
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .padding(.top, 16)
                        .accessibilityIdentifier("cloze.retryWrong")
                    }

                    Button("返回生词本") {
                        dismiss()
                    }
                    .buttonStyle(StudyPrimaryButtonStyle())
                    .padding(.top, 12)
                    .accessibilityIdentifier("cloze.backToVocab")

                    Spacer(minLength: 0)
                }
                .padding(24)
                .frame(minHeight: geo.size.height)
            }
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

private struct ClozeTopTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyAccent : Color.studyMuted)
            .frame(minHeight: 44)
            .padding(.leading, 4)
            .padding(.trailing, 10)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.65 : 1.0)
    }
}

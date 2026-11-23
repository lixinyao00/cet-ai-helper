import SwiftUI

struct SentenceAnalysisSheet: View {
    let viewModel: ReaderViewModel
    let maxHeight: CGFloat
    @State private var highlightedChunkRange: TextRange?

    var body: some View {
        let sentence = viewModel.analysis.value?.sentence ?? viewModel.selection?.sentence ?? ""

        VStack(alignment: .leading, spacing: 0) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(Color.studyOutline)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.bottom, 8)

            HStack(alignment: .center, spacing: 4) {
                Text("整句分析")
                    .font(StudyFont.serif(22))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onTapGesture {
                        if !sentence.isEmpty {
                            viewModel.speak(sentence)
                        }
                    }

                Button("返回释义") {
                    viewModel.showLookupPanel()
                }
                .buttonStyle(StudyTextButtonStyle(minHeight: 40, horizontalPadding: 8))

                Button("关闭") {
                    viewModel.closePanel()
                }
                .buttonStyle(StudyTextButtonStyle(minHeight: 40, horizontalPadding: 6))
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if viewModel.analysis.value?.mode == "demo" {
                        StudyBadge(text: "演示分析")
                            .padding(.top, 4)
                    }

                    if !sentence.isEmpty {
                        Text(highlightedSentence(sentence))
                            .font(StudyFont.serif(17))
                            .foregroundStyle(Color.studyInk)
                            .lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .studyToneCard(padding: 12)
                            .onTapGesture {
                                viewModel.speak(sentence)
                            }
                            .padding(.top, 8)
                    }

                    if viewModel.analysis.isPending {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在分析整句…")
                                .font(StudyFont.caption)
                                .foregroundStyle(Color.studyMuted)
                        }
                        .padding(.top, 10)
                    } else if let error = viewModel.analysis.errorMessage {
                        Text(error)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 10)
                        if viewModel.analysis.canRetry {
                            Button("重试") {
                                viewModel.retryAnalysis()
                            }
                            .buttonStyle(StudyPrimaryButtonStyle())
                            .padding(.top, 8)
                        }
                    } else if let analysis = viewModel.analysis.value {
                        Text("中文翻译")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Color.studyAccent)
                            .padding(.top, 14)

                        Text(analysis.translation)
                            .font(.system(size: 16))
                            .foregroundStyle(Color.studyInk)
                            .padding(.top, 4)

                        Text("句子主干")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Color.studyAccent)
                            .padding(.top, 14)

                        Text(analysis.structure)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.studyInk)
                            .lineSpacing(3)
                            .padding(.top, 4)

                        Text("点片段可在原句中定位")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Color.studyAccent)
                            .padding(.top, 14)

                        VStack(spacing: 6) {
                            ForEach(analysis.chunks) { chunk in
                                let isSelected = highlightedChunkRange == chunk.range
                                Button {
                                    highlightedChunkRange = chunk.range
                                } label: {
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(chunk.text)
                                            .font(StudyFont.serif(17))
                                            .foregroundStyle(Color.studyInk)
                                            .multilineTextAlignment(.leading)

                                        Text(chunk.role)
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(Color.studyAccent)
                                            .padding(.top, 3)

                                        Text(chunk.explanation)
                                            .font(StudyFont.caption)
                                            .foregroundStyle(Color.studyMuted)
                                            .multilineTextAlignment(.leading)
                                            .padding(.top, 2)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(10)
                                    .background(
                                        RoundedRectangle(cornerRadius: StudyLayout.toneRadius, style: .continuous)
                                            .fill(isSelected ? Color.studyAccentSoft : Color.studyTone)
                                    )
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("reader.analysis.chunk.\(chunk.index)")
                            }
                        }
                        .padding(.top, 4)

                        Button {
                            viewModel.askTutorForSentence()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 14, weight: .medium))
                                    .accessibilityHidden(true)
                                Text("追问 AI 助教")
                            }
                        }
                        .buttonStyle(StudyPrimaryButtonStyle())
                        .padding(.top, 12)
                        .accessibilityIdentifier("reader.analysis.askTutor")
                    }
                }
                .padding(.bottom, 6)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: maxHeight)
        .background(
            UnevenRoundedRectangle(
                topLeadingRadius: StudyLayout.sheetRadius,
                bottomLeadingRadius: 0,
                bottomTrailingRadius: 0,
                topTrailingRadius: StudyLayout.sheetRadius,
                style: .continuous
            )
            .fill(Color.studySurfaceElevated)
            .ignoresSafeArea(edges: .bottom)
        )
        .overlay(alignment: .top) {
            UnevenRoundedRectangle(
                topLeadingRadius: StudyLayout.sheetRadius,
                bottomLeadingRadius: 0,
                bottomTrailingRadius: 0,
                topTrailingRadius: StudyLayout.sheetRadius,
                style: .continuous
            )
            .stroke(Color.studyOutline, lineWidth: 1)
            .ignoresSafeArea(edges: .bottom)
        }
        .onChange(of: viewModel.analysis.value) { _, _ in
            highlightedChunkRange = nil
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reader.analysis")
    }

    private func highlightedSentence(_ sentence: String) -> AttributedString {
        guard let range = highlightedChunkRange else {
            return AttributedString(sentence)
        }
        return sentence.attributedWithMarkerHighlights([range])
    }
}

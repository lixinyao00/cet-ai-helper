import SwiftUI
import UIKit

struct DictionaryView: View {
    @Bindable var viewModel: DictionaryViewModel
    var asTab: Bool = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !asTab {
                StudyTopBar(onBack: {
                    dismissKeyboard()
                    dismiss()
                })
                .padding(.top, 4)
            }

            StudyHeading("查词")
                .padding(.top, asTab ? 12 : 6)

            searchBar
                .padding(.top, 12)

            levelFilterRow
                .padding(.top, 10)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if viewModel.showsSuggestions {
                        suggestionsSection
                    }

                    if !viewModel.history.isEmpty {
                        historySection
                    }

                    if viewModel.isLoading {
                        loadingCard
                    } else if let error = viewModel.errorMessage {
                        StudyNoticeBanner(message: error, tone: .danger)
                    }

                    if let entry = viewModel.current {
                        entryDetailCard(entry)
                    } else if viewModel.isEmptyState {
                        emptyStateCard
                    }
                }
                .padding(.top, 14)
                .padding(.bottom, 24)
            }
        }
        .padding(.horizontal, 20)
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            viewModel.loadHistory()
            viewModel.refreshFavoriteState()
        }
        .studyConfirmationAlert($viewModel.confirmation)
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15))
                .foregroundStyle(Color.studyMuted)
                .accessibilityHidden(true)

            TextField("输入英文单词或词组…", text: $viewModel.query)
                .font(.system(size: 15))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .foregroundStyle(Color.studyInk)
                .onSubmit {
                    dismissKeyboard()
                    viewModel.lookup()
                }
                .accessibilityIdentifier("dict.search")

            if !viewModel.query.isEmpty {
                Button {
                    viewModel.clearQuery()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(Color.studyMuted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索")
                .accessibilityIdentifier("dict.clear")
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 46)
        .background(
            RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                .fill(Color.studySurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                .stroke(Color.studyOutline, lineWidth: 1)
        )
    }

    private var levelFilterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(DictionaryLevelFilter.allCases) { filter in
                    StudyFilterChip(
                        title: filter.title,
                        isSelected: viewModel.levelFilter == filter
                    ) {
                        viewModel.selectLevelFilter(filter)
                    }
                    .accessibilityIdentifier(filter.accessibilityIdentifier)
                }
            }
        }
    }

    private var suggestionsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(viewModel.suggestions.enumerated()), id: \.element.id) { index, suggestion in
                Button {
                    dismissKeyboard()
                    viewModel.selectSuggestion(suggestion)
                } label: {
                    HStack(alignment: .center, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(suggestion.word)
                                    .font(StudyFont.serif(17, weight: .medium))
                                    .foregroundStyle(Color.studyInk)

                                let meta = [suggestion.phonetic, suggestion.partOfSpeech]
                                    .filter { !$0.isEmpty }
                                    .joined(separator: "  ")
                                if !meta.isEmpty {
                                    Text(meta)
                                        .font(.system(size: 12))
                                        .foregroundStyle(Color.studyMuted)
                                }
                            }

                            if !suggestion.meaning.isEmpty {
                                Text(suggestion.meaning)
                                    .font(.system(size: 13))
                                    .foregroundStyle(Color.studyMuted)
                                    .lineLimit(1)
                            }
                        }

                        Spacer(minLength: 6)

                        if !suggestion.cetLevel.isEmpty {
                            StudyBadge(text: suggestion.cetLevel, tone: .accent)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 10)
                    .padding(.horizontal, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("dict.suggestion.\(index)")

                if index + 1 < viewModel.suggestions.count {
                    Divider()
                        .overlay(Color.studyOutline)
                        .padding(.horizontal, 12)
                }
            }
        }
        .background(
            RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                .fill(Color.studySurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                .stroke(Color.studyOutline, lineWidth: 1)
        )
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center) {
                Text("最近查词")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.studyMuted)

                Spacer()

                Button("清空") {
                    viewModel.requestClearHistory()
                }
                .buttonStyle(StudyDangerTextButtonStyle(minHeight: 32, horizontalPadding: 6, fontSize: 13))
                .accessibilityIdentifier("dict.history.clear")
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(viewModel.history.enumerated()), id: \.element.id) { index, item in
                        Button {
                            dismissKeyboard()
                            viewModel.selectHistory(item)
                        } label: {
                            Text(item.word)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Color.studyInk)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(
                                    RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                                        .fill(Color.studyTone)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                                        .stroke(Color.studyOutline, lineWidth: 1)
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("dict.history.\(index)")
                    }
                }
            }
        }
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在查询释义…")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 16)
    }

    private var emptyStateCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("输入英文单词或词组，支持四六级联想与 AI 助教追问。")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 18)
    }

    private func entryDetailCard(_ entry: DictionaryEntry) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                Text(entry.displayHeading)
                    .font(StudyFont.serif(24, weight: .medium))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("dict.word")

                Button {
                    viewModel.speak(entry.word)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "speaker.wave.2.fill")
                            .font(.system(size: 14, weight: .medium))
                            .accessibilityHidden(true)
                        Text("朗读")
                    }
                }
                .buttonStyle(StudyTextButtonStyle(minHeight: 38, horizontalPadding: 8))
                .accessibilityIdentifier("dict.speak")
            }

            if !entry.phonetic.isEmpty || !entry.partOfSpeech.isEmpty || !entry.cetLevel.isEmpty || entry.isDemo {
                HStack(spacing: 8) {
                    if !entry.phonetic.isEmpty {
                        Text(entry.phonetic)
                            .font(.system(size: 14))
                            .foregroundStyle(Color.studyMuted)
                    }
                    if !entry.partOfSpeech.isEmpty {
                        StudyBadge(text: entry.partOfSpeech, tone: .muted)
                    }
                    if !entry.cetLevel.isEmpty {
                        StudyBadge(text: entry.cetLevel, tone: .accent)
                    }
                    if entry.isDemo {
                        StudyBadge(text: "演示释义", tone: .warning)
                    }
                }
                .padding(.top, 6)
            }

            Text(entry.meaning)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Color.studyInk)
                .padding(.top, 10)

            if !entry.explanation.isEmpty {
                Text(entry.explanation)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(4)
                    .padding(.top, 8)
            }

            if !entry.exampleSentence.isEmpty || !entry.exampleTranslation.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    StudyBadge(text: "双语例句", tone: .accent)

                    if !entry.exampleSentence.isEmpty {
                        Text(entry.exampleSentence)
                            .font(StudyFont.serif(16))
                            .foregroundStyle(Color.studyInk)
                            .lineSpacing(3)
                            .textSelection(.enabled)
                    }

                    if !entry.exampleTranslation.isEmpty {
                        Text(entry.exampleTranslation)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .studyToneCard(padding: 12)
                .padding(.top, 12)
            }

            HStack(spacing: 12) {
                Button(viewModel.isFavorite ? "已在生词本" : "加入生词本") {
                    viewModel.toggleFavorite()
                }
                .buttonStyle(StudySecondaryButtonStyle())
                .accessibilityIdentifier("dict.favorite")

                Button {
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
                .accessibilityIdentifier("dict.askTutor")
            }
            .padding(.top, 14)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 16)
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

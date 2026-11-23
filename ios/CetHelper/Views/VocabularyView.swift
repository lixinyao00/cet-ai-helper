import SwiftUI

struct VocabularyView: View {
    @Bindable var viewModel: VocabularyViewModel
    var asTab: Bool = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !asTab {
                StudyTopBar(onBack: { dismiss() }) {
                    StudyBadge(text: viewModel.username)
                }
                .padding(.top, 4)
            }

            HStack(alignment: .center, spacing: 8) {
                StudyHeading("生词本")
                    .frame(maxWidth: .infinity, alignment: .leading)
                if asTab {
                    StudyBadge(text: viewModel.username)
                }
            }
            .padding(.top, asTab ? 12 : 6)

            Text("共 \(viewModel.totalCount) 词 · 待复习 \(viewModel.dueCount) · 已掌握 \(viewModel.masteredCount)")
                .font(.system(size: 14))
                .foregroundStyle(Color.studyAccent)
                .padding(.top, 4)

            Button("原句填空") {
                viewModel.startCloze()
            }
            .buttonStyle(StudyPrimaryButtonStyle())
            .disabled(!viewModel.canStartCloze)
            .padding(.top, 12)
            .accessibilityIdentifier("vocab.startCloze")

            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.studyMuted)
                TextField("搜索生词、释义或原句…", text: $viewModel.query)
                    .font(.system(size: 14))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundStyle(Color.studyInk)
                    .accessibilityIdentifier("vocab.search")
                if !viewModel.query.isEmpty {
                    Button {
                        viewModel.query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(Color.studyMuted)
                    }
                }
            }
            .padding(.horizontal, 16)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .fill(Color.studySurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
            .padding(.top, 12)

            filterChipsSection
                .padding(.top, 10)

            if viewModel.filteredFavorites.isEmpty {
                Text(viewModel.emptyMessage ?? "还没有收藏。请先到阅读页收藏单词或词组。")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .padding(.top, 14)
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(viewModel.filteredFavorites.enumerated()), id: \.element.id) { index, favorite in
                            favoriteRow(favorite, index: index)
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                }
            }
        }
        .padding(.horizontal, 20)
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            viewModel.refresh()
        }
        .studyConfirmationAlert($viewModel.confirmation)
    }

    private var filterChipsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(VocabularyLevelFilter.allCases) { filter in
                        StudyFilterChip(
                            title: filter.title,
                            isSelected: viewModel.levelFilter == filter
                        ) {
                            viewModel.levelFilter = filter
                        }
                        .accessibilityIdentifier(filter.accessibilityIdentifier)
                    }
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(VocabularySourceFilter.allCases) { filter in
                        StudyFilterChip(
                            title: filter.title,
                            isSelected: viewModel.sourceFilter == filter
                        ) {
                            viewModel.sourceFilter = filter
                        }
                        .accessibilityIdentifier(filter.accessibilityIdentifier)
                    }
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(VocabularyMasteryFilter.allCases) { filter in
                        StudyFilterChip(
                            title: filter.title,
                            isSelected: viewModel.masteryFilter == filter
                        ) {
                            viewModel.masteryFilter = filter
                        }
                        .accessibilityIdentifier(filter.accessibilityIdentifier)
                    }
                }
            }
        }
    }

    private func favoriteRow(_ favorite: Favorite, index: Int) -> some View {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let sourceTitle = favorite.resolvedSourceTitle

        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                Text(favorite.selection)
                    .font(StudyFont.serif(22))
                    .foregroundStyle(Color.studyInk)
                    .frame(maxWidth: .infinity, alignment: .leading)

                StudyBadge(text: masteryLabel(for: favorite, nowMs: nowMs))
            }

            HStack(spacing: 6) {
                if !favorite.cetLevel.isEmpty {
                    StudyOutlinePill(text: favorite.cetLevel, foreground: .studyAccent)
                }
                StudyBadge(
                    text: favorite.isDictionarySource ? "词典" : "阅读",
                    tone: favorite.isDictionarySource ? .accent : .muted
                )
            }
            .padding(.top, 4)

            let metaParts = [favorite.phonetic, favorite.partOfSpeech].filter { !$0.isEmpty }
            if !metaParts.isEmpty {
                Text(metaParts.joined(separator: "  "))
                    .font(.system(size: 13))
                    .foregroundStyle(Color.studyMuted)
                    .padding(.top, 4)
            }

            Text(favorite.meaning)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.studyAccent)
                .padding(.top, 4)

            if !favorite.sentence.isEmpty {
                Text(favorite.sentence)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .studyToneCard(padding: 10)
                    .padding(.top, 8)
            }

            if !sourceTitle.isEmpty {
                Text(sourceTitle)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.studyMuted)
                    .lineLimit(1)
                    .padding(.top, 6)
            }

            HStack(spacing: 4) {
                Spacer(minLength: 4)

                Button {
                    viewModel.requestRemove(favorite)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "trash")
                            .font(.system(size: 14, weight: .medium))
                        Text("移除")
                    }
                }
                .buttonStyle(StudyDangerTextButtonStyle(minHeight: 40, horizontalPadding: 8))
                .accessibilityIdentifier("vocab.remove.\(index)")

                Button(favorite.isDictionarySource ? "词典详情" : "回原文") {
                    viewModel.openFavorite(favorite)
                }
                .buttonStyle(StudyTextButtonStyle(minHeight: 40, horizontalPadding: 10))
                .accessibilityIdentifier("vocab.open.\(index)")
            }
            .padding(.top, 4)
        }
        .studyCard(padding: 14)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("vocab.row.\(index)")
    }

    private func masteryLabel(for favorite: Favorite, nowMs: Int64) -> String {
        let level: String = switch favorite.masteryLevel {
        case 1: "初识"
        case 2: "熟悉"
        case 3: "已掌握"
        default: "未复习"
        }
        return favorite.nextReviewAt <= nowMs ? "\(level) · 待复习" : level
    }
}

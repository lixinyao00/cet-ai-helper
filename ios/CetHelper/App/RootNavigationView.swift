import SwiftUI

struct RootNavigationView: View {
    let app: AppViewModel

    var body: some View {
        VStack(spacing: 0) {
            if let banner = app.offlineBanner {
                OfflineBannerView(message: banner)
            }

            Group {
                switch app.phase {
                case .restoring:
                    StatusView(message: nil, onRetry: nil, onBackToLogin: nil)
                case .restoreFailed(let message):
                    StatusView(
                        message: message,
                        onRetry: { app.retryRestore() },
                        onBackToLogin: { app.backToLogin() }
                    )
                case .signedOut:
                    LoginView(viewModel: app.login)
                case .signedIn(let scope):
                    SignedInNavigationHost(scope: scope)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.studyPaper.ignoresSafeArea())
    }
}

private struct SignedInNavigationHost: View {
    @Bindable var scope: SessionScope

    var body: some View {
        VStack(spacing: 0) {
            NavigationStack(path: $scope.path) {
                rootTabView
                    .navigationDestination(for: AppScreen.self) { screen in
                        switch screen {
                        case .dictionary(let query):
                            DictionaryDestinationView(scope: scope, query: query)
                        case .tutor:
                            TutorView(viewModel: scope.tutor, asTab: false)
                        case .reader(let articleId, let focus):
                            ReaderDestinationView(scope: scope, articleId: articleId, focus: focus)
                        case .vocabulary:
                            VocabularyView(viewModel: scope.vocabulary, asTab: false)
                        case .cloze:
                            ClozeDestinationView(scope: scope)
                        case .pageImport(let target, let source):
                            PageImportDestinationView(scope: scope, target: target, source: source)
                        case .translationSource:
                            TranslationSourceView(scope: scope, viewModel: scope.translation)
                        case .translationAnswer:
                            TranslationAnswerView(scope: scope, viewModel: scope.translation)
                        case .writing:
                            WritingView(scope: scope, viewModel: scope.writing)
                        }
                    }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .tint(Color.studyAccent)

            if scope.path.isEmpty {
                StudyBottomTabBar(activeTab: scope.activeTab) { tab in
                    scope.selectTab(tab)
                }
            }
        }
    }

    @ViewBuilder
    private var rootTabView: some View {
        switch scope.activeTab {
        case .home:
            HomeView(scope: scope)
        case .dictionary:
            DictionaryView(viewModel: scope.dictionary, asTab: true)
        case .tutor:
            TutorView(viewModel: scope.tutor, asTab: true)
        case .vocabulary:
            VocabularyView(viewModel: scope.vocabulary, asTab: true)
        }
    }
}

private struct StudyBottomTabBar: View {
    let activeTab: AppTab
    let onSelect: (AppTab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Color.studyOutline)
                .frame(height: 1)

            HStack(spacing: 0) {
                ForEach(AppTab.allCases) { tab in
                    let isSelected = activeTab == tab
                    Button {
                        onSelect(tab)
                    } label: {
                        VStack(spacing: 3) {
                            Image(systemName: tab.systemImage)
                                .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                                .accessibilityHidden(true)
                            Text(tab.title)
                                .font(.system(size: 11, weight: isSelected ? .semibold : .medium))
                                .lineLimit(1)
                        }
                        .foregroundStyle(isSelected ? Color.studyAccent : Color.studyMuted)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                    .accessibilityIdentifier(tab.accessibilityIdentifier)
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 4)
            .padding(.bottom, 2)
        }
        .background(Color.studySurfaceElevated.ignoresSafeArea(edges: .bottom))
    }
}

private struct DictionaryDestinationView: View {
    let scope: SessionScope
    let query: String?

    var body: some View {
        DictionaryView(viewModel: scope.dictionary, asTab: false)
            .onAppear {
                if let query {
                    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty,
                       scope.dictionary.query.caseInsensitiveCompare(trimmed) != .orderedSame {
                        scope.dictionary.lookup(trimmed)
                    }
                }
            }
    }
}

private struct ReaderDestinationView: View {
    let scope: SessionScope
    @State private var viewModel: ReaderViewModel?

    init(scope: SessionScope, articleId: String, focus: TextRange?) {
        self.scope = scope
        _viewModel = State(initialValue: scope.makeReader(articleId: articleId, focus: focus))
    }

    var body: some View {
        if let viewModel {
            ReaderView(
                viewModel: viewModel,
                fromPractice: scope.path.dropLast().last == .cloze,
                onOpenVocabulary: {
                    if scope.path.dropLast().last == .vocabulary {
                        scope.pop()
                    } else {
                        scope.push(.vocabulary)
                    }
                }
            )
        } else {
            VStack(spacing: 16) {
                Image(systemName: "doc.questionmark")
                    .font(.system(size: 40))
                    .foregroundStyle(Color.studyMuted)
                Text("文章不存在或已删除。")
                    .font(StudyFont.sectionTitle)
                    .foregroundStyle(Color.studyInk)
                Button("返回") {
                    scope.pop()
                }
                .buttonStyle(StudyPrimaryButtonStyle(fullWidth: false))
                .accessibilityIdentifier("nav.back")
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.studyPaper.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

private struct ClozeDestinationView: View {
    @State private var viewModel: ClozeViewModel

    init(scope: SessionScope) {
        _viewModel = State(initialValue: scope.makeCloze())
    }

    var body: some View {
        ClozeView(viewModel: viewModel)
    }
}

private struct PageImportDestinationView: View {
    let source: PageImportSource
    @State private var viewModel: PageImportViewModel

    init(scope: SessionScope, target: PageImportTarget, source: PageImportSource) {
        self.source = source
        _viewModel = State(initialValue: scope.makePageImport(target: target, source: source))
    }

    var body: some View {
        Group {
            switch viewModel.stage {
            case .external, .recognizing, .saving, .error:
                ImportStatusView(viewModel: viewModel, source: source)
            case .review:
                ImportReviewView(viewModel: viewModel)
            }
        }
        .pageImportPickers(viewModel: viewModel)
    }
}

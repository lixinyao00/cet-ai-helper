import SwiftUI
import UIKit

struct ReaderView: View {
    @Bindable var viewModel: ReaderViewModel
    var fromPractice: Bool = false
    var onOpenVocabulary: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @State private var viewportHeight: CGFloat = 0
    @State private var hasRestoredInitialScroll = false

    var body: some View {
        GeometryReader { outerGeo in
            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    StudyTopBar(
                        backTitle: fromPractice ? "返回练习" : "返回",
                        onBack: { dismiss() }
                    ) {
                        HStack(spacing: 2) {
                            Button {
                                viewModel.cycleFontTier()
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "textformat.size")
                                        .font(.system(size: 14, weight: .medium))
                                    Text(viewModel.fontTier.buttonTitle)
                                }
                            }
                            .buttonStyle(StudyTextButtonStyle(horizontalPadding: 10))
                            .accessibilityIdentifier("reader.fontTier")

                            Button {
                                onOpenVocabulary()
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "book.closed.fill")
                                        .font(.system(size: 14, weight: .medium))
                                    Text("生词本")
                                }
                            }
                            .buttonStyle(StudyTextButtonStyle(horizontalPadding: 8))
                            .accessibilityIdentifier("reader.vocabulary")
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 4)

                    StudyProgressBar(value: viewModel.progress, height: 4)
                        .padding(.horizontal, 16)

                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(viewModel.article.title)
                                    .font(StudyFont.serif(26))
                                    .foregroundStyle(Color.studyInk)
                                    .lineSpacing(3)

                                Text("点按单词查释义，长按拖选词组后点「查释义」。")
                                    .font(StudyFont.caption)
                                    .foregroundStyle(Color.studyMuted)
                                    .lineSpacing(3)
                                    .padding(.top, 8)

                                VStack(alignment: .leading, spacing: 0) {
                                    ForEach(Array(viewModel.paragraphs.enumerated()), id: \.element.id) { index, paragraph in
                                        WordTextView(
                                            paragraph: paragraph,
                                            fontSize: viewModel.fontTier.pointSize,
                                            savedRanges: viewModel.savedRanges,
                                            activeRange: viewModel.selection?.range,
                                            accessibilityId: "reader.paragraph.\(index)",
                                            onSelect: { range in
                                                viewModel.select(range)
                                            }
                                        )
                                        .padding(.top, 18)
                                        .id(paragraph.id)
                                    }
                                }
                                .padding(.top, 8)
                            }
                            .padding(.horizontal, 22)
                            .padding(.top, 16)
                            .padding(.bottom, viewModel.panel == .none ? 28 : outerGeo.size.height * 0.4)
                            .background(
                                GeometryReader { contentGeo in
                                    let frame = contentGeo.frame(in: .named("readerScroll"))
                                    Color.clear
                                        .onChange(of: frame.minY) { _, newMinY in
                                            reportScroll(minY: newMinY, contentHeight: frame.height)
                                        }
                                        .onAppear {
                                            viewportHeight = outerGeo.size.height
                                            if let target = viewModel.scrollTarget {
                                                scrollToTarget(target, proxy: proxy)
                                            }
                                        }
                                }
                            )
                            .background(
                                ReaderScrollRestoreHelper(
                                    targetOffsetY: viewModel.initialScrollY,
                                    shouldRestore: !hasRestoredInitialScroll && viewModel.scrollTarget == nil,
                                    onRestored: { hasRestoredInitialScroll = true }
                                )
                            )
                        }
                        .coordinateSpace(name: "readerScroll")
                        .onChange(of: viewModel.scrollTarget) { _, newTarget in
                            if let newTarget {
                                scrollToTarget(newTarget, proxy: proxy)
                            }
                        }
                    }
                }

                switch viewModel.panel {
                case .none:
                    EmptyView()
                case .lookup:
                    lookupDrawer(maxHeight: max(250, outerGeo.size.height * 0.38))
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                case .analysis:
                    SentenceAnalysisSheet(
                        viewModel: viewModel,
                        maxHeight: max(300, outerGeo.size.height * 3.0 / 5.0)
                    )
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .onAppear {
                viewportHeight = outerGeo.size.height
                viewModel.reloadSavedRanges()
            }
            .onChange(of: outerGeo.size.height) { _, newHeight in
                viewportHeight = newHeight
            }
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onDisappear {
            viewModel.flushProgress()
        }
    }

    private func reportScroll(minY: CGFloat, contentHeight: CGFloat) {
        let offsetY = max(0, Int(-minY))
        let scrollable = contentHeight - viewportHeight
        let progress = scrollable > 1 ? min(1, max(0, Double(-minY) / Double(scrollable))) : 0
        viewModel.updateScroll(offsetY: offsetY, progress: progress)
    }

    private func scrollToTarget(_ target: TextRange, proxy: ScrollViewProxy) {
        let match = viewModel.paragraphs.first(where: { $0.start <= target.start && target.start < $0.end })
            ?? viewModel.paragraphs.first(where: { $0.end > target.start })
        if let match {
            DispatchQueue.main.async {
                withAnimation {
                    proxy.scrollTo(match.id, anchor: .center)
                }
                viewModel.scrollTarget = nil
            }
        } else {
            viewModel.scrollTarget = nil
        }
    }

    private func lookupDrawer(maxHeight: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(Color.studyOutline)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.bottom, 8)

            HStack(alignment: .center, spacing: 4) {
                if let selectedText = viewModel.selection?.text {
                    Text(selectedText)
                        .font(StudyFont.serif(22))
                        .foregroundStyle(Color.studyInk)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .onTapGesture {
                            viewModel.speak(selectedText)
                        }

                    Button {
                        viewModel.speak(selectedText)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "speaker.wave.2.fill")
                                .font(.system(size: 14, weight: .medium))
                            Text("朗读")
                        }
                    }
                    .buttonStyle(StudyTextButtonStyle(minHeight: 40, horizontalPadding: 8))
                    .accessibilityIdentifier("reader.panel.speak")
                } else {
                    Spacer(minLength: 4)
                }

                Button("关闭") {
                    viewModel.closePanel()
                }
                .buttonStyle(StudyTextButtonStyle(minHeight: 40, horizontalPadding: 6))
                .accessibilityIdentifier("reader.panel.close")
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let notice = viewModel.selectionNotice {
                        Text(notice)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 6)
                            .accessibilityIdentifier("reader.panel.notice")
                    } else if viewModel.lookup.isPending {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在查询…")
                                .font(StudyFont.caption)
                                .foregroundStyle(Color.studyMuted)
                        }
                        .padding(.vertical, 6)
                    } else if let error = viewModel.lookup.errorMessage {
                        if viewModel.showsDemoBadge {
                            StudyBadge(text: "演示释义")
                        }
                        Text(error)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 6)
                        if viewModel.lookup.canRetry {
                            Button("重试") {
                                viewModel.retryLookup()
                            }
                            .buttonStyle(StudyPrimaryButtonStyle())
                            .padding(.top, 10)
                        }
                    } else if let result = viewModel.lookup.value {
                        let meta = result.formattedMeta()
                        if viewModel.showsDemoBadge || !meta.isEmpty {
                            HStack(spacing: 8) {
                                if viewModel.showsDemoBadge {
                                    StudyBadge(text: "演示释义")
                                }
                                if !meta.isEmpty {
                                    Text(meta)
                                        .font(.system(size: 13))
                                        .foregroundStyle(Color.studyAccent)
                                        .accessibilityIdentifier("reader.panel.meta")
                                }
                            }
                        }

                        Text(result.meaning)
                            .font(.system(size: 19, weight: .medium))
                            .foregroundStyle(Color.studyInk)
                            .padding(.top, 6)
                            .accessibilityIdentifier("reader.panel.meaning")

                        Text(result.explanation)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 6)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 6)
            }

            if viewModel.canSaveSelection || viewModel.canAnalyze || viewModel.canAskTutorWord {
                VStack(spacing: 8) {
                    if viewModel.canSaveSelection || viewModel.canAnalyze {
                        HStack(spacing: 12) {
                            if viewModel.canSaveSelection {
                                Button(viewModel.isSelectionSaved ? "已收藏" : "收藏") {
                                    viewModel.toggleFavorite()
                                }
                                .buttonStyle(StudyPrimaryButtonStyle())
                                .accessibilityIdentifier("reader.panel.save")
                            }

                            if viewModel.canAnalyze {
                                Button("分析整句") {
                                    viewModel.analyzeSentence()
                                }
                                .buttonStyle(StudySecondaryButtonStyle())
                                .accessibilityIdentifier("reader.panel.analyze")
                            }
                        }
                    }

                    if viewModel.canAskTutorWord {
                        Button {
                            viewModel.askTutorForWord()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 14, weight: .medium))
                                    .accessibilityHidden(true)
                                Text("追问 AI 助教")
                            }
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .accessibilityIdentifier("reader.panel.askTutor")
                    }
                }
                .padding(.top, 10)
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
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reader.panel")
    }
}

private struct ReaderScrollRestoreHelper: UIViewRepresentable {
    let targetOffsetY: Int
    let shouldRestore: Bool
    let onRestored: () -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isHidden = true
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        guard shouldRestore, targetOffsetY > 0 else { return }
        DispatchQueue.main.async {
            guard let scrollView = findScrollView(from: uiView) else { return }
            let maxOffset = max(0, scrollView.contentSize.height - scrollView.bounds.height)
            guard maxOffset > 0 else { return }
            let y = min(CGFloat(targetOffsetY), maxOffset)
            scrollView.setContentOffset(CGPoint(x: 0, y: y), animated: false)
            onRestored()
        }
    }

    private func findScrollView(from view: UIView) -> UIScrollView? {
        var current = view.superview
        while let parent = current {
            if let scroll = parent as? UIScrollView {
                return scroll
            }
            current = parent.superview
        }
        return nil
    }
}

import SwiftUI
import UIKit

struct ReviewIssueCard<Footer: View>: View {
    let issue: ReviewIssue
    let onLocate: (ReviewIssue) -> Void
    @ViewBuilder let footer: () -> Footer

    init(
        issue: ReviewIssue,
        onLocate: @escaping (ReviewIssue) -> Void,
        @ViewBuilder footer: @escaping () -> Footer
    ) {
        self.issue = issue
        self.onLocate = onLocate
        self.footer = footer
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                onLocate(issue)
            } label: {
                Text("\(issue.type.label) · 「\(issue.quote)」\n定位引文")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.studyAccent)
                    .multilineTextAlignment(.leading)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("issue.locate.\(issue.index)")

            Text(issue.detail)
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 2)

            Text("建议：\(issue.suggestion)")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.studyInk)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 6)

            if let notice = issue.candidateNotice {
                Text(notice)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
            }

            footer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyToneCard(padding: 12)
    }
}

extension ReviewIssueCard where Footer == EmptyView {
    init(issue: ReviewIssue, onLocate: @escaping (ReviewIssue) -> Void) {
        self.init(issue: issue, onLocate: onLocate) { EmptyView() }
    }
}

struct QuoteSnapshotSheet: View {
    let snapshot: QuoteSnapshot
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(snapshot.title)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.studyInk)
                .frame(maxWidth: .infinity, alignment: .leading)

            InlineHighlightReadOnlyView(
                text: snapshot.text,
                highlights: snapshot.highlights,
                isSerif: false,
                fontSize: 17,
                showsBorder: false,
                accessibilityId: "snapshot.sheet.text"
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            HStack {
                Spacer()
                Button("确定") {
                    dismiss()
                }
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Color.studyAccent)
                .frame(minHeight: 44)
                .padding(.horizontal, 12)
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 12)
        .background(Color.studySurface.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("snapshot.sheet")
    }
}

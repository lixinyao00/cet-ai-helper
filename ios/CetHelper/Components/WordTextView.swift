import SwiftUI
import UIKit

struct WordTextView: UIViewRepresentable {
    let paragraph: Paragraph
    let fontSize: Double
    let savedRanges: [TextRange]
    let activeRange: TextRange?
    let accessibilityId: String
    let onSelect: (TextRange) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.adjustsFontForContentSizeCategory = false
        textView.delegate = context.coordinator
        textView.accessibilityIdentifier = accessibilityId

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        tap.cancelsTouchesInView = false
        tap.delegate = context.coordinator
        textView.addGestureRecognizer(tap)
        context.coordinator.textView = textView

        applyContent(to: textView, coordinator: context.coordinator)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        context.coordinator.onSelect = onSelect
        context.coordinator.paragraphStart = paragraph.start
        context.coordinator.paragraphText = paragraph.text
        textView.accessibilityIdentifier = accessibilityId
        applyContent(to: textView, coordinator: context.coordinator)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.window?.windowScene?.screen.bounds.width ?? 360
        let fitting = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fitting.height))
    }

    private func applyContent(to textView: UITextView, coordinator: Coordinator) {
        let localSaved = localIntersections(of: savedRanges)
        let localActive = activeRange.flatMap { localIntersection(of: $0) }

        let state = RenderState(
            text: paragraph.text,
            fontSize: fontSize,
            saved: localSaved,
            active: localActive
        )
        guard coordinator.lastState != state else { return }
        coordinator.lastState = state
        coordinator.paragraphStart = paragraph.start
        coordinator.paragraphText = paragraph.text

        let savedSelection = textView.selectedRange
        let baseFont = UIFont.systemFont(ofSize: CGFloat(fontSize), weight: .regular)
        let serifDescriptor = baseFont.fontDescriptor.withDesign(.serif) ?? baseFont.fontDescriptor
        let font = UIFont(descriptor: serifDescriptor, size: CGFloat(fontSize))

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineSpacing = 8

        let attributed = NSMutableAttributedString(
            string: paragraph.text,
            attributes: [
                .font: font,
                .foregroundColor: UIColor.studyInk,
                .paragraphStyle: paragraphStyle
            ]
        )

        for range in localSaved {
            attributed.addAttributes([
                .backgroundColor: UIColor.studySavedHighlight,
                .underlineStyle: NSUnderlineStyle.single.rawValue,
                .underlineColor: UIColor.studyAccent
            ], range: range.nsRange)
        }

        if let active = localActive {
            attributed.addAttribute(.backgroundColor, value: UIColor.studyMarker, range: active.nsRange)
        }

        textView.attributedText = attributed
        if savedSelection.length > 0, savedSelection.location + savedSelection.length <= attributed.length {
            textView.selectedRange = savedSelection
        }
    }

    private func localIntersections(of ranges: [TextRange]) -> [TextRange] {
        ranges.compactMap { localIntersection(of: $0) }
    }

    private func localIntersection(of range: TextRange) -> TextRange? {
        let length = paragraph.text.utf16.count
        let localStart = max(0, range.start - paragraph.start)
        let localEnd = min(length, range.end - paragraph.start)
        guard localStart < localEnd else { return nil }
        return TextRange(start: localStart, end: localEnd)
    }

    fileprivate struct RenderState: Equatable {
        let text: String
        let fontSize: Double
        let saved: [TextRange]
        let active: TextRange?
    }

    final class Coordinator: NSObject, UITextViewDelegate, UIGestureRecognizerDelegate {
        private static let wordRegex = try? NSRegularExpression(
            pattern: "\\p{L}[\\p{L}\\p{M}]*(?:['’‐‑-][\\p{L}\\p{M}]+)*"
        )

        var onSelect: (TextRange) -> Void
        var paragraphStart: Int = 0
        var paragraphText: String = ""
        fileprivate var lastState: RenderState?
        weak var textView: UITextView?

        init(onSelect: @escaping (TextRange) -> Void) {
            self.onSelect = onSelect
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, let textView else { return }
            if textView.selectedRange.length > 0 {
                textView.selectedTextRange = nil
                return
            }
            let point = recognizer.location(in: textView)
            lookUpWord(at: point, in: textView)
        }

        private func lookUpWord(at point: CGPoint, in textView: UITextView) {
            let utf16Length = (paragraphText as NSString).length
            guard utf16Length > 0,
                  let closest = textView.closestPosition(to: point) else {
                return
            }
            let caretOffset = textView.offset(from: textView.beginningOfDocument, to: closest)
            var hitOffset: Int?

            if caretOffset > 0,
               let prevPos = textView.position(from: closest, offset: -1),
               let prevRange = textView.textRange(from: prevPos, to: closest) {
                let rect = textView.firstRect(for: prevRange)
                if rect.insetBy(dx: -2, dy: -6).contains(point) {
                    hitOffset = caretOffset - 1
                }
            }

            if hitOffset == nil,
               caretOffset < utf16Length,
               let nextPos = textView.position(from: closest, offset: 1),
               let nextRange = textView.textRange(from: closest, to: nextPos) {
                let rect = textView.firstRect(for: nextRange)
                if rect.insetBy(dx: -2, dy: -6).contains(point) {
                    hitOffset = caretOffset
                }
            }

            guard let targetOffset = hitOffset,
                  let regex = Self.wordRegex else {
                return
            }

            let fullRange = NSRange(location: 0, length: utf16Length)
            for match in regex.matches(in: paragraphText, range: fullRange) {
                let r = match.range
                if r.location <= targetOffset && targetOffset < r.location + r.length {
                    onSelect(TextRange(start: paragraphStart + r.location, end: paragraphStart + r.location + r.length))
                    return
                }
            }
        }

        func textView(_ textView: UITextView, editMenuForTextIn range: NSRange, suggestedActions: [UIMenuElement]) -> UIMenu? {
            guard range.length > 0 else {
                return UIMenu(children: suggestedActions)
            }
            let lookupAction = UIAction(
                title: "查释义",
                image: UIImage(systemName: "magnifyingglass")
            ) { [weak self, weak textView] _ in
                guard let self, let textView else { return }
                let start = range.location
                let end = range.location + range.length
                textView.selectedTextRange = nil
                self.onSelect(TextRange(start: self.paragraphStart + start, end: self.paragraphStart + end))
            }
            return UIMenu(children: [lookupAction] + suggestedActions)
        }
    }
}

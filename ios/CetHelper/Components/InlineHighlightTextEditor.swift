import SwiftUI
import UIKit

struct InlineHighlightTextEditor: View {
    @Binding var text: String
    let field: PracticeField
    let inlineHighlight: InlineHighlight?
    let placeholder: String
    var isSerif: Bool = false
    var minHeight: CGFloat = 150
    let accessibilityId: String
    var focusToken: UUID? = nil

    var body: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty && !placeholder.isEmpty {
                Text(placeholder)
                    .font(isSerif ? StudyFont.serif(16) : .system(size: 16))
                    .foregroundStyle(Color.studyMuted)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .allowsHitTesting(false)
            }

            HighlightUITextView(
                text: $text,
                field: field,
                inlineHighlight: inlineHighlight,
                isSerif: isSerif,
                isEditable: true,
                accessibilityId: accessibilityId,
                focusToken: focusToken
            )
            .frame(minHeight: minHeight, maxHeight: max(minHeight, 220))
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
}

struct InlineHighlightReadOnlyView: View {
    let text: String
    let highlights: [TextRange]
    var isSerif: Bool = false
    var fontSize: CGFloat = 16
    var showsBorder: Bool = true
    let accessibilityId: String

    var body: some View {
        let content = HighlightReadOnlyUITextView(
            text: text,
            highlights: highlights,
            isSerif: isSerif,
            fontSize: fontSize,
            insets: showsBorder
                ? UIEdgeInsets(top: 12, left: 10, bottom: 12, right: 10)
                : UIEdgeInsets(top: 4, left: 0, bottom: 4, right: 0),
            accessibilityId: accessibilityId
        )

        if showsBorder {
            content
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .fill(Color.studySurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                        .stroke(Color.studyOutline, lineWidth: 1)
                )
        } else {
            content
        }
    }
}

private struct HighlightUITextView: UIViewRepresentable {
    @Binding var text: String
    let field: PracticeField
    let inlineHighlight: InlineHighlight?
    let isSerif: Bool
    let isEditable: Bool
    let accessibilityId: String
    let focusToken: UUID?

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.isEditable = isEditable
        textView.isSelectable = true
        textView.isScrollEnabled = true
        textView.backgroundColor = .clear
        textView.textContainerInset = UIEdgeInsets(top: 14, left: 11, bottom: 14, right: 11)
        textView.delegate = context.coordinator
        textView.accessibilityIdentifier = accessibilityId
        applyFontAndTypingAttributes(to: textView)
        textView.text = text
        applyHighlights(to: textView, coordinator: context.coordinator)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        context.coordinator.textBinding = $text
        textView.accessibilityIdentifier = accessibilityId
        applyFontAndTypingAttributes(to: textView)

        if textView.markedTextRange == nil && textView.text != text {
            textView.text = text
        }
        applyHighlights(to: textView, coordinator: context.coordinator)

        if let focusToken, context.coordinator.lastFocusToken != focusToken {
            context.coordinator.lastFocusToken = focusToken
            DispatchQueue.main.async {
                textView.becomeFirstResponder()
            }
        }
    }

    private func baseFont() -> UIFont {
        let base = UIFont.systemFont(ofSize: 16, weight: .regular)
        if isSerif, let serif = base.fontDescriptor.withDesign(.serif) {
            return UIFont(descriptor: serif, size: 16)
        }
        return base
    }

    private func applyFontAndTypingAttributes(to textView: UITextView) {
        let font = baseFont()
        textView.font = font
        textView.textColor = UIColor.studyInk
        textView.typingAttributes = [
            .font: font,
            .foregroundColor: UIColor.studyInk
        ]
    }

    private func applyHighlights(to textView: UITextView, coordinator: Coordinator) {
        let activeHighlight = (inlineHighlight?.field == field) ? inlineHighlight : nil
        let storage = textView.textStorage
        let fullLength = storage.length
        let fullRange = NSRange(location: 0, length: fullLength)

        storage.beginEditing()
        storage.removeAttribute(.backgroundColor, range: fullRange)
        storage.addAttribute(.font, value: baseFont(), range: fullRange)
        storage.addAttribute(.foregroundColor, value: UIColor.studyInk, range: fullRange)

        if let activeHighlight {
            for range in activeHighlight.ranges {
                let ns = range.nsRange
                if ns.location >= 0 && ns.length > 0 && ns.location + ns.length <= fullLength {
                    storage.addAttribute(.backgroundColor, value: UIColor.studyMarker, range: ns)
                }
            }
        }
        storage.endEditing()

        if let activeHighlight,
           coordinator.lastScrolledToken != activeHighlight.token,
           let first = activeHighlight.ranges.first {
            coordinator.lastScrolledToken = activeHighlight.token
            let ns = first.nsRange
            if ns.location >= 0 && ns.location + ns.length <= fullLength {
                DispatchQueue.main.async {
                    textView.scrollRangeToVisible(ns)
                }
            }
        } else if activeHighlight == nil {
            coordinator.lastScrolledToken = nil
        }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var textBinding: Binding<String>
        var lastScrolledToken: UUID?
        var lastFocusToken: UUID?

        init(text: Binding<String>) {
            self.textBinding = text
        }

        func textViewDidChange(_ textView: UITextView) {
            let updated = textView.text ?? ""
            if textBinding.wrappedValue != updated {
                textBinding.wrappedValue = updated
            }
        }
    }
}

private struct HighlightReadOnlyUITextView: UIViewRepresentable {
    let text: String
    let highlights: [TextRange]
    let isSerif: Bool
    let fontSize: CGFloat
    let insets: UIEdgeInsets
    let accessibilityId: String

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = true
        textView.backgroundColor = .clear
        textView.textContainerInset = insets
        textView.accessibilityIdentifier = accessibilityId
        applyContent(to: textView, coordinator: context.coordinator)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        textView.textContainerInset = insets
        textView.accessibilityIdentifier = accessibilityId
        applyContent(to: textView, coordinator: context.coordinator)
    }

    private func applyContent(to textView: UITextView, coordinator: Coordinator) {
        let state = StateKey(text: text, highlights: highlights, isSerif: isSerif, fontSize: fontSize)
        guard coordinator.lastState != state else { return }
        coordinator.lastState = state

        let base = UIFont.systemFont(ofSize: fontSize, weight: .regular)
        let font: UIFont
        if isSerif, let serif = base.fontDescriptor.withDesign(.serif) {
            font = UIFont(descriptor: serif, size: fontSize)
        } else {
            font = base
        }

        let style = NSMutableParagraphStyle()
        style.lineSpacing = 4

        let attributed = NSMutableAttributedString(
            string: text,
            attributes: [
                .font: font,
                .foregroundColor: UIColor.studyInk,
                .paragraphStyle: style
            ]
        )

        let total = attributed.length
        for range in highlights {
            let ns = range.nsRange
            if ns.location >= 0 && ns.length > 0 && ns.location + ns.length <= total {
                attributed.addAttribute(.backgroundColor, value: UIColor.studyMarker, range: ns)
            }
        }

        textView.attributedText = attributed

        if let first = highlights.first {
            let ns = first.nsRange
            if ns.location >= 0 && ns.location + ns.length <= total {
                DispatchQueue.main.async {
                    textView.scrollRangeToVisible(ns)
                }
            }
        }
    }

    fileprivate struct StateKey: Equatable {
        let text: String
        let highlights: [TextRange]
        let isSerif: Bool
        let fontSize: CGFloat
    }

    final class Coordinator {
        fileprivate var lastState: StateKey?
    }
}

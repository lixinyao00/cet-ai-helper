import SwiftUI
import UIKit

// MARK: - Color Tokens (§4.3)

extension Color {
    static let studyPaper = Color("paper")
    static let studySurface = Color("surface")
    static let studySurfaceElevated = Color("surface_elevated")
    static let studyInk = Color("ink")
    static let studyMuted = Color("muted")
    static let studyAccent = Color("accent")
    static let studyAccentSoft = Color("accent_soft")
    static let studyOnAccent = Color("on_accent")
    static let studyOutline = Color("outline")
    static let studyTone = Color("tone")
    static let studyMarker = Color("marker")
    static let studyDanger = Color("danger")
    static let studyDangerSoft = Color("danger_soft")
    static let studyWarning = Color("warning")
    static let studyWarningSoft = Color("warning_soft")
    static let studySuccess = Color("success")
    static let studySuccessSoft = Color("success_soft")
    static let studyBadgeBg = Color("badge_bg")
    static let studyBadgeText = Color("badge_text")
    static let studyRipple = Color("ripple")
    static let studySavedHighlight = Color("saved_highlight")
}

extension UIColor {
    static func studyNamed(_ name: String, light: UInt32, dark: UInt32, alphaLight: CGFloat = 1, alphaDark: CGFloat = 1) -> UIColor {
        if let asset = UIColor(named: name) {
            return asset
        }
        return UIColor { trait in
            let hex = trait.userInterfaceStyle == .dark ? dark : light
            let alpha = trait.userInterfaceStyle == .dark ? alphaDark : alphaLight
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255.0,
                green: CGFloat((hex >> 8) & 0xFF) / 255.0,
                blue: CGFloat(hex & 0xFF) / 255.0,
                alpha: alpha
            )
        }
    }

    static let studyPaper = studyNamed("paper", light: 0xF5F4EE, dark: 0x0F1719)
    static let studySurface = studyNamed("surface", light: 0xFFFEFA, dark: 0x182427)
    static let studyInk = studyNamed("ink", light: 0x162B2F, dark: 0xE6F0EE)
    static let studyMuted = studyNamed("muted", light: 0x455D60, dark: 0x9FB3B0)
    static let studyAccent = studyNamed("accent", light: 0x075B53, dark: 0x4DB6AC)
    static let studyOutline = studyNamed("outline", light: 0xC9D8D2, dark: 0x2A3B3F)
    static let studyMarker = studyNamed("marker", light: 0xFADD80, dark: 0x665214)
    static let studySavedHighlight = studyNamed("saved_highlight", light: 0x075B53, dark: 0x4DB6AC, alphaLight: 0.133, alphaDark: 0.20)
}

// MARK: - Design Tokens & Typography (§4.3)

enum StudyLayout {
    static let cardRadius: CGFloat = 16
    static let buttonRadius: CGFloat = 14
    static let toneRadius: CGFloat = 12
    static let sheetRadius: CGFloat = 24
    static let badgeRadius: CGFloat = 20
    static let progressRadius: CGFloat = 4
    static let minButtonHeight: CGFloat = 48
    static let minInputHeight: CGFloat = 54
    static let tileHeight: CGFloat = 84
    static let pagePadding: CGFloat = 16
}

enum StudyFont {
    static let screenTitle = Font.system(size: 28, weight: .medium)
    static let sectionTitle = Font.system(size: 18, weight: .medium)
    static let caption = Font.system(size: 14, weight: .regular)
    static let statValue = Font.system(size: 24, weight: .medium)
    static let hintMono = Font.system(size: 15, weight: .regular, design: .monospaced)

    static func serif(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        Font.system(size: size, weight: weight, design: .serif)
    }
}

// MARK: - Button & Card Styles

struct StudyPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var fullWidth: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyOnAccent : Color.studyMuted)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .frame(minWidth: fullWidth ? nil : 88, minHeight: StudyLayout.minButtonHeight)
            .padding(.horizontal, 16)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .fill(isEnabled ? Color.studyAccent : Color.studyTone)
            )
            .opacity(configuration.isPressed ? 0.86 : 1.0)
    }
}

struct StudySecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var fullWidth: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyInk : Color.studyMuted)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .frame(minWidth: fullWidth ? nil : 88, minHeight: StudyLayout.minButtonHeight)
            .padding(.horizontal, 14)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .fill(isEnabled ? Color.studySurface : Color.studyPaper)
            )
            .overlay(
                RoundedRectangle(cornerRadius: StudyLayout.buttonRadius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.85 : 1.0)
    }
}

struct StudyTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var minHeight: CGFloat = StudyLayout.minButtonHeight
    var horizontalPadding: CGFloat = 12
    var fullWidth: Bool = false
    var fontSize: CGFloat = 14

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyAccent : Color.studyMuted)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .frame(minHeight: minHeight)
            .padding(.horizontal, horizontalPadding)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.65 : 1.0)
    }
}

struct StudyDangerTextButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var minHeight: CGFloat = StudyLayout.minButtonHeight
    var horizontalPadding: CGFloat = 12
    var fullWidth: Bool = false
    var fontSize: CGFloat = 14

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .medium))
            .foregroundStyle(isEnabled ? Color.studyDanger : Color.studyMuted)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .frame(minHeight: minHeight)
            .padding(.horizontal, horizontalPadding)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.65 : 1.0)
    }
}

struct StudyCompactButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var isAccent: Bool = false
    var isDestructive: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        let fg: Color = {
            if !isEnabled { return .studyMuted }
            if isDestructive { return .studyDanger }
            if isAccent { return .studyAccent }
            return .studyInk
        }()
        let bg: Color = isDestructive ? .studyDangerSoft : (isAccent ? .studyAccentSoft : .studySurface)
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(fg)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(bg)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(isDestructive ? Color.studyDanger.opacity(0.35) : Color.studyOutline, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.82 : 1.0)
    }
}

extension View {
    func studyCard(padding: CGFloat = 16, background: Color = .studySurface, radius: CGFloat = StudyLayout.cardRadius) -> some View {
        self
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(background)
            )
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
    }

    func studyToneCard(padding: CGFloat = 12) -> some View {
        self
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.toneRadius, style: .continuous)
                    .fill(Color.studyTone)
            )
    }

    func studyStatCard(padding: CGFloat = 16, radius: CGFloat = StudyLayout.cardRadius) -> some View {
        self
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.studyAccentSoft, Color.studySurface],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
    }

    func studyInputFieldStyle() -> some View {
        self
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
    }

    func studyConfirmationAlert(_ binding: Binding<ConfirmationRequest?>) -> some View {
        alert(
            binding.wrappedValue?.title ?? "",
            isPresented: Binding(
                get: { binding.wrappedValue != nil },
                set: { if !$0 { binding.wrappedValue = nil } }
            ),
            presenting: binding.wrappedValue
        ) { request in
            Button(request.confirmTitle, role: request.isDestructive ? .destructive : nil) {
                request.onConfirm()
                binding.wrappedValue = nil
            }
            Button("取消", role: .cancel) {
                binding.wrappedValue = nil
            }
        } message: { request in
            Text(request.message)
        }
    }
}

// MARK: - UTF-16 Attributed Highlight Helpers

extension String {
    func utf16Subrange(_ range: TextRange) -> Range<String.Index>? {
        let count = utf16.count
        guard range.start >= 0, range.start < range.end, range.end <= count else {
            return nil
        }
        let s16 = utf16.index(utf16.startIndex, offsetBy: range.start)
        let e16 = utf16.index(utf16.startIndex, offsetBy: range.end)
        guard let startIndex = String.Index(s16, within: self),
              let endIndex = String.Index(e16, within: self) else {
            return nil
        }
        return startIndex..<endIndex
    }

    func attributedWithMarkerHighlights(_ ranges: [TextRange]) -> AttributedString {
        var result = AttributedString(self)
        for textRange in ranges {
            guard let strRange = utf16Subrange(textRange),
                  let attrStart = AttributedString.Index(strRange.lowerBound, within: result),
                  let attrEnd = AttributedString.Index(strRange.upperBound, within: result) else {
                continue
            }
            result[attrStart..<attrEnd].backgroundColor = .studyMarker
        }
        return result
    }
}

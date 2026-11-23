import SwiftUI

enum StudyBadgeTone {
    case accent
    case success
    case warning
    case danger
    case muted

    var background: Color {
        switch self {
        case .accent: return .studyBadgeBg
        case .success: return .studySuccessSoft
        case .warning: return .studyWarningSoft
        case .danger: return .studyDangerSoft
        case .muted: return .studyTone
        }
    }

    var foreground: Color {
        switch self {
        case .accent: return .studyBadgeText
        case .success: return .studySuccess
        case .warning: return .studyWarning
        case .danger: return .studyDanger
        case .muted: return .studyMuted
        }
    }
}

struct StudyHeading: View {
    let text: String
    var size: CGFloat = 28

    init(_ text: String, size: CGFloat = 28) {
        self.text = text
        self.size = size
    }

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .medium))
            .foregroundStyle(Color.studyInk)
            .lineSpacing(3)
    }
}

struct StudyBadge: View {
    let text: String
    var tone: StudyBadgeTone = .accent

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(tone.foreground)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                    .fill(tone.background)
            )
    }
}

struct StudyOutlinePill: View {
    let text: String
    var foreground: Color = .studyMuted
    var fontSize: CGFloat = 11

    var body: some View {
        Text(text)
            .font(.system(size: fontSize, weight: .regular))
            .foregroundStyle(foreground)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                    .fill(Color.studyTone)
            )
            .overlay(
                RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                    .stroke(Color.studyOutline, lineWidth: 1)
            )
    }
}

struct StudyTopBar<Trailing: View>: View {
    let backTitle: String
    let onBack: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    init(
        backTitle: String = "返回",
        onBack: @escaping () -> Void,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) {
        self.backTitle = backTitle
        self.onBack = onBack
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 15, weight: .medium))
                    Text(backTitle)
                }
            }
            .buttonStyle(StudyTextButtonStyle(horizontalPadding: 6))
            .accessibilityIdentifier("nav.back")

            Spacer(minLength: 8)

            trailing()
        }
    }
}

struct OfflineBannerView: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.system(size: 13, weight: .regular))
            .foregroundStyle(Color.studyWarning)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.studyWarningSoft)
            .accessibilityIdentifier("offline.banner")
    }
}

struct StudyStatCard: View {
    let value: Int
    let label: String
    let identifier: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(value)")
                .font(StudyFont.statValue)
                .foregroundStyle(Color.studyAccent)
                .monospacedDigit()
            Text(label)
                .font(.system(size: 12, weight: .regular))
                .foregroundStyle(Color.studyMuted)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
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
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }
}

struct StudyNoticeBanner: View {
    let message: String
    var tone: StudyBadgeTone = .warning

    var body: some View {
        Text(message)
            .font(.system(size: 14, weight: .regular))
            .foregroundStyle(tone.foreground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: StudyLayout.toneRadius, style: .continuous)
                    .fill(tone.background)
            )
    }
}

struct StudyProgressBar: View {
    let value: Double
    var height: CGFloat = 6

    var body: some View {
        let clamped = min(1, max(0, value))
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: StudyLayout.progressRadius, style: .continuous)
                    .fill(Color.studyTone)
                RoundedRectangle(cornerRadius: StudyLayout.progressRadius, style: .continuous)
                    .fill(Color.studyAccent)
                    .frame(width: geo.size.width * clamped)
            }
        }
        .frame(height: height)
    }
}

struct StudyFilterChip: View {
    let title: String
    let isSelected: Bool
    var isDisabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isDisabled ? Color.studyMuted : (isSelected ? Color.studyAccent : Color.studyInk))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                        .fill(isSelected ? Color.studyAccentSoft : Color.studySurface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: StudyLayout.badgeRadius, style: .continuous)
                        .stroke(isSelected ? Color.studyAccent : Color.studyOutline, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

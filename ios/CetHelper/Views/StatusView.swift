import SwiftUI

struct StatusView: View {
    let message: String?
    let onRetry: (() -> Void)?
    let onBackToLogin: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            if let message {
                Text(message)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .multilineTextAlignment(.center)

                if let onRetry {
                    Button("重试") {
                        onRetry()
                    }
                    .buttonStyle(StudyPrimaryButtonStyle(fullWidth: false))
                    .padding(.top, 18)
                    .accessibilityIdentifier("status.retry")
                }

                if let onBackToLogin {
                    Button("返回登录") {
                        onBackToLogin()
                    }
                    .buttonStyle(StudyTextButtonStyle())
                    .padding(.top, 6)
                    .accessibilityIdentifier("status.backToLogin")
                }
            } else {
                ProgressView()
                    .tint(Color.studyAccent)

                Text("正在恢复会话…")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .multilineTextAlignment(.center)
                    .padding(.top, 14)
            }

            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.studyPaper.ignoresSafeArea())
    }
}

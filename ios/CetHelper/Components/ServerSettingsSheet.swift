import SwiftUI

struct ServerSettingsSheet: View {
    @Binding var serverDraft: String
    let serverDraftError: String?
    let healthCheck: HealthCheckState
    let onTestConnection: () -> Void
    let onSave: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                healthStatusView

                TextField("http://127.0.0.1:8080", text: $serverDraft)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(size: 16))
                    .foregroundStyle(Color.studyInk)
                    .studyInputFieldStyle()
                    .accessibilityIdentifier("server.url")

                if let error = serverDraftError {
                    Text(error)
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyDanger)
                        .lineSpacing(3)
                }

                HStack(spacing: 12) {
                    Button("测试连接") {
                        onTestConnection()
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                    .disabled(isChecking)
                    .accessibilityIdentifier("server.test")

                    Button("保存") {
                        onSave()
                    }
                    .buttonStyle(StudyPrimaryButtonStyle())
                    .accessibilityIdentifier("server.save")
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 22)
            .padding(.top, 14)
            .padding(.bottom, 20)
            .background(Color.studyPaper.ignoresSafeArea())
            .navigationTitle("设置服务端地址")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") {
                        dismiss()
                    }
                }
            }
        }
    }

    private var isChecking: Bool {
        if case .checking = healthCheck {
            return true
        }
        return false
    }

    @ViewBuilder
    private var healthStatusView: some View {
        switch healthCheck {
        case .idle:
            Text("可点击「测试连接」检查服务状态与 Demo / Live 模式。")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
                .accessibilityIdentifier("server.health")
        case .checking:
            Text("正在探测服务连接…")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyMuted)
                .lineSpacing(3)
                .accessibilityIdentifier("server.health")
        case .ok(let info):
            Text(formattedHealthMessage(info))
                .font(StudyFont.caption)
                .foregroundStyle(Color.studySuccess)
                .lineSpacing(3)
                .accessibilityIdentifier("server.health")
        case .failed(let message):
            Text("连接测试失败：\(message)")
                .font(StudyFont.caption)
                .foregroundStyle(Color.studyDanger)
                .lineSpacing(3)
                .accessibilityIdentifier("server.health")
        }
    }

    private func formattedHealthMessage(_ info: HealthInfo) -> String {
        let modeLabel: String = {
            guard let mode = info.mode, !mode.isEmpty else { return "" }
            if mode.caseInsensitiveCompare("demo") == .orderedSame {
                return "Demo 演示模式"
            }
            if mode.caseInsensitiveCompare("live") == .orderedSame {
                return "Live 真实模型"
            }
            return mode
        }()
        let versionPart = info.version.flatMap { $0.isEmpty ? nil : " (v\($0))" } ?? ""
        return "连接成功 · 状态 \(info.status)\(modeLabel.isEmpty ? "" : " · \(modeLabel)")\(versionPart)"
    }
}

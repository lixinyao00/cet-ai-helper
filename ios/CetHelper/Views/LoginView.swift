import SwiftUI

struct LoginView: View {
    @Bindable var viewModel: LoginViewModel

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)

                    StudyBadge(text: "四六级 AI 学习助手")

                    StudyHeading("登录学习账号")
                        .padding(.top, 10)

                    TextField("用户名", text: $viewModel.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 16))
                        .foregroundStyle(Color.studyInk)
                        .submitLabel(.next)
                        .studyInputFieldStyle()
                        .disabled(!viewModel.canSubmit)
                        .padding(.top, 24)
                        .accessibilityIdentifier("login.username")

                    SecureField("密码", text: $viewModel.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 16))
                        .foregroundStyle(Color.studyInk)
                        .submitLabel(.done)
                        .onSubmit {
                            if viewModel.canSubmit {
                                viewModel.signIn()
                            }
                        }
                        .studyInputFieldStyle()
                        .disabled(!viewModel.canSubmit)
                        .padding(.top, 10)
                        .accessibilityIdentifier("login.password")

                    if let error = viewModel.errorMessage {
                        Text(error)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyDanger)
                            .lineSpacing(3)
                            .padding(.top, 12)
                            .accessibilityIdentifier("login.error")
                    } else if let notice = viewModel.noticeMessage {
                        Text(notice)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)
                            .accessibilityIdentifier("login.notice")
                    } else if viewModel.isSubmitting {
                        Text(viewModel.mode == .register ? "正在注册…" : "正在登录…")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)
                    }

                    Button("登录") {
                        viewModel.signIn()
                    }
                    .buttonStyle(StudyPrimaryButtonStyle())
                    .disabled(!viewModel.canSubmit)
                    .padding(.top, 18)
                    .accessibilityIdentifier("login.submit")

                    Button("注册新账号") {
                        viewModel.register()
                    }
                    .buttonStyle(StudySecondaryButtonStyle())
                    .disabled(!viewModel.canSubmit)
                    .padding(.top, 10)
                    .accessibilityIdentifier("login.register")

                    Button("设置服务端地址") {
                        viewModel.presentServerSettings()
                    }
                    .buttonStyle(StudyTextButtonStyle(fullWidth: true))
                    .disabled(!viewModel.canSubmit)
                    .padding(.top, 6)
                    .accessibilityIdentifier("login.serverSettings")

                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 28)
                .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
            }
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .sheet(isPresented: $viewModel.isServerSheetPresented) {
            ServerSettingsSheet(
                serverDraft: $viewModel.serverDraft,
                serverDraftError: viewModel.serverDraftError,
                healthCheck: viewModel.healthCheck,
                onTestConnection: { viewModel.testConnection() },
                onSave: { viewModel.saveServer() }
            )
            .presentationDetents([.medium])
        }
    }
}

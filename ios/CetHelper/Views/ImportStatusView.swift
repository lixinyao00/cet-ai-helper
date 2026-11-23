import SwiftUI

struct ImportStatusView: View {
    let viewModel: PageImportViewModel
    let source: PageImportSource

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)

                    StudyHeading("导入材料")

                    switch viewModel.stage {
                    case .external:
                        ProgressView()
                            .tint(Color.studyAccent)
                            .padding(.top, 18)

                        Text(source == .camera ? "等待系统相机…" : "等待相册选择…")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)

                    case .recognizing:
                        ProgressView()
                            .tint(Color.studyAccent)
                            .padding(.top, 18)

                        Text(viewModel.statusMessage)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)

                    case .saving:
                        ProgressView()
                            .tint(Color.studyAccent)
                            .padding(.top, 18)

                        Text("正在保存…")
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)

                    case .error(let message):
                        Text(message)
                            .font(StudyFont.caption)
                            .foregroundStyle(Color.studyMuted)
                            .lineSpacing(3)
                            .padding(.top, 12)
                            .accessibilityIdentifier("import.error")

                        Button("重拍") {
                            viewModel.retry(with: .camera)
                        }
                        .buttonStyle(StudyPrimaryButtonStyle())
                        .disabled(!viewModel.isCameraAvailable)
                        .padding(.top, 18)

                        Button("重选图片") {
                            viewModel.retry(with: .photoLibrary)
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .padding(.top, 8)

                    case .review:
                        EmptyView()
                    }

                    if viewModel.stage != .saving {
                        Button("取消导入") {
                            viewModel.cancel()
                        }
                        .buttonStyle(StudyTextButtonStyle(fullWidth: true))
                        .padding(.top, 8)
                        .accessibilityIdentifier("import.cancel")
                    }

                    Spacer(minLength: 0)
                }
                .padding(24)
                .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
            }
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

import SwiftUI
import UIKit

struct ImportReviewView: View {
    @Bindable var viewModel: PageImportViewModel

    var body: some View {
        let cameraAvailable = UIImagePickerController.isSourceTypeAvailable(.camera)
        let isSaving = viewModel.stage == .saving
        let isBusy = isSaving || viewModel.isOrganizing

        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    StudyHeading("校对识别结果")

                    Text(viewModel.target == .reading ? "可修改文字和换行。用空行分隔段落。" : "请对照图片校对中文和标点，再开始作答。")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                        .padding(.top, 6)

                    if viewModel.showsTitleField {
                        TextField("文章标题", text: $viewModel.title)
                            .font(.system(size: 16))
                            .foregroundStyle(Color.studyInk)
                            .studyInputFieldStyle()
                            .disabled(isBusy)
                            .padding(.top, 16)
                            .accessibilityIdentifier("import.title")
                    }

                    if let image = viewModel.previewImage {
                        VStack(alignment: .leading, spacing: 0) {
                            HStack {
                                StudyBadge(text: "原图对照")
                                Spacer()
                                Button(viewModel.isPreviewExpanded ? "收起原图对照" : "展开原图对照") {
                                    viewModel.isPreviewExpanded.toggle()
                                }
                                .buttonStyle(StudyTextButtonStyle(minHeight: 36, horizontalPadding: 6))
                                .accessibilityIdentifier("import.togglePreview")
                            }

                            if viewModel.isPreviewExpanded {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxWidth: .infinity, maxHeight: 220)
                                    .padding(.top, 8)
                            }
                        }
                        .studyCard(padding: 12)
                        .padding(.top, 12)
                    }

                    HStack(spacing: 12) {
                        Button {
                            viewModel.organizeManually()
                        } label: {
                            Text("AI 自动整理")
                                .font(.system(size: 13, weight: .medium))
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .disabled(isBusy)
                        .accessibilityIdentifier("import.organize")

                        if viewModel.isRestoreRawVisible {
                            Button {
                                viewModel.restoreRaw()
                            } label: {
                                Text("还原原文")
                                    .font(.system(size: 13, weight: .medium))
                            }
                            .buttonStyle(StudySecondaryButtonStyle())
                            .disabled(isBusy)
                            .accessibilityIdentifier("import.restoreRaw")
                        }
                    }
                    .padding(.top, 12)

                    ZStack(alignment: .topLeading) {
                        if viewModel.bodyText.isEmpty {
                            Text(viewModel.target == .reading ? "文章正文" : "中文原文")
                                .font(.system(size: 16))
                                .foregroundStyle(Color.studyMuted)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 14)
                                .allowsHitTesting(false)
                        }

                        TextEditor(text: $viewModel.bodyText)
                            .font(.system(size: 16))
                            .foregroundStyle(Color.studyInk)
                            .scrollContentBackground(.hidden)
                            .padding(.horizontal, 11)
                            .padding(.vertical, 8)
                            .frame(minHeight: 168)
                            .disabled(isBusy)
                            .accessibilityIdentifier("import.body")
                    }
                    .background(
                        RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                            .fill(Color.studySurface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: StudyLayout.cardRadius, style: .continuous)
                            .stroke(Color.studyOutline, lineWidth: 1)
                    )
                    .padding(.top, 12)

                    HStack(spacing: 12) {
                        Button {
                            viewModel.appendPage(from: .camera)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "camera")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle((isBusy || !cameraAvailable) ? Color.studyMuted : Color.studyAccent)
                                Text("+ 追加拍一页")
                            }
                            .font(.system(size: 13, weight: .medium))
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .disabled(isBusy || !cameraAvailable)
                        .accessibilityIdentifier("import.appendCamera")

                        Button {
                            viewModel.appendPage(from: .photoLibrary)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "photo")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(isBusy ? Color.studyMuted : Color.studyAccent)
                                Text("+ 选图追加")
                            }
                            .font(.system(size: 13, weight: .medium))
                        }
                        .buttonStyle(StudySecondaryButtonStyle())
                        .disabled(isBusy)
                        .accessibilityIdentifier("import.appendPhotos")
                    }
                    .padding(.top, 12)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 16)
            }

            if let validation = viewModel.validationMessage {
                Text(validation)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyDanger)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .accessibilityIdentifier("import.error")
            } else if viewModel.isOrganizing {
                HStack(spacing: 8) {
                    ProgressView()
                        .tint(Color.studyAccent)
                    Text(viewModel.notice ?? "正在 AI 智能整理…")
                        .font(StudyFont.caption)
                        .foregroundStyle(Color.studyMuted)
                        .lineSpacing(3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
            } else if let notice = viewModel.notice {
                Text(notice)
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
            } else if isSaving {
                Text("正在保存…")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
            }

            HStack(spacing: 12) {
                Button("取消导入") {
                    viewModel.cancel()
                }
                .buttonStyle(StudySecondaryButtonStyle())
                .disabled(isSaving)
                .accessibilityIdentifier("import.cancel")

                Button(viewModel.target == .translation ? "开始作答" : "确认并保存") {
                    viewModel.confirm()
                }
                .buttonStyle(StudyPrimaryButtonStyle())
                .disabled(isBusy)
                .accessibilityIdentifier("import.confirm")
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 14)
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .studyConfirmationAlert($viewModel.confirmation)
    }
}

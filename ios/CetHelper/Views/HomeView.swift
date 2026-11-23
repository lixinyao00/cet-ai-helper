import SwiftUI
import UIKit

struct HomeView: View {
    let scope: SessionScope
    @Bindable private var home: HomeViewModel

    init(scope: SessionScope) {
        self.scope = scope
        self.home = scope.home
    }

    var body: some View {
        let cameraAvailable = UIImagePickerController.isSourceTypeAvailable(.camera)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                headerSection
                serverStatusBar
                    .padding(.top, 12)
                dashboardSection
                    .padding(.top, 14)
                quickActionsSection(cameraAvailable: cameraAvailable)
                    .padding(.top, 20)
                vocabularyBannerButton
                    .padding(.top, 12)
                demoArticleCard
                    .padding(.top, 18)
                materialsSection
                    .padding(.top, 22)
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 28)
        }
        .background(Color.studyPaper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            home.refresh()
        }
        .studyConfirmationAlert($home.confirmation)
        .sheet(isPresented: $home.isServerSheetPresented) {
            ServerSettingsSheet(
                serverDraft: $home.serverDraft,
                serverDraftError: home.serverDraftError,
                healthCheck: home.healthCheck,
                onTestConnection: { home.testConnection() },
                onSave: { home.saveServer() }
            )
            .presentationDetents([.medium])
        }
    }

    private var serverStatusBar: some View {
        HStack(alignment: .center, spacing: 10) {
            Button {
                home.probeHealth()
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(home.serverMode != nil ? Color.studySuccess : Color.studyMuted)
                            .frame(width: 8, height: 8)
                            .accessibilityHidden(true)

                        Text("\(home.serverUrl) · \(home.serverStatusLabel)")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color.studyInk)
                            .lineLimit(1)
                            .truncationMode(.middle)

                        if home.isProbingHealth {
                            ProgressView()
                                .controlSize(.mini)
                        }
                    }

                    if let probeMessage = home.probeMessage, !probeMessage.isEmpty {
                        Text(probeMessage)
                            .font(.system(size: 11))
                            .foregroundStyle(Color.studyMuted)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("home.serverStatus")

            Button("切换服务器") {
                home.presentServerSettings()
            }
            .buttonStyle(StudyCompactButtonStyle(isAccent: true))
            .accessibilityIdentifier("home.serverSettings")
        }
        .studyCard(padding: 12)
    }

    private var headerSection: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 6) {
                StudyHeading("四六级 AI 学习助手", size: 24)

                HStack(spacing: 8) {
                    StudyBadge(text: home.username)
                    StudyOutlinePill(text: connectionModeLabel)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button("退出登录") {
                scope.signOut()
            }
            .buttonStyle(StudyTextButtonStyle(horizontalPadding: 6))
            .accessibilityIdentifier("home.signOut")
        }
    }

    private var connectionModeLabel: String {
        guard let mode = home.serverMode else {
            return "离线 / 未探测"
        }
        if mode == "demo" {
            return "演示模式"
        }
        return "在线服务 (\(mode))"
    }

    private var dashboardSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("今日学习概览")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Color.studyAccent)

            HStack(spacing: 0) {
                statColumn(
                    value: home.stats.dueCount,
                    label: "待复习生词",
                    identifier: "home.stats.due"
                )

                Rectangle()
                    .fill(Color.studyOutline)
                    .frame(width: 1, height: 36)

                statColumn(
                    value: home.stats.favoriteCount,
                    label: "累计生词",
                    identifier: "home.stats.favorites"
                )

                Rectangle()
                    .fill(Color.studyOutline)
                    .frame(width: 1, height: 36)

                statColumn(
                    value: home.stats.articleCount,
                    label: "阅读篇章",
                    identifier: "home.stats.articles"
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyStatCard(padding: 16)
    }

    private func statColumn(value: Int, label: String, identifier: String) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(StudyFont.statValue)
                .foregroundStyle(Color.studyAccent)
                .monospacedDigit()
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(Color.studyMuted)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }

    private func quickActionsSection(cameraAvailable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("学习工作台")
                .font(StudyFont.sectionTitle)
                .foregroundStyle(Color.studyInk)

            VStack(spacing: 10) {
                HStack(spacing: 12) {
                    workbenchTile(
                        title: "拍照导入",
                        icon: "camera",
                        enabled: cameraAvailable,
                        identifier: "home.import.camera"
                    ) {
                        scope.push(.pageImport(target: .reading, source: .camera))
                    }

                    workbenchTile(
                        title: "从相册导入",
                        icon: "photo.on.rectangle",
                        identifier: "home.import.photos"
                    ) {
                        scope.push(.pageImport(target: .reading, source: .photoLibrary))
                    }
                }

                HStack(spacing: 12) {
                    workbenchTile(
                        title: "汉译英",
                        icon: "character.book.closed",
                        identifier: "home.translation"
                    ) {
                        scope.push(.translationSource)
                    }

                    workbenchTile(
                        title: "写作练习",
                        icon: "square.and.pencil",
                        identifier: "home.writing"
                    ) {
                        scope.push(.writing)
                    }
                }

                HStack(spacing: 12) {
                    workbenchTile(
                        title: "查词",
                        icon: "magnifyingglass",
                        identifier: "home.dictionary"
                    ) {
                        scope.selectTab(.dictionary)
                    }

                    workbenchTile(
                        title: "AI 助教",
                        icon: "sparkles",
                        identifier: "home.tutor"
                    ) {
                        scope.selectTab(.tutor)
                    }
                }
            }
        }
    }

    private func workbenchTile(
        title: String,
        icon: String,
        enabled: Bool = true,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(enabled ? Color.studyAccent : Color.studyMuted)
                Text(title)
                    .font(.system(size: 14, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .frame(height: StudyLayout.tileHeight)
        }
        .buttonStyle(StudySecondaryButtonStyle())
        .disabled(!enabled)
        .accessibilityIdentifier(identifier)
    }

    private var vocabularyBannerButton: some View {
        Button {
            scope.push(.vocabulary)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "book.closed.fill")
                    .font(.system(size: 18, weight: .medium))
                Text("生词本")
                    .font(.system(size: 16, weight: .medium))
            }
            .frame(height: 54)
        }
        .buttonStyle(StudyPrimaryButtonStyle())
        .accessibilityIdentifier("home.vocabulary")
    }

    private var demoArticleCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            StudyBadge(text: "示例文章")

            Text(home.demoArticle.title)
                .font(StudyFont.serif(22))
                .foregroundStyle(Color.studyInk)
                .padding(.top, 8)

            Button("打开示例文章") {
                scope.push(.reader(articleId: home.demoArticle.id, focus: nil))
            }
            .buttonStyle(StudySecondaryButtonStyle())
            .padding(.top, 12)
            .accessibilityIdentifier("home.demoCard")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .studyCard(padding: 16)
    }

    private var materialsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("我的材料")
                .font(StudyFont.sectionTitle)
                .foregroundStyle(Color.studyInk)

            if home.articles.isEmpty {
                Text("还没有导入材料。")
                    .font(StudyFont.caption)
                    .foregroundStyle(Color.studyMuted)
                    .padding(.top, 8)
            } else {
                VStack(spacing: 10) {
                    ForEach(home.articles) { article in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .center, spacing: 8) {
                                Button {
                                    scope.push(.reader(articleId: article.articleId, focus: nil))
                                } label: {
                                    HStack(alignment: .center, spacing: 10) {
                                        Image(systemName: "book.closed.fill")
                                            .font(.system(size: 16, weight: .medium))
                                            .foregroundStyle(Color.studyAccent)
                                        Text(article.title)
                                            .font(.system(size: 15, weight: .medium))
                                            .foregroundStyle(Color.studyInk)
                                            .multilineTextAlignment(.leading)
                                    }
                                    .padding(.leading, 4)
                                    .padding(.trailing, 8)
                                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("home.article.\(article.articleId)")

                                Button {
                                    home.requestDelete(article)
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: "trash")
                                            .font(.system(size: 13, weight: .medium))
                                        Text("删除")
                                    }
                                }
                                .buttonStyle(StudyDangerTextButtonStyle(minHeight: 40, horizontalPadding: 6, fontSize: 13))
                                .accessibilityIdentifier("home.article.delete.\(article.articleId)")
                            }

                            Text(home.formattedMeta(for: article))
                                .font(.system(size: 12))
                                .foregroundStyle(Color.studyMuted)
                                .padding(.leading, 30)
                                .padding(.trailing, 4)
                        }
                        .padding(.leading, 12)
                        .padding(.trailing, 10)
                        .padding(.top, 6)
                        .padding(.bottom, 8)
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
                .padding(.top, 10)
            }
        }
    }
}

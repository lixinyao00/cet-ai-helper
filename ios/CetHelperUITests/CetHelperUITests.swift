import XCTest

/// iOS 27.2 模拟器端到端冒烟。
///
/// 统一启动参数：`-server_url http://127.0.0.1:8080 -cet_reset_state YES`。
/// 运行：
/// ```sh
/// xcodebuild -project CetHelper.xcodeproj -scheme CetHelper \
///   -destination "platform=iOS Simulator,id=$SIM_ID" -derivedDataPath build \
///   -resultBundlePath build/E2E.xcresult CODE_SIGNING_ALLOWED=NO \
///   -only-testing:CetHelperUITests test
/// ```
/// 深色模式：先 `xcrun simctl ui $SIM_ID appearance dark`，再用
/// `TEST_RUNNER_E2E_APPEARANCE=dark` 运行（截图名带 -dark 后缀）。
class E2EBase: XCTestCase {
    var app: XCUIApplication!
    var baseUrl = "http://127.0.0.1:8080"
    let proxyBaseUrl = "http://127.0.0.1:8090"
    let controlBaseUrl = "http://127.0.0.1:8091"
    let password = "Passw0rd!23"
    var username = ""

    var appearance: String {
        ProcessInfo.processInfo.environment["E2E_APPEARANCE"] ?? "light"
    }

    override func setUp() {
        continueAfterFailure = false
        username = "ui\(Int(Date().timeIntervalSince1970))\(Int.random(in: 100...999))"
        app = launchApp(reset: true)
    }

    @discardableResult
    func launchApp(reset: Bool) -> XCUIApplication {
        let application = XCUIApplication()
        application.launchArguments = reset
            ? ["-server_url", baseUrl, "-cet_reset_state", "YES"]
            : ["-server_url", baseUrl]
        application.launch()
        return application
    }

    // MARK: - Screenshots

    func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "\(name)-\(appearance)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    // MARK: - Waits

    /// 跨元素类型按 accessibility identifier 查找（SwiftUI 的 identifier 可能落在
    /// StaticText / Other / Button 等不同节点上）。
    func any(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    @discardableResult
    func waitFor(_ element: XCUIElement, contains text: String, timeout: TimeInterval = 15) -> Bool {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// TextView / TextField 的正文在 `value` 上，用 value 断言。
    @discardableResult
    func waitForValue(_ element: XCUIElement, contains text: String, timeout: TimeInterval = 15) -> Bool {
        let predicate = NSPredicate(format: "value CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// 页面上任意静态文本包含指定片段（用于重复 identifier 的列表项）。
    func waitForAnyText(_ text: String, timeout: TimeInterval = 15) -> Bool {
        let query = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text))
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if query.count > 0 { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        return query.count > 0
    }

    /// 代理控制接口（E12/E13）。
    @discardableResult
    func control(_ path: String, method: String = "POST") -> (status: Int, body: String)? {
        guard let url = URL(string: controlBaseUrl + path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 5
        var output: (Int, String)?
        let semaphore = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, _ in
            if let http = response as? HTTPURLResponse {
                output = (http.statusCode, data.flatMap { String(data: $0, encoding: .utf8) } ?? "")
            }
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 8)
        return output.map { (status: $0.0, body: $0.1) }
    }

    func requireProxy() throws {
        let health = control("/health", method: "GET")
        guard health?.status == 200 else {
            throw XCTSkip("E2E 控制代理未运行：先执行 python3 Tools/e2e_proxy.py")
        }
    }

    func waitForNoText(_ text: String, timeout: TimeInterval = 15) -> Bool {
        let query = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text))
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if query.count == 0 { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        return query.count == 0
    }

    /// 对齐 Android 后各页改用页内自定义顶栏，返回按钮统一标识为 `nav.back`。
    /// 系统导航栏（服务端设置、历史批改等 Sheet）作为兜底。
    func back() {
        let custom = app.buttons["nav.back"]
        if custom.waitForExistence(timeout: 5), custom.isHittable {
            custom.tap()
            return
        }
        let button = app.navigationBars.buttons.firstMatch
        if button.waitForExistence(timeout: 2) {
            button.tap()
        }
    }

    /// 连续返回直到回到 L1 首页（最多 maxBacks 次），兼容自定义顶栏未命中的兜底。
    func popToHome(maxBacks: Int = 6) {
        for _ in 0..<maxBacks {
            if app.buttons["home.demoCard"].exists || app.buttons["home.writing"].exists {
                return
            }
            // 部分页面（汉译英/写作）的自定义顶栏随 ScrollView 滚动，先回顶部再返回。
            scrollToTop(swipes: 4)
            back()
        }
    }

    /// 长页面先滚回顶部，便于点击自定义顶栏按钮。
    func scrollToTop(swipes: Int = 5) {
        for _ in 0..<swipes {
            app.swipeDown()
        }
    }

    /// 向下滚动直到元素可点（长表单、复盘卡片）。
    func scrollUntilHittable(_ element: XCUIElement, maxSwipes: Int = 10) {
        var swipes = 0
        while !element.isHittable && swipes < maxSwipes {
            app.swipeUp()
            swipes += 1
        }
    }

    func dismissKeyboard() {
        guard app.keyboards.count > 0 else { return }
        for name in ["return", "Return", "换行", "完成"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable {
                button.tap()
                return
            }
        }
        let key = app.keyboards.keys["return"]
        if key.exists && key.isHittable {
            key.tap()
        }
    }

    /// 键盘遮挡时先滚动，直到按钮可点。
    func tapButton(_ identifier: String) {
        let button = app.buttons[identifier]
        guard button.waitForExistence(timeout: 10) else {
            XCTFail("找不到按钮 \(identifier)")
            return
        }
        if button.isHittable {
            button.tap()
            return
        }
        for _ in 0..<4 {
            app.swipeUp()
            if button.isHittable {
                button.tap()
                return
            }
        }
        button.tap()
    }

    func clear(_ element: XCUIElement) {
        element.tap()
        guard let current = element.value as? String, !current.isEmpty else { return }
        let placeholder = (element.placeholderValue ?? "")
        guard current != placeholder else { return }
        element.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
    }

    // MARK: - Sign in

    /// 注册一个一次性账号并完成登录，等待首页统计卡片出现。
    /// 新登录页把「登录」「注册新账号」拆成两个并列按钮，不再有模式切换。
    func signIn() {
        let userField = app.textFields["login.username"]
        XCTAssertTrue(userField.waitForExistence(timeout: 25), "冷启动应进入登录页")

        userField.tap()
        userField.typeText(username)

        let passwordField = app.secureTextFields["login.password"]
        passwordField.tap()
        passwordField.typeText(password)

        app.buttons["login.register"].tap()
        XCTAssertTrue(
            app.staticTexts["注册成功，请登录。"].waitForExistence(timeout: 20),
            "注册成功后应显示提示"
        )
        app.buttons["login.submit"].tap()
        XCTAssertTrue(
            any("home.stats.articles").waitForExistence(timeout: 20),
            "登录后应进入首页"
        )
    }

    // MARK: - Reader helpers

    func openDemoArticle() {
        app.buttons["home.demoCard"].tap()
        XCTAssertTrue(
            app.textViews["reader.paragraph.0"].waitForExistence(timeout: 15),
            "应打开示例文章"
        )
    }

    /// 在示例文章第一段点击 decline（UTF-16 26..33）。
    func tapDecline() {
        app.textViews["reader.paragraph.0"]
            .coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.38))
            .tap()
    }

    func saveDeclineFavorite() {
        openDemoArticle()
        tapDecline()
        XCTAssertTrue(any("reader.panel").waitForExistence(timeout: 15))
        let save = app.buttons["reader.panel.save"]
        XCTAssertTrue(save.waitForExistence(timeout: 15), "查词成功后应可收藏")
        save.tap()
        XCTAssertTrue(waitFor(save, contains: "已收藏", timeout: 10), "收藏后按钮应变为已收藏")
        app.buttons["reader.panel.close"].tap()
        back()
        XCTAssertTrue(any("home.stats.favorites").waitForExistence(timeout: 10))
    }
}

// MARK: - E1 / E2 / E6 / E11

final class CetHelperSmokeUITests: E2EBase {

    func testE1ColdStartServerSettings() {
        XCTAssertTrue(app.textFields["login.username"].waitForExistence(timeout: 25))
        XCTAssertEqual(app.secureTextFields["login.password"].exists, true)
        snap("E1a-login-light")

        app.buttons["login.serverSettings"].tap()
        let urlField = app.textFields["server.url"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 10), "应打开服务端设置")
        urlField.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        urlField.typeText("/")
        snap("E1b-server-sheet")

        app.buttons["server.test"].tap()
        let health = app.staticTexts["server.health"]
        XCTAssertTrue(health.waitForExistence(timeout: 20), "健康探测应有结果")
        XCTAssertTrue(waitFor(health, contains: "连接成功", timeout: 20), "demo 服务端健康探测应为 UP")
        XCTAssertTrue(waitFor(health, contains: "Demo", timeout: 5), "健康探测应标明 Demo 模式")
        snap("E1c-health-demo")

        app.buttons["server.save"].tap()
        XCTAssertTrue(
            app.staticTexts["服务端地址已保存。"].waitForExistence(timeout: 10),
            "保存后应提示服务端地址已保存"
        )
        // 重新打开设置，确认保存时已规范化（去掉尾斜杠）。
        app.buttons["login.serverSettings"].tap()
        XCTAssertTrue(urlField.waitForExistence(timeout: 10))
        XCTAssertEqual(
            urlField.value as? String, "http://127.0.0.1:8080",
            "保存后应显示规范化（无尾斜杠）地址"
        )
        app.buttons["取消"].tap()
    }

    func testE2RegistrationValidationAndHome() {
        let userField = app.textFields["login.username"]
        XCTAssertTrue(userField.waitForExistence(timeout: 25))

        userField.tap()
        userField.typeText("1bad")
        app.secureTextFields["login.password"].tap()
        app.secureTextFields["login.password"].typeText("short")
        app.buttons["login.register"].tap()

        let error = app.staticTexts["login.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(error, contains: "用户名需为 3–32 位"), "用户名前置校验文案应与 §1.2 一致")
        snap("E2a-invalid-username")

        clear(userField)
        userField.typeText(username)
        let passwordField = app.secureTextFields["login.password"]
        clear(passwordField)
        passwordField.typeText("short")
        app.buttons["login.register"].tap()
        XCTAssertTrue(waitFor(error, contains: "密码需至少 8 位", timeout: 10), "密码前置校验文案应与 §1.2 一致")
        snap("E2b-invalid-password")

        clear(passwordField)
        passwordField.typeText(password)
        app.buttons["login.register"].tap()
        XCTAssertTrue(app.staticTexts["注册成功，请登录。"].waitForExistence(timeout: 20))
        app.buttons["login.submit"].tap()

        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["演示模式"].waitForExistence(timeout: 10), "首页应显示 demo 徽章")
        XCTAssertTrue(waitFor(any("home.stats.articles"), contains: "0", timeout: 10))
        XCTAssertTrue(waitFor(any("home.stats.favorites"), contains: "0", timeout: 10))
        XCTAssertTrue(waitFor(any("home.stats.due"), contains: "0", timeout: 10))
        snap("E2c-home-empty")
    }

    func testE6FontTierAndScrollPersistence() {
        signIn()
        openDemoArticle()

        let fontButton = app.buttons["reader.fontTier"]
        XCTAssertTrue(waitFor(fontButton, contains: "字号·标准", timeout: 10))
        fontButton.tap()
        XCTAssertTrue(waitFor(fontButton, contains: "字号·大", timeout: 10))
        fontButton.tap()
        XCTAssertTrue(waitFor(fontButton, contains: "字号·超大", timeout: 10))
        snap("E6a-font-extra-large")

        app.swipeUp()
        snap("E6b-scrolled")

        app.terminate()
        app = launchApp(reset: false)
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 20), "重启应恢复会话")
        openDemoArticle()
        XCTAssertTrue(waitFor(app.buttons["reader.fontTier"], contains: "字号·超大", timeout: 10), "字号档位应持久")
        snap("E6c-font-restored")
    }

    func testE11DraftPersistence() {
        signIn()

        // 汉译英草稿
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        app.buttons["translation.useDemo"].tap()
        app.buttons["translation.start"].tap()
        let draft = app.textViews["translation.draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 10))
        draft.tap()
        draft.typeText("Draft persisted across a cold restart.")
        dismissKeyboard()

        // 写作草稿：用演示草稿再加一行
        back()
        back()
        XCTAssertTrue(app.buttons["home.writing"].waitForExistence(timeout: 10))
        app.buttons["home.writing"].tap()
        app.buttons["writing.useDemo"].tap()
        let essay = app.textViews["writing.essay"]
        XCTAssertTrue(essay.waitForExistence(timeout: 10))
        essay.tap()
        essay.typeText("\nPersisted writing line.")
        dismissKeyboard()
        snap("E11a-drafts-entered")

        XCUIDevice.shared.press(.home)
        app.terminate()
        app = launchApp(reset: false)
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 20))

        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.start"].waitForExistence(timeout: 10))
        app.buttons["translation.start"].tap()
        XCTAssertTrue(
            waitForValue(app.textViews["translation.draft"], contains: "Draft persisted across a cold restart.", timeout: 10),
            "汉译英草稿应恢复"
        )
        back()
        back()

        app.buttons["home.writing"].tap()
        XCTAssertTrue(
            waitForValue(app.textViews["writing.essay"], contains: "Persisted writing line.", timeout: 10),
            "写作草稿应恢复"
        )
        snap("E11b-drafts-restored")
    }
}

// MARK: - E3 / E5 / E7 / E8 / E16

final class CetHelperReaderUITests: E2EBase {

    func testE3LookupAndSaveFavorite() {
        signIn()
        openDemoArticle()
        snap("E3a-reader")

        tapDecline()
        XCTAssertTrue(any("reader.panel").waitForExistence(timeout: 15), "点词应打开查词抽屉")
        XCTAssertTrue(app.staticTexts["reader.panel.meaning"].waitForExistence(timeout: 20), "应返回释义")
        XCTAssertTrue(app.staticTexts["reader.panel.meta"].exists, "应显示音标/词性等 meta")
        XCTAssertTrue(app.staticTexts["decline"].exists, "点中的单词应为 decline")
        XCTAssertTrue(app.staticTexts["演示释义"].exists, "demo 模式应显示演示释义徽章")
        snap("E3b-lookup-decline")

        app.buttons["reader.panel.save"].tap()
        XCTAssertTrue(waitFor(app.buttons["reader.panel.save"], contains: "已收藏", timeout: 10))
        snap("E3c-saved-highlight")
        app.buttons["reader.panel.close"].tap()

        back()
        XCTAssertTrue(any("home.stats.favorites").waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(any("home.stats.favorites"), contains: "1", timeout: 10), "首页生词数应为 1")
        snap("E3d-home-stats")
    }

    func testE4CrossSentenceSelectionRejected() {
        signIn()

        // 先用相册导入的长文章制造同段多句文本。
        app.buttons["home.import.photos"].tap()
        XCTAssertTrue(app.images.firstMatch.waitForExistence(timeout: 15))
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.84, dy: 0.40)).tap()
        let body = app.textViews["import.body"]
        XCTAssertTrue(body.waitForExistence(timeout: 25))
        XCTAssertTrue(waitForValue(body, contains: "decline", timeout: 15))
        let titleField = app.textFields["import.title"]
        titleField.tap()
        titleField.typeText("Cross Sentence Fixture")
        dismissKeyboard()
        tapButton("import.confirm")
        XCTAssertTrue(app.textViews["reader.paragraph.0"].waitForExistence(timeout: 25))

        // 长按后拖过句号，形成跨句选区。
        let paragraph = app.textViews["reader.paragraph.0"]
        paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0.06, dy: 0.10))
            .press(forDuration: 1.2, thenDragTo: paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.85)))
        sleep(1)
        snap("E4a-selection")

        let lookup = app.buttons["查释义"]
        if lookup.waitForExistence(timeout: 5) {
            lookup.tap()
        } else {
            // 部分系统版本把自定义菜单项挂到 menuItems。
            let item = app.menuItems["查释义"]
            XCTAssertTrue(item.waitForExistence(timeout: 5), "应出现查释义菜单")
            item.tap()
        }

        let notice = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "请在同一句中选择单词或词组。"))
        XCTAssertTrue(notice.firstMatch.waitForExistence(timeout: 10), "跨句选区应提示同句选择")
        XCTAssertFalse(app.buttons["reader.panel.save"].exists, "跨句选区不应出现收藏按钮")
        XCTAssertFalse(app.buttons["reader.panel.analyze"].exists, "跨句选区不应出现整句分析")
        snap("E4b-notice")
    }

    func testE5SentenceAnalysis() {
        signIn()
        openDemoArticle()

        app.textViews["reader.paragraph.1"]
            .coordinate(withNormalizedOffset: CGVector(dx: 0.37, dy: 0.5))
            .tap()
        XCTAssertTrue(any("reader.panel").waitForExistence(timeout: 15))
        let analyze = app.buttons["reader.panel.analyze"]
        XCTAssertTrue(analyze.waitForExistence(timeout: 15), "应可分析整句")
        snap("E5a-panel")
        analyze.tap()

        XCTAssertTrue(any("reader.analysis").waitForExistence(timeout: 20), "应打开长难句抽屉")
        XCTAssertTrue(app.buttons["reader.analysis.chunk.0"].waitForExistence(timeout: 10), "应显示片段高亮")
        snap("E5b-analysis")
    }

    func testE7VocabularySearchOpenAndRemove() {
        signIn()
        saveDeclineFavorite()

        app.buttons["home.vocabulary"].tap()
        XCTAssertTrue(app.textFields["vocab.search"].waitForExistence(timeout: 10))
        snap("E7a-vocabulary")

        app.textFields["vocab.search"].tap()
        app.textFields["vocab.search"].typeText("decl")
        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10))
        snap("E7b-search")

        app.buttons["vocab.open.0"].tap()
        XCTAssertTrue(app.textViews["reader.paragraph.0"].waitForExistence(timeout: 10), "回原文应打开阅读页")
        snap("E7c-back-to-reader")
        back()

        XCTAssertTrue(app.buttons["vocab.remove.0"].waitForExistence(timeout: 10))
        app.buttons["vocab.remove.0"].tap()
        let confirm = app.alerts.buttons["移除"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "移除应有二次确认")
        snap("E7d-remove-confirm")
        confirm.tap()
        XCTAssertTrue(app.staticTexts["还没有收藏。请先到阅读页收藏单词或词组。"].waitForExistence(timeout: 10))
        snap("E7e-vocabulary-empty")
    }

    func testE8ClozeSrsFlow() {
        signIn()
        saveDeclineFavorite()

        // 新首页把原句填空收进生词本，先进入生词本再开始。
        app.buttons["home.vocabulary"].tap()
        let startCloze = app.buttons["vocab.startCloze"]
        XCTAssertTrue(startCloze.waitForExistence(timeout: 10), "生词本应有原句填空入口")
        startCloze.tap()

        let title = app.staticTexts["cloze.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 15), "应生成填空题目")
        XCTAssertTrue(app.staticTexts["cloze.sentence"].exists)
        XCTAssertFalse(app.staticTexts["cloze.hint"].exists, "首字母提示默认收起")

        app.buttons["cloze.hintButton"].tap()
        XCTAssertTrue(app.staticTexts["cloze.hint"].waitForExistence(timeout: 5), "点击首字母提示应显示提示")
        XCTAssertTrue(waitFor(app.staticTexts["cloze.hint"], contains: "提示：", timeout: 5))
        snap("E8a-question")

        app.buttons["cloze.submit"].tap()
        XCTAssertTrue(app.staticTexts["请输入单词或词组。"].waitForExistence(timeout: 10))

        let input = app.textFields["cloze.input"]
        input.tap()
        input.typeText("declines")
        tapButton("cloze.submit")
        let feedback = app.staticTexts["cloze.feedback"]
        XCTAssertTrue(feedback.waitForExistence(timeout: 10), "提交后应出现结果卡")
        XCTAssertTrue(waitFor(feedback, contains: "答错了", timeout: 10))
        XCTAssertTrue(app.buttons["cloze.next"].exists, "结果卡应提供下一题/完成练习")
        snap("E8b-wrong")

        tapButton("cloze.next")
        let summary = app.staticTexts["cloze.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(summary, contains: "独立答对 0 / 1", timeout: 10), "答错不应计入独立答对")
        XCTAssertTrue(app.buttons["cloze.retryWrong"].exists)
        snap("E8c-summary")

        app.buttons["cloze.retryWrong"].tap()
        XCTAssertTrue(app.staticTexts["cloze.title"].waitForExistence(timeout: 10))
        snap("E8d-retry-wrong")

        // 重练错题：这次答对，独立答对应为 1 / 1。
        let retryInput = app.textFields["cloze.input"]
        XCTAssertTrue(retryInput.waitForExistence(timeout: 10))
        retryInput.tap()
        retryInput.typeText("decline")
        tapButton("cloze.submit")
        XCTAssertTrue(feedback.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(feedback, contains: "答对了", timeout: 10))
        snap("E8e-correct")

        tapButton("cloze.next")
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(summary, contains: "独立答对 1 / 1", timeout: 10))
        snap("E8f-final-summary")
    }

    func testE14PhotoImportReadingAndTranslation() {
        signIn()

        // —— 中文图导入阅读目标 → 报英文识别错误 ——
        app.buttons["home.import.photos"].tap()
        XCTAssertTrue(app.images.firstMatch.waitForExistence(timeout: 15), "应打开系统相册")
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.16, dy: 0.40)).tap()
        let importError = app.staticTexts["import.error"]
        XCTAssertTrue(importError.waitForExistence(timeout: 25), "中文图片导入阅读应报错")
        XCTAssertTrue(waitFor(importError, contains: "未识别到英文文字", timeout: 5))
        snap("E14a-wrong-language")
        app.buttons["import.cancel"].tap()

        // —— 英文文章导入 + 原图对照 + 追加一页 + 保存 ——
        app.buttons["home.import.photos"].tap()
        XCTAssertTrue(app.images.firstMatch.waitForExistence(timeout: 15))
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.84, dy: 0.40)).tap()

        let body = app.textViews["import.body"]
        XCTAssertTrue(body.waitForExistence(timeout: 25), "应进入校对页")
        XCTAssertTrue(waitForValue(body, contains: "decline", timeout: 15), "英文页应识别出 decline 原文")
        XCTAssertTrue(app.buttons["import.togglePreview"].exists, "应有原图对照入口")
        XCTAssertTrue(app.buttons["import.appendPhotos"].exists, "应有选图追加入口")
        XCTAssertFalse(app.buttons["import.appendCamera"].isEnabled, "模拟器无相机，拍照追加应禁用")
        snap("E14b-review-english")

        let toggle = app.buttons["import.togglePreview"]
        XCTAssertTrue(toggle.label.contains("收起"), "原图对照默认展开，可折叠")
        snap("E14c-preview-expanded")
        toggle.tap()
        XCTAssertTrue(waitFor(toggle, contains: "展开", timeout: 5), "再次点击应收起原图对照")
        toggle.tap()
        XCTAssertTrue(waitFor(toggle, contains: "收起", timeout: 5))

        app.buttons["import.appendPhotos"].tap()
        XCTAssertTrue(app.images.firstMatch.waitForExistence(timeout: 15))
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.50, dy: 0.40)).tap()
        XCTAssertTrue(waitForValue(body, contains: "river", timeout: 30), "追加页后正文应拼入新页文字")
        snap("E14d-appended-page")

        let titleField = app.textFields["import.title"]
        XCTAssertTrue(titleField.waitForExistence(timeout: 10))
        titleField.tap()
        titleField.typeText("Imported Reading Page")
        dismissKeyboard()
        tapButton("import.confirm")

        XCTAssertTrue(app.textViews["reader.paragraph.0"].waitForExistence(timeout: 25), "保存后应进入阅读页")
        snap("E14e-imported-reader")
        back()

        // —— 中文材料导入汉译英 ——
        XCTAssertTrue(app.buttons["home.translation"].waitForExistence(timeout: 10))
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["拍照导入"].isEnabled, "模拟器无相机，拍照导入应禁用")
        app.buttons["从相册导入"].tap()

        XCTAssertTrue(app.images.firstMatch.waitForExistence(timeout: 15))
        sleep(2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.16, dy: 0.40)).tap()

        XCTAssertTrue(app.textViews["import.body"].waitForExistence(timeout: 25), "中文图应进入校对页")
        XCTAssertTrue(waitForValue(app.textViews["import.body"], contains: "数字经济的快速发展", timeout: 15))
        snap("E14f-review-chinese")
        tapButton("import.confirm")

        XCTAssertTrue(app.buttons["translation.hint.keyword"].waitForExistence(timeout: 30), "中文图保存后应进入汉译英作答页")
        XCTAssertTrue(waitForAnyText("数字经济的快速发展", timeout: 10), "中文原文应被识别")
        snap("E14g-translation-imported")
    }

    func testE12OfflineBannerAndRecovery() throws {
        try requireProxy()

        app.terminate()
        baseUrl = proxyBaseUrl
        app = launchApp(reset: true)
        signIn()

        // 留一份译文草稿，验证离线启动后输入仍在。
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        app.buttons["translation.useDemo"].tap()
        app.buttons["translation.start"].tap()
        let draft = app.textViews["translation.draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 10))
        draft.tap()
        draft.typeText("Offline draft survives.")
        dismissKeyboard()
        back()
        back()
        XCTAssertTrue(app.buttons["home.translation"].waitForExistence(timeout: 10))

        // 停服（代理暂停）→ 杀进程重启 → 缓存会话进入离线模式。
        XCTAssertEqual(control("/pause")?.status, 200)
        app.terminate()
        app = launchApp(reset: false)
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 25), "离线启动应使用缓存会话进入首页")
        XCTAssertTrue(waitForAnyText("离线模式 · 正在等待服务恢复", timeout: 10), "应显示离线横幅")
        snap("E12a-offline")

        // 当前页输入保留。
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.start"].waitForExistence(timeout: 10))
        app.buttons["translation.start"].tap()
        XCTAssertTrue(
            waitForValue(app.textViews["translation.draft"], contains: "Offline draft survives.", timeout: 10),
            "离线状态应保留当前草稿"
        )
        back()
        back()

        // 恢复服务端 → 回前台触发复验 → 横幅消失。
        XCTAssertEqual(control("/resume")?.status, 200)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(waitForNoText("离线模式", timeout: 25), "服务恢复后离线横幅应消失")
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 10))
        snap("E12b-recovered")
    }

    func testE13GlobalLogoutOnUnauthorized() throws {
        try requireProxy()

        app.terminate()
        baseUrl = proxyBaseUrl
        app = launchApp(reset: true)
        signIn()

        // 清空代理缓存的旧 token，再重启触发 /me，让代理抓到本次 Bearer token。
        XCTAssertEqual(control("/reset")?.status, 200)
        app.terminate()
        app = launchApp(reset: false)
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 25))
        var hasToken = false
        for _ in 0..<20 {
            if control("/health", method: "GET")?.body.contains("\"hasToken\":true") == true
                || control("/health", method: "GET")?.body.contains("\"hasToken\": true") == true {
                hasToken = true
                break
            }
            sleep(1)
        }
        XCTAssertTrue(hasToken, "代理应已捕获 App 的 Bearer token")

        XCTAssertEqual(control("/revoke")?.status, 200, "应能用 App 的 token 撤销会话")

        // 任意业务请求触发 401 → 全局登出。
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        app.buttons["translation.useDemo"].tap()
        app.buttons["translation.start"].tap()
        tapButton("translation.hint.keyword")

        XCTAssertTrue(app.textFields["login.username"].waitForExistence(timeout: 25), "401 应回到登录页")
        XCTAssertTrue(waitForAnyText("会话已失效，请重新登录。", timeout: 10), "应提示会话失效")
        snap("E13-global-logout")
    }

    func testE16OutOfOrderLookupKeepsLatestSelection() {
        signIn()
        openDemoArticle()

        let paragraph = app.textViews["reader.paragraph.0"]
        paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.38)).tap()
        paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0.11, dy: 0.78)).tap()

        let panel = any("reader.panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 15))
        XCTAssertTrue(
            panel.staticTexts["demand"].waitForExistence(timeout: 20),
            "快速连续点词时抽屉只应显示最后一次选词"
        )
        snap("E16-latest-selection")
    }
}

// MARK: - E17 / E18 / E19 / E20（v4 四栏导航、查词、生词本与 AI 助教）

final class CetHelperV4UITests: E2EBase {

    private let demoTranslationDraft = "With the rapid development of the digital economy, programming skills are very important for students. Many companies prefer to hire young people who can think independently and have good team cooperation."

    /// 在查词 Tab 内完成一次 abandon 检索并返回详情页。
    private func lookupAbandonInDictionaryTab() {
        XCTAssertTrue(app.buttons["tab.dictionary"].waitForExistence(timeout: 10))
        app.buttons["tab.dictionary"].tap()
        let search = app.textFields["dict.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10), "查词 Tab 应有搜索框")
        search.tap()
        search.typeText("abandon")

        let suggestion = app.buttons["dict.suggestion.0"]
        XCTAssertTrue(suggestion.waitForExistence(timeout: 20), "输入 abandon 应出现联想")
        XCTAssertTrue(waitFor(suggestion, contains: "abandon", timeout: 5))
        suggestion.tap()

        let word = any("dict.word")
        XCTAssertTrue(word.waitForExistence(timeout: 20), "点选联想后应展示词条详情")
        XCTAssertTrue(waitFor(word, contains: "abandon", timeout: 5))
    }

    /// 把当前 abandon 词条加入生词本。
    private func favoriteCurrentDictionaryEntry() {
        let favorite = app.buttons["dict.favorite"]
        scrollUntilHittable(favorite)
        XCTAssertTrue(favorite.waitForExistence(timeout: 10))
        if !favorite.label.contains("已在生词本") {
            favorite.tap()
        }
        XCTAssertTrue(waitFor(favorite, contains: "已在生词本", timeout: 10), "收藏后应变为已在生词本")
    }

    func testE17FourTabNavigationAndHomeServerStatus() {
        signIn()

        for identifier in ["tab.home", "tab.dictionary", "tab.tutor", "tab.vocabulary"] {
            XCTAssertTrue(app.buttons[identifier].waitForExistence(timeout: 10), "L1 应显示 \(identifier)")
        }

        // 首页服务状态栏主动探测 + 通用服务端设置 Sheet。
        let status = app.buttons["home.serverStatus"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        status.tap()
        XCTAssertTrue(waitFor(status, contains: "Demo", timeout: 20), "探测后状态应显示 Demo 演示模式")
        snap("E17a-server-status")

        app.buttons["home.serverSettings"].tap()
        XCTAssertTrue(app.textFields["server.url"].waitForExistence(timeout: 10), "应打开通用服务端设置")
        app.buttons["server.test"].tap()
        let health = app.staticTexts["server.health"]
        XCTAssertTrue(health.waitForExistence(timeout: 20))
        XCTAssertTrue(waitFor(health, contains: "连接成功", timeout: 20))
        snap("E17b-server-settings")
        app.buttons["取消"].tap()

        // 进入 L2 后底栏完全隐藏，返回 L1 后恢复。
        app.buttons["home.demoCard"].tap()
        XCTAssertTrue(app.textViews["reader.paragraph.0"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["tab.home"].exists, "L2 阅读页不应显示底部四栏")
        snap("E17c-l2-tabs-hidden")
        back()
        XCTAssertTrue(app.buttons["tab.home"].waitForExistence(timeout: 10), "返回 L1 后底栏应恢复")

        // 四栏切换。
        app.buttons["tab.dictionary"].tap()
        XCTAssertTrue(app.textFields["dict.search"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["tab.dictionary"].exists)
        snap("E17d-tab-dictionary")

        app.buttons["tab.tutor"].tap()
        XCTAssertTrue(any("tutor.messages").waitForExistence(timeout: 10))
        snap("E17e-tab-tutor")

        app.buttons["tab.vocabulary"].tap()
        XCTAssertTrue(app.textFields["vocab.search"].waitForExistence(timeout: 10))
        snap("E17f-tab-vocabulary")

        app.buttons["tab.home"].tap()
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 10))
    }

    func testE18DictionaryLookupAndVocabularyLinkage() {
        signIn()
        lookupAbandonInDictionaryTab()

        XCTAssertTrue(waitForAnyText("双语例句", timeout: 10), "词条应展示双语例句卡片")
        XCTAssertTrue(waitForAnyText("雪地", timeout: 10), "词条应展示例句翻译")
        XCTAssertTrue(app.staticTexts["CET-6"].exists, "abandon 在内置词表中为 CET-6")
        snap("E18a-dictionary-entry")

        favoriteCurrentDictionaryEntry()

        // 进入 AI 助教（L2），验证单词上下文。
        let ask = app.buttons["dict.askTutor"]
        scrollUntilHittable(ask)
        XCTAssertTrue(ask.waitForExistence(timeout: 10))
        ask.tap()
        XCTAssertTrue(any("tutor.context.type").waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(any("tutor.context.type"), contains: "单词上下文", timeout: 10))
        XCTAssertTrue(waitFor(any("tutor.context.title"), contains: "abandon", timeout: 10))
        XCTAssertFalse(app.buttons["tab.dictionary"].exists, "L2 助教页不应显示底栏")
        snap("E18b-dict-tutor-context")

        // 返回后查词详情保留。
        back()
        XCTAssertTrue(any("dict.word").waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(any("dict.word"), contains: "abandon", timeout: 5))

        // 生词本联动：词典收藏应出现在四栏生词本中。
        app.buttons["tab.vocabulary"].tap()
        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10), "生词本应出现词典收藏")
        XCTAssertTrue(waitForAnyText("abandon", timeout: 10))
        snap("E18c-vocabulary-linkage")

        app.buttons["tab.dictionary"].tap()
        XCTAssertTrue(any("dict.word").waitForExistence(timeout: 10), "切回查词应保留详情")
    }

    func testE19VocabularyFiltersAndDictionaryClozeJump() {
        signIn()
        lookupAbandonInDictionaryTab()
        favoriteCurrentDictionaryEntry()

        app.buttons["tab.vocabulary"].tap()
        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10))

        // 三级筛选：CET-6 + 词典。
        app.buttons["vocab.filter.level.cet6"].tap()
        app.buttons["vocab.filter.source.dictionary"].tap()
        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10))
        XCTAssertTrue(waitForAnyText("词典", timeout: 5))

        // 搜索条件与筛选在 L2 往返后保留。
        let search = app.textFields["vocab.search"]
        search.tap()
        search.typeText("aba")
        dismissKeyboard()
        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10))
        snap("E19a-filters")

        let open = app.buttons["vocab.open.0"]
        XCTAssertTrue(waitFor(open, contains: "词典详情", timeout: 5), "词典收藏的跳转文案应为词典详情")
        open.tap()
        XCTAssertTrue(any("dict.word").waitForExistence(timeout: 15), "应压栈进入 L2 查词")
        XCTAssertTrue(waitFor(any("dict.word"), contains: "abandon", timeout: 5))
        snap("E19b-dictionary-detail")
        back()

        XCTAssertTrue(any("vocab.row.0").waitForExistence(timeout: 10), "返回后筛选结果应保留")
        XCTAssertEqual(search.value as? String, "aba", "返回后搜索词应保留")

        // 词典收藏参与原句填空，反馈页跳转文案为「词典详情」。
        scrollToTop()
        let startCloze = app.buttons["vocab.startCloze"]
        XCTAssertTrue(startCloze.waitForExistence(timeout: 10))
        startCloze.tap()
        XCTAssertTrue(app.staticTexts["cloze.title"].waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(app.staticTexts["cloze.sentence"], contains: "____", timeout: 5))
        snap("E19c-dictionary-cloze")

        let input = app.textFields["cloze.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        input.tap()
        input.typeText("abandon")
        tapButton("cloze.submit")
        let feedback = app.staticTexts["cloze.feedback"]
        XCTAssertTrue(feedback.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(feedback, contains: "答对了", timeout: 10))

        let backToSource = app.buttons["cloze.backToReader"]
        scrollUntilHittable(backToSource)
        XCTAssertTrue(backToSource.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(backToSource, contains: "词典详情", timeout: 5))
        snap("E19d-cloze-dictionary-jump")

        backToSource.tap()
        XCTAssertTrue(any("dict.word").waitForExistence(timeout: 15), "应压栈进入 L3 词典详情")
        XCTAssertTrue(waitFor(any("dict.word"), contains: "abandon", timeout: 5))
        back()

        XCTAssertTrue(app.staticTexts["cloze.feedback"].waitForExistence(timeout: 10), "返回后填空反馈应保留")
        snap("E19e-cloze-state-kept")
    }

    func testE20TutorSseStreamAndFourAskEntriesPreserveState() {
        signIn()

        // 1) 阅读查词抽屉 → 单词上下文 → 快捷提问流式 → 推荐追问 → 返回面板保留。
        openDemoArticle()
        tapDecline()
        XCTAssertTrue(any("reader.panel").waitForExistence(timeout: 15))
        let wordAsk = app.buttons["reader.panel.askTutor"]
        XCTAssertTrue(wordAsk.waitForExistence(timeout: 15))
        wordAsk.tap()

        XCTAssertTrue(any("tutor.context.type").waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(any("tutor.context.type"), contains: "单词上下文", timeout: 10))
        let quickPrompt = app.buttons["tutor.quickPrompt.0"]
        XCTAssertTrue(quickPrompt.waitForExistence(timeout: 10), "应显示快捷提问")
        quickPrompt.tap()

        let followUp = app.buttons["tutor.followUp.0"]
        XCTAssertTrue(followUp.waitForExistence(timeout: 60), "流式完成后应显示推荐追问")
        snap("E20a-tutor-stream-done")

        back()
        XCTAssertTrue(app.staticTexts["reader.panel.meaning"].waitForExistence(timeout: 10), "返回后查词抽屉结果应保留")
        XCTAssertTrue(waitForAnyText("decline", timeout: 5))

        // 2) 长难句分析面板 → 句法上下文 → 返回分析结果保留。
        let analyze = app.buttons["reader.panel.analyze"]
        XCTAssertTrue(analyze.waitForExistence(timeout: 15))
        analyze.tap()
        XCTAssertTrue(any("reader.analysis").waitForExistence(timeout: 20))
        let sentenceAsk = app.buttons["reader.analysis.askTutor"]
        scrollUntilHittable(sentenceAsk)
        XCTAssertTrue(sentenceAsk.waitForExistence(timeout: 10))
        sentenceAsk.tap()

        XCTAssertTrue(any("tutor.context.type").waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(any("tutor.context.type"), contains: "句法上下文", timeout: 10))
        snap("E20b-sentence-tutor-context")
        back()
        XCTAssertTrue(any("reader.analysis").waitForExistence(timeout: 10), "返回后长难句分析应保留")
        app.buttons["关闭"].firstMatch.tap()
        back()
        XCTAssertTrue(any("home.stats.articles").waitForExistence(timeout: 10))

        // 3) 汉译英批改页 → 翻译上下文 → 返回草稿与评分保留。
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        app.buttons["translation.useDemo"].tap()
        app.buttons["translation.start"].tap()
        let draft = app.textViews["translation.draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 10))
        draft.tap()
        draft.typeText(demoTranslationDraft)
        dismissKeyboard()
        tapButton("translation.submit")
        XCTAssertTrue(app.staticTexts["translation.score"].waitForExistence(timeout: 25))

        let translationAsk = app.buttons["translation.askTutor"]
        scrollUntilHittable(translationAsk)
        XCTAssertTrue(translationAsk.waitForExistence(timeout: 10))
        translationAsk.tap()
        XCTAssertTrue(any("tutor.context.type").waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(any("tutor.context.type"), contains: "翻译上下文", timeout: 10))
        snap("E20c-translation-tutor-context")

        back()
        XCTAssertTrue(app.staticTexts["translation.score"].waitForExistence(timeout: 10), "返回后翻译批改应保留")
        XCTAssertTrue(
            waitForValue(app.textViews["translation.draft"], contains: "digital economy", timeout: 10),
            "返回后译文草稿应保留"
        )
        popToHome()
        XCTAssertTrue(app.buttons["home.writing"].waitForExistence(timeout: 10))

        // 4) 写作批改页 → 写作上下文 → 返回评分保留。
        app.buttons["home.writing"].tap()
        XCTAssertTrue(app.buttons["writing.useDemo"].waitForExistence(timeout: 10))
        app.buttons["writing.useDemo"].tap()
        tapButton("writing.submit")
        XCTAssertTrue(app.staticTexts["writing.score"].waitForExistence(timeout: 25))

        let writingAsk = app.buttons["writing.askTutor"]
        scrollUntilHittable(writingAsk)
        XCTAssertTrue(writingAsk.waitForExistence(timeout: 10))
        writingAsk.tap()
        XCTAssertTrue(any("tutor.context.type").waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(any("tutor.context.type"), contains: "写作上下文", timeout: 10))
        snap("E20d-writing-tutor-context")

        back()
        XCTAssertTrue(app.staticTexts["writing.score"].waitForExistence(timeout: 10), "返回后写作批改应保留")
        snap("E20e-four-entries-state-kept")
    }
}

// MARK: - E9 / E10

final class CetHelperPracticeUITests: E2EBase {

    private let demoDraft = "With the rapid development of the digital economy, programming skills are very important for students. Many companies prefer to hire young people who can think independently and have good team cooperation."

    func testE9TranslationHintsReviewAndLocate() {
        signIn()
        app.buttons["home.translation"].tap()
        XCTAssertTrue(app.buttons["translation.useDemo"].waitForExistence(timeout: 10))
        app.buttons["translation.useDemo"].tap()
        app.buttons["translation.start"].tap()

        XCTAssertTrue(app.buttons["translation.hint.keyword"].waitForExistence(timeout: 10))
        snap("E9a-answer-page")

        tapButton("translation.hint.keyword")
        XCTAssertTrue(app.staticTexts["演示提示 · 关键词"].waitForExistence(timeout: 20))
        snap("E9b-hint-keyword")

        tapButton("translation.hint.phrase")
        XCTAssertTrue(app.staticTexts["演示提示 · 短语"].waitForExistence(timeout: 20))
        snap("E9c-hint-phrase")

        tapButton("translation.hint.structure")
        XCTAssertTrue(app.staticTexts["演示提示 · 句子结构"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["主干"].waitForExistence(timeout: 10))
        snap("E9d-hint-structure")

        let draft = app.textViews["translation.draft"]
        scrollToTop()
        draft.tap()
        draft.typeText(demoDraft)
        dismissKeyboard()
        tapButton("translation.submit")

        let score = app.staticTexts["translation.score"]
        XCTAssertTrue(score.waitForExistence(timeout: 25), "演示译文应返回 15 分制预估分")
        XCTAssertTrue(waitFor(score, contains: "9 / 15", timeout: 10))
        XCTAssertTrue(app.staticTexts["演示批改"].exists)
        snap("E9e-review-score")

        tapButton("issue.locate.0")
        let sheet = any("snapshot.sheet")
        XCTAssertTrue(sheet.waitForExistence(timeout: 10), "定位引文应弹出快照")
        snap("E9f-snapshot")
        app.buttons["确定"].firstMatch.tap()

        // 修改输入后内联高亮被清除（快照关闭、反馈仍在，并提示反馈对应上一次提交）。
        scrollToTop()
        let draftAfterReview = app.textViews["translation.draft"]
        XCTAssertTrue(draftAfterReview.waitForExistence(timeout: 10))
        draftAfterReview.tap()
        draftAfterReview.typeText("x")
        XCTAssertFalse(sheet.exists)
        XCTAssertTrue(
            waitForAnyText("草稿已修改。下方反馈对应上一次提交。", timeout: 10),
            "修改草稿后应提示反馈对应上一次提交"
        )
        snap("E9g-after-edit")
    }

    func testE10WritingReviewExerciseRetest() {
        signIn()
        app.buttons["home.writing"].tap()
        XCTAssertTrue(app.buttons["writing.useDemo"].waitForExistence(timeout: 10))
        app.buttons["writing.useDemo"].tap()

        let badge = app.staticTexts["writing.wordBadge"]
        XCTAssertTrue(badge.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(badge, contains: "达标", timeout: 5), "演示作文词数应落在 120–180 达标区间")
        snap("E10a-demo-essay")

        tapButton("writing.submit")
        let score = app.staticTexts["writing.score"]
        XCTAssertTrue(score.waitForExistence(timeout: 25), "演示作文应返回预估分")
        XCTAssertTrue(waitFor(score, contains: "10 / 15", timeout: 10))
        snap("E10b-review-score")

        let exerciseInput = app.textFields["followup.exercise.input"]
        XCTAssertTrue(exerciseInput.waitForExistence(timeout: 10), "应挂载针对性小练习")
        scrollUntilHittable(exerciseInput)
        exerciseInput.tap()
        exerciseInput.typeText("The volunteers at the animal shelter feed the dogs every morning.")
        dismissKeyboard()
        tapButton("followup.exercise.submit")
        let verdict = app.staticTexts["followup.verdict"]
        XCTAssertTrue(verdict.waitForExistence(timeout: 20))
        XCTAssertTrue(waitFor(verdict, contains: "正确", timeout: 10))
        snap("E10c-exercise-correct")

        let request = app.buttons["followup.retest.request"]
        XCTAssertTrue(request.waitForExistence(timeout: 10))
        scrollUntilHittable(request)
        request.tap()
        XCTAssertTrue(app.staticTexts["新情境复测"].waitForExistence(timeout: 20), "换题后应出现新情境复测")
        let retestInput = app.textFields["followup.retest.input"]
        XCTAssertTrue(retestInput.waitForExistence(timeout: 10), "换题后应出现复测输入框")
        scrollUntilHittable(retestInput)
        retestInput.tap()
        retestInput.typeText("The student hosts check the microphones every Friday.")
        dismissKeyboard()
        tapButton("followup.retest.submit")
        XCTAssertTrue(waitForAnyText("已正确运用", timeout: 20), "复测应判定为已正确运用目标结构")
        snap("E10d-retest-target-used")

        scrollToTop()
        app.buttons["writing.history"].tap()
        XCTAssertTrue(
            app.staticTexts["写作批改历史"].firstMatch.waitForExistence(timeout: 10),
            "应打开写作批改历史"
        )
        snap("E10e-history")
    }
}

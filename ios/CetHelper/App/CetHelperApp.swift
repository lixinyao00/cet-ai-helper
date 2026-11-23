import SwiftUI

@main
struct CetHelperApp: App {
    @State private var app = AppViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootNavigationView(app: app)
                .task {
                    app.start()
                }
                .onChange(of: scenePhase) { _, newPhase in
                    switch newPhase {
                    case .active:
                        app.handleLifecycle(.active)
                    case .inactive:
                        app.handleLifecycle(.inactive)
                    case .background:
                        app.handleLifecycle(.background)
                    @unknown default:
                        app.handleLifecycle(.inactive)
                    }
                }
        }
    }
}

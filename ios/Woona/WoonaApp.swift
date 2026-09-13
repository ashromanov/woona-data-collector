import SwiftUI

@main
struct WoonaApp: App {
    @StateObject private var appState = AppViewModel()

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environmentObject(appState)
        }
    }
}

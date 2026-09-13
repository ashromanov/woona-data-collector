import SwiftUI

struct AppRootView: View {
    @EnvironmentObject private var appState: AppViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            TabView(selection: $appState.selectedTab) {
                OverviewView()
                    .tabItem {
                        Label(appState.text(.overview), systemImage: "dot.radiowaves.left.and.right")
                    }
                    .tag(AppViewModel.AppTab.overview)

                ChartsView()
                    .tabItem {
                        Label(appState.text(.charts), systemImage: "chart.xyaxis.line")
                    }
                    .tag(AppViewModel.AppTab.charts)

                SettingsView()
                    .tabItem {
                        Label(appState.text(.settings), systemImage: "gearshape")
                    }
                    .tag(AppViewModel.AppTab.settings)
            }

            if let exportPhase = appState.exportPhase, appState.preparedExport == nil {
                ExportProgressOverlay(phase: exportPhase)
            }
        }
        .preferredColorScheme(preferredColorScheme)
        .sheet(item: Binding(
            get: { appState.preparedExport },
            set: { _ in appState.clearPreparedExport() }
        )) { export in
            ActivityView(items: export.urls)
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .background {
                appState.enterBackground()
            }
        }
    }

    private var preferredColorScheme: ColorScheme? {
        switch appState.selectedThemeMode {
        case .system:
            nil
        case .light:
            .light
        case .dark:
            .dark
        }
    }
}

private struct ExportProgressOverlay: View {
    let phase: ExportPhase

    var body: some View {
        ZStack {
            Color.black.opacity(0.18)
                .ignoresSafeArea()

            VStack(spacing: 12) {
                ProgressView()
                Text(phase.rawValue)
                    .font(.headline)
            }
            .padding(24)
            .frame(maxWidth: 260)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(radius: 12)
        }
        .transition(.opacity)
    }
}

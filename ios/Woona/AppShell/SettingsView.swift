import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section(appState.text(.bleTransport)) {
                    LabeledContent(appState.text(.profile)) {
                        Text(appState.currentTransportProfileTitle)
                            .foregroundStyle(.secondary)
                    }
                    Text(appState.text(.transportAdvisory))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section(appState.text(.appearance)) {
                    Picker(appState.text(.theme), selection: $appState.selectedThemeMode) {
                        ForEach(AppThemeMode.allCases, id: \.self) { themeMode in
                            Text(themeMode.rawValue).tag(themeMode)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(appState.text(.language)) {
                    Picker(appState.text(.language), selection: $appState.selectedLanguage) {
                        ForEach(AppLanguage.allCases, id: \.self) { language in
                            Text(language.rawValue).tag(language)
                        }
                    }
                }

                Section(appState.selectedLanguage == .russian ? "Общий сервер" : "Shared server") {
                    TextField("https://server.example", text: $appState.serverBaseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    SecureField(appState.selectedLanguage == .russian ? "Токен устройства" : "Device token", text: $appState.serverToken)
                        .textInputAutocapitalization(.never)
                    Toggle(appState.selectedLanguage == .russian ? "Только Wi-Fi" : "Wi-Fi only", isOn: $appState.serverWifiOnly)
                    HStack {
                        Button(appState.selectedLanguage == .russian ? "Сохранить" : "Save") {
                            appState.saveServerSettings()
                        }
                        Button(appState.selectedLanguage == .russian ? "Проверить и восстановить" : "Test and restore") {
                            appState.refreshFromServer()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Button(appState.selectedLanguage == .russian ? "Повторить ошибки синхронизации" : "Retry failed sync") {
                        appState.retryFailedSync()
                    }
                    Text(appState.selectedLanguage == .russian
                         ? "Все активные Android/iOS токены видят общих собак и завершённые записи."
                         : "All active Android/iOS tokens share dogs and completed recordings.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(appState.text(.settings))
        }
    }
}

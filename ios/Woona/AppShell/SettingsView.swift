import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppViewModel

    var body: some View {
        NavigationStack {
            Form {
                if let error = appState.errorMessage {
                    Section(appState.selectedLanguage == .russian ? "Ошибка" : "Error") {
                        Text(error).foregroundStyle(.red).textSelection(.enabled)
                    }
                }
                Section(appState.selectedLanguage == .russian ? "Аккаунт" : "Account") {
                    TextField(appState.selectedLanguage == .russian ? "Уникальный идентификатор" : "Unique identifier", text: $appState.accountInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(!appState.canChangeAccount)
                    LabeledContent(appState.selectedLanguage == .russian ? "Выбран" : "Selected", value: appState.selectedAccountID ?? (appState.selectedLanguage == .russian ? "Без аккаунта" : "Unassigned"))
                    Button(appState.selectedLanguage == .russian ? "Применить и загрузить анкеты" : "Apply and load questionnaires") { appState.applyAccount() }
                        .disabled(!appState.canChangeAccount)
                    if appState.isAccountOperationRunning { ProgressView() }
                    Text(appState.selectedLanguage == .russian ? "Без пароля. Пустое поле открывает данные без аккаунта. Один идентификатор на всех ваших устройствах." : "No password. An empty identifier opens unassigned data. Use the same identifier on all your devices.")
                        .font(.footnote).foregroundStyle(.secondary)
                    if appState.selectedAccountID != nil {
                        ForEach(appState.unassignedDogProfiles) { dog in
                            Button(appState.selectedLanguage == .russian ? "Привязать собаку и все сессии: \(dog.numberOrName)" : "Link dog and all sessions: \(dog.numberOrName)") { appState.linkUnassignedDog(dog) }
                                .disabled(!appState.canChangeAccount)
                        }
                    }
                }
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
                         ? "Данные собак и сессий доступны в выбранном аккаунте."
                         : "Dogs and sessions are available within the selected account.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .disabled(!appState.canChangeAccount)
            }
            .navigationTitle(appState.text(.settings))
        }
    }
}

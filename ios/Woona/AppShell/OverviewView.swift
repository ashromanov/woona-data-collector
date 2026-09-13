import SwiftUI
import UniformTypeIdentifiers

struct OverviewView: View {
    @EnvironmentObject private var appState: AppViewModel
    @State private var isReplayImporterPresented = false
    @State private var isExportOptionsPresented = false
    @State private var pendingExportKind: ExportKind?
    @State private var isDogEditorPresented = false
    @State private var editingProfile: DogProfile?
    @State private var isSessionEditorPresented = false
    @State private var pendingDevice: BleDevice?
    @State private var pendingReplayURL: URL?

    var body: some View {
        NavigationStack {
            List {
                Section(appState.selectedLanguage == .russian ? "Собака" : "Dog profile") {
                    if appState.dogProfiles.isEmpty {
                        Text(appState.selectedLanguage == .russian ? "Создайте обязательную анкету перед записью" : "Create the required questionnaire before recording")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker(appState.selectedLanguage == .russian ? "Выбрана" : "Selected", selection: selectedProfileID) {
                            ForEach(appState.dogProfiles) { profile in
                                Text(profile.numberOrName).tag(Optional(profile.id))
                            }
                        }
                    }
                    HStack {
                        Button(appState.selectedLanguage == .russian ? "Новая" : "New") {
                            editingProfile = nil
                            isDogEditorPresented = true
                        }
                        Button(appState.selectedLanguage == .russian ? "Изменить" : "Edit") {
                            editingProfile = appState.selectedDogProfile
                            isDogEditorPresented = true
                        }
                        .disabled(appState.selectedDogProfile == nil)
                    }
                }

                Section(appState.text(.session)) {
                    LabeledContent(appState.text(.status), value: appState.statusText)
                    if appState.isReplayRunning {
                        Text(appState.text(.replay))
                            .foregroundStyle(.purple)
                    }
                    MetricGrid(
                        packetsLabel: appState.text(.packets),
                        lostLabel: appState.text(.lost),
                        rejectedLabel: appState.text(.rejected),
                        packetsReceived: appState.packetsReceived,
                        packetsLost: appState.packetsLost,
                        packetsRejected: appState.packetsRejected
                    )
                    CompactSessionDetails()
                    if let errorMessage = appState.errorMessage {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }

                Section(appState.text(.captureControls)) {
                    if appState.isCaptureReady, appState.activeRecording?.videoRequested == true, !appState.isCameraDegraded {
                        CameraPreview(recorder: appState.cameraRecorder)
                            .aspectRatio(16.0 / 9.0, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    if appState.isCaptureReady {
                        Button(appState.isRecording ? (appState.selectedLanguage == .russian ? "Остановить" : "Stop") : (appState.selectedLanguage == .russian ? "Начать" : "Start")) {
                            if appState.isRecording { appState.stopActiveCapture() }
                            else { appState.startActiveCapture() }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    HStack {
                        Button(appState.isScanning ? appState.text(.stopScan) : appState.text(.scan)) {
                            if appState.isScanning {
                                appState.stopScanning()
                            } else {
                                appState.startScanning()
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(appState.isConnectionActive || appState.selectedDogProfile == nil)

                        Button(appState.isReplayRunning ? appState.text(.stopReplay) : appState.text(.uploadBinary)) {
                            if appState.isReplayRunning {
                                appState.stopReplay()
                            } else {
                                isReplayImporterPresented = true
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled((appState.isConnectionActive && !appState.isReplayRunning) || appState.selectedDogProfile == nil)

                        Spacer()

                        Button(appState.text(.disconnect)) {
                            appState.disconnect()
                        }
                        .buttonStyle(.bordered)
                        .disabled(!appState.isConnectionActive)
                    }
                }

                Section(appState.text(.devices)) {
                    if appState.foundDevices.isEmpty {
                        Text(appState.text(.noDevices))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(appState.foundDevices) { device in
                            Button {
                                pendingDevice = device
                                pendingReplayURL = nil
                                isSessionEditorPresented = true
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(device.name)
                                    Text(device.stableIdentifier)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .disabled(appState.isConnectionActive)
                        }
                    }
                }

                if let profile = appState.selectedDogProfile {
                    Section(appState.selectedLanguage == .russian ? "Последние записи" : "Recent recordings") {
                        if appState.recentRecordings.isEmpty && appState.serverRecordings.isEmpty {
                            Text(appState.selectedLanguage == .russian ? "Записей пока нет" : "No recordings yet")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(appState.recentRecordings) { recording in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(recording.sessionLabel)
                                    Text("\(recording.source) · \(recording.status) · \(recording.serverSyncState)")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if appState.downloadableRecordingIDs.contains(recording.id) {
                                    Button(appState.selectedLanguage == .russian ? "Скачать" : "Download") {
                                        appState.downloadRecording(recording.id)
                                    }
                                    .disabled(appState.downloadingRecordingID != nil)
                                }
                            }
                        }
                        ForEach(appState.serverRecordings.filter { remote in !appState.recentRecordings.contains(where: { $0.id == remote.id }) }) { recording in
                            VStack(alignment: .leading) {
                                Text(recording.sessionLabel)
                                Text("server · \(recording.source) · \(recording.ingestStatus)")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .id(profile.id)
                }

                Section(appState.text(.diagnostics)) {
                    if appState.diagnosticMessages.isEmpty {
                        Text(appState.text(.noDiagnosticEvents))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(appState.diagnosticMessages.enumerated()), id: \.offset) { _, message in
                            Text(message)
                                .font(.footnote)
                        }
                    }
                }
            }
            .navigationTitle(appState.text(.overview))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(appState.text(.export)) {
                        isExportOptionsPresented = true
                    }
                    .disabled(appState.exportPhase != nil)
                }
            }
            .fileImporter(
                isPresented: $isReplayImporterPresented,
                allowedContentTypes: [.data, .item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        pendingReplayURL = url
                        pendingDevice = nil
                        isSessionEditorPresented = true
                    }
                case .failure(let error):
                    appState.reportError("Replay file selection failed", error: error)
                }
            }
            .sheet(isPresented: $isExportOptionsPresented, onDismiss: preparePendingExport) {
                ExportOptionsSheet(isPresented: $isExportOptionsPresented) { kind in
                    pendingExportKind = kind
                }
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $isDogEditorPresented) {
                DogQuestionnaireEditor(
                    initial: editingProfile?.questionnaire,
                    language: appState.selectedLanguage
                ) { questionnaire in
                    appState.saveDogProfile(questionnaire, replacing: editingProfile)
                }
            }
            .sheet(isPresented: $isSessionEditorPresented) {
                SessionQuestionnaireEditor(
                    language: appState.selectedLanguage,
                    source: pendingReplayURL == nil ? "live" : "replay"
                ) { questionnaire in
                    if let device = pendingDevice {
                        appState.connect(to: device, questionnaire: questionnaire)
                    } else if let url = pendingReplayURL {
                        appState.startReplay(from: url, questionnaire: questionnaire)
                    }
                    pendingDevice = nil
                    pendingReplayURL = nil
                }
            }
        }
    }

    private var selectedProfileID: Binding<UUID?> {
        Binding(
            get: { appState.selectedDogProfile?.id },
            set: { id in
                if let id, let profile = appState.dogProfiles.first(where: { $0.id == id }) {
                    appState.selectDogProfile(profile)
                }
            }
        )
    }

    private func preparePendingExport() {
        guard let kind = pendingExportKind else { return }
        pendingExportKind = nil
        appState.prepareExport(kind)
    }
}

private struct CompactSessionDetails: View {
    @EnvironmentObject private var appState: AppViewModel

    var body: some View {
        VStack(spacing: 4) {
            Text("\(appState.text(.fragments)): \(appState.fragmentsReceived) | \(appState.text(.rawBytes)): \(appState.rawBytesReceived)")
            Text("\(appState.text(.lastIssue)): \(appState.lastPacketIssue ?? appState.text(.none))")
                .foregroundStyle(appState.lastPacketIssue == nil ? Color.secondary : Color.red)
            Text("\(appState.text(.timerRegressions)): \(appState.timerRegressionRejects)")
                .foregroundStyle(appState.timerRegressionRejects == 0 ? Color.secondary : Color.red)
            Text("\(appState.text(.rejects)): \(appState.rejectionBreakdown ?? appState.text(.none))")
                .foregroundStyle(appState.rejectionBreakdown == nil ? Color.secondary : Color.purple)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
        .lineLimit(2)
    }
}

struct ExportOptionsSheet: View {
    @EnvironmentObject private var appState: AppViewModel
    @Binding var isPresented: Bool
    let onSelect: (ExportKind) -> Void

    var body: some View {
        NavigationStack {
            List {
                if let exportPhase = appState.exportPhase {
                    Section {
                        LabeledContent(appState.text(.status), value: exportPhase.rawValue)
                    }
                }

                Section {
                    ForEach(ExportKind.allCases) { kind in
                        Button {
                            onSelect(kind)
                            isPresented = false
                        } label: {
                            Text(appState.title(for: kind))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .disabled(appState.exportPhase != nil)
                    }
                }
            }
            .navigationTitle(appState.text(.export))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        isPresented = false
                    }
                }
            }
        }
    }
}

private struct MetricGrid: View {
    let packetsLabel: String
    let lostLabel: String
    let rejectedLabel: String
    let packetsReceived: UInt64
    let packetsLost: UInt64
    let packetsRejected: UInt64

    var body: some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 8) {
            GridRow {
                MetricCell(title: packetsLabel, value: packetsReceived, role: .primary)
                MetricCell(title: lostLabel, value: packetsLost, role: packetsLost > 0 ? .destructive : .secondary)
                MetricCell(title: rejectedLabel, value: packetsRejected, role: packetsRejected > 0 ? .destructive : .secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct MetricCell: View {
    enum Role {
        case primary
        case secondary
        case destructive
    }

    let title: String
    let value: UInt64
    let role: Role

    var body: some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("\(value)")
                .font(.title2.monospacedDigit())
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity)
    }

    private var color: Color {
        switch role {
        case .primary:
            .accentColor
        case .secondary:
            .secondary
        case .destructive:
            .red
        }
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

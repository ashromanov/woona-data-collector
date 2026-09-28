import XCTest
@testable import Woona

final class WoonaDataTests: XCTestCase {
    func testSheetQuestionnaireRoundTripAndDogLink() throws {
        var dog = DogQuestionnaire()
        dog.schemaVersion = 2
        dog.animalId = "финик"
        dog.numberOrName = "Финик"
        dog.species = "собака"
        dog.diagnosesDetails = "Дисплазия"
        XCTAssertTrue(dog.validate().isValid)
        let data = try WoonaStore.dogQuestionnaireData(dog)
        XCTAssertEqual(dog, try JSONDecoder().decode(DogQuestionnaire.self, from: data))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertTrue(json["specialistName"] is NSNull)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let profile = try store.saveProfile(dog)
        var session = SessionQuestionnaire()
        session.schemaVersion = 2
        session.sessionLabel = "2"
        session.plannedActivities = ["Аллюр/движение"]
        session.surfaces = ["Асфальт", "Грунт", "Трава"]
        session.sensorPosition = "Снизу на горле"
        XCTAssertTrue(session.validate().isValid)
        let recording = try store.createRecording(profile: profile, source: "live", questionnaire: session)
        XCTAssertEqual("финик", recording.questionnaire?.animalId)
        XCTAssertEqual(session.surfaces, recording.questionnaire?.surfaces)
        dog.weightKg = .nan
        XCTAssertFalse(dog.validate().isValid)
    }

    func testQuestionnairesMatchAndroidValidationRules() {
        var dog = DogQuestionnaire()
        XCTAssertFalse(dog.validate().isValid)
        dog = completeDog()
        dog.numberOrName = "Rex"
        XCTAssertTrue(dog.validate().isValid)

        dog.weightStatus = "measured"
        XCTAssertEqual("Use >0 and ≤150 kg", dog.validate().errors["weightKg"])
        dog.weightKg = 15.5
        XCTAssertTrue(dog.validate().isValid)

        var session = SessionQuestionnaire()
        XCTAssertFalse(session.validate().isValid)
        session = completeSession()
        session.videoRequested = false
        XCTAssertTrue(session.validate().isValid)
        session.pulseStatus = "measured"
        session.pulseBpm = 70
        XCTAssertNotNil(session.validate().errors["measurementAtUtc"])
    }

    func testSQLiteKeepsImmutableProfileVersionsAndRecordingSnapshot() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        var dog = completeDog()
        let first = try store.saveProfile(dog)

        dog.numberOrName = "Rex updated"
        let second = try store.saveProfile(dog, replacing: first)
        XCTAssertEqual(first.id, second.id)
        XCTAssertNotEqual(first.profileVersionID, second.profileVersionID)
        XCTAssertNotNil(try store.profile(dogID: first.id, versionID: first.profileVersionID))

        var session = completeSession()
        session.videoRequested = false
        let recording = try store.createRecording(profile: second, source: "replay", questionnaire: session)
        try store.markRecording(recording.id, status: "completed", endedAt: Date())
        XCTAssertEqual(second.profileVersionID, try store.recording(id: recording.id)?.profileVersionID)
        try store.updateRecordingSync(recording.id, state: "permanent_error")
        try store.resetFailedSync()
        XCTAssertEqual("pending", try store.recording(id: recording.id)?.serverSyncState)
    }

    func testServerQuestionnaireJSONIncludesRequiredNulls() throws {
        let dog = try XCTUnwrap(
            JSONSerialization.jsonObject(with: WoonaStore.dogQuestionnaireData(completeDog())) as? [String: Any]
        )
        XCTAssertTrue(dog["breedName"] is NSNull)
        XCTAssertTrue(dog["notes"] is NSNull)

        let session = try XCTUnwrap(
            JSONSerialization.jsonObject(with: WoonaStore.sessionQuestionnaireData(completeSession())) as? [String: Any]
        )
        XCTAssertTrue(session["pulseBpm"] is NSNull)
        XCTAssertTrue(session["measurementAtUtc"] is NSNull)
    }

    func testRemoteProfileCanCreateANewLocalDog() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let dogID = UUID()
        let versionID = UUID()
        let hash = String(repeating: "a", count: 64)
        let profile = try store.upsertRemoteProfile(
            dogID: dogID,
            profileVersionID: versionID,
            revision: 4,
            questionnaire: completeDog(),
            contentSha256: hash,
            updatedAtUTC: "2026-08-17T12:00:00Z"
        )
        XCTAssertEqual(dogID, profile.id)
        XCTAssertEqual(versionID, profile.profileVersionID)
        XCTAssertEqual(hash, profile.contentSha256)
    }

    func testRemoteRestoreDoesNotOverwriteAnUnsyncedLocalProfile() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let local = try store.saveProfile(completeDog())
        var remoteDog = completeDog()
        remoteDog.numberOrName = "Remote Rex"
        let restored = try store.upsertRemoteProfile(
            dogID: local.id,
            profileVersionID: UUID(),
            revision: 1,
            questionnaire: remoteDog,
            contentSha256: String(repeating: "c", count: 64),
            updatedAtUTC: "2026-08-17T13:00:00Z"
        )
        XCTAssertEqual(local.profileVersionID, restored.profileVersionID)
        XCTAssertEqual("Rex", restored.numberOrName)
        XCTAssertEqual(1, restored.revision)
    }

    func testRemoteRecordingRestoresMetadataBeforeVerifiedDownload() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let profile = try store.saveProfile(completeDog())
        let recordingID = UUID()
        let artifactID = UUID()
        let remote = ServerRecordingDetail(
            id: recordingID,
            dogId: profile.id,
            profileVersion: .init(
                id: profile.profileVersionID,
                schemaVersion: 1,
                validationState: "complete",
                questionnaire: profile.questionnaire,
                contentSha256: profile.contentSha256,
                clientCreatedAtUtc: profile.createdAtUTC,
                supersededAtUtc: nil
            ),
            source: "live",
            captureStatus: "completed",
            ingestStatus: "complete",
            startedAtUtc: "2026-08-17T12:00:00Z",
            endedAtUtc: "2026-08-17T12:01:00Z",
            timezone: "Europe/Moscow",
            sessionLabel: "Remote",
            questionnaireSchemaVersion: 1,
            questionnaireValidationState: "complete",
            sessionQuestionnaire: .object(["legacy": .bool(true)]),
            videoRequested: false,
            sync: .object(["schemaVersion": .integer(2)]),
            artifacts: [
                ServerArtifact(
                    id: artifactID,
                    type: "sync",
                    fileName: "sync.json",
                    mimeType: "application/json",
                    sizeBytes: 12,
                    sha256: String(repeating: "b", count: 64),
                    storageStatus: "available"
                ),
            ]
        )
        XCTAssertTrue(try store.restoreRemoteRecording(remote))
        XCTAssertFalse(try store.restoreRemoteRecording(remote))
        XCTAssertEqual("synced", try store.recording(id: recordingID)?.serverSyncState)
        XCTAssertNil(try store.recording(id: recordingID)?.questionnaire)
        XCTAssertEqual([artifactID], try store.missingArtifacts(recordingID: recordingID).map(\.id))
    }

    @MainActor
    func testFailedRecordingExportsAllLocalArtifactsAfterRestart() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var store: WoonaStore? = try WoonaStore(rootDirectory: directory)
        let profile = try store!.saveProfile(completeDog())
        let session = completeSession()
        let recording = try store!.createRecording(profile: profile, source: "live", questionnaire: session)
        let samples = [
            ("packet", "packets.bin"), ("packet_timeline", "packet_timeline.bin"),
            ("raw", "raw_fragments.binlog"), ("diagnostic", "diagnostics.log"),
            ("csv", "channel.csv"), ("video", "video.mp4"), ("imported_source", "imported.bin"),
        ]
        let files = try samples.map { type, name -> (type: String, source: URL, name: String, mime: String) in
            let source = directory.appendingPathComponent(name)
            try Data("fixture-\(name)".utf8).write(to: source)
            return (type, source, name, "application/octet-stream")
        }
        try store!.finalize(recording: recording, status: "interrupted", files: files, syncJSON: Data("{\"schemaVersion\":2}".utf8))
        try store!.updateRecordingSync(recording.id, state: "permanent_error", error: NSError(domain: "upload", code: 401,
            userInfo: [NSLocalizedDescriptionKey: "HTTP 401: invalid_bearer_token"]))
        var editedDog = completeDog()
        editedDog.numberOrName = "Updated dog"
        _ = try store!.saveProfile(editedDog, replacing: profile)
        store = nil
        let reopened = try WoonaStore(rootDirectory: directory)
        let metadata = try reopened.recordingArchive(recordingID: recording.id)
        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: metadata.manifest) as? [String: Any])
        XCTAssertEqual("Rex", (manifest["profile"] as? [String: Any])?["numberOrName"] as? String)
        XCTAssertEqual("permanent_error", (manifest["recording"] as? [String: Any])?["serverSyncState"] as? String)
        XCTAssertEqual("Morning", ((manifest["recording"] as? [String: Any])?["questionnaire"] as? [String: Any])?["sessionLabel"] as? String)
        XCTAssertEqual(2, (manifest["synchronization"] as? [String: Any])?["schemaVersion"] as? Int)
        XCTAssertEqual(8, metadata.files.count)
        let viewModel = AppViewModel(store: reopened)
        XCTAssertTrue(viewModel.exportableRecordingIDs.contains(recording.id))
        XCTAssertEqual("HTTP 401: invalid_bearer_token", viewModel.recordingSyncErrors[recording.id])
        viewModel.shareRecording(recording.id)
        for _ in 0..<100 where viewModel.preparedExport == nil && viewModel.exportPhase != nil {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        let prepared = try XCTUnwrap(viewModel.preparedExport, viewModel.errorMessage ?? "ZIP did not open the share sheet")
        let archive = try XCTUnwrap(prepared.urls.first)
        let bytes = try Data(contentsOf: archive)
        XCTAssertEqual([0x50, 0x4b], Array(bytes.prefix(2)))
        for name in samples.map({ $0.1 }) + ["sync.json", "manifest.json"] {
            XCTAssertNotNil(bytes.range(of: Data(name.utf8)), "ZIP central directory omitted \(name)")
        }
        // Retain a synthetic acceptance ZIP for independent host-side unzip/SHA verification in CI.
        let acceptance = FileManager.default.temporaryDirectory.appendingPathComponent("woona-export-acceptance.zip")
        try? FileManager.default.removeItem(at: acceptance)
        try FileManager.default.copyItem(at: archive, to: acceptance)
        viewModel.clearPreparedExport()
        XCTAssertFalse(FileManager.default.fileExists(atPath: archive.path))
        XCTAssertEqual("permanent_error", try reopened.recording(id: recording.id)?.serverSyncState)
        XCTAssertEqual(8, try reopened.artifacts(recordingID: recording.id).count)
        for file in metadata.files {
            XCTAssertEqual(file.sha256, WoonaStore.sha256(try Data(contentsOf: file.url)))
        }
    }

    @MainActor
    func testSavedPacketAndCsvExportsWorkWithoutCurrentCapture() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let profile = try store.saveProfile(completeDog())
        let recording = try store.createRecording(profile: profile, source: "replay", questionnaire: completeSession())
        let packet = Data(makeTestPacket(counter: 1, timerMillis: 50, measurementCount: 1,
            blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]))
        let source = directory.appendingPathComponent("source.bin")
        try packet.write(to: source)
        try store.finalize(recording: recording, status: "completed", files: [("packet", source, "packets.bin", "application/octet-stream")],
                           syncJSON: Data("{}".utf8))
        let viewModel = AppViewModel(store: store)
        viewModel.prepareExport(.channelCsv)
        XCTAssertNotNil(viewModel.exportPhase)
        viewModel.prepareExport(.packetDump) // A second tap must not replace an export already in progress.
        for _ in 0..<100 where viewModel.preparedExport == nil && viewModel.exportPhase != nil {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        let csv = try XCTUnwrap(viewModel.preparedExport?.urls.first, viewModel.errorMessage ?? "CSV not prepared")
        let text = try String(contentsOf: csv, encoding: .utf8)
        XCTAssertTrue(text.contains("axl_sensor_2_ch_1"))
        XCTAssertTrue(text.contains(",50,50,10"))
        viewModel.clearPreparedExport()
        XCTAssertFalse(FileManager.default.fileExists(atPath: csv.path))
        viewModel.prepareExport(.packetDump)
        for _ in 0..<100 where viewModel.preparedExport == nil && viewModel.exportPhase != nil {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        let exported = try XCTUnwrap(viewModel.preparedExport?.urls.first)
        XCTAssertEqual(packet, try Data(contentsOf: exported))
        viewModel.clearPreparedExport()
        XCTAssertEqual(2, try store.artifacts(recordingID: recording.id).count)
    }

    func testRecordingArchiveRejectsEmptyAndChangedFilesAndReportsMissingFiles() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try WoonaStore(rootDirectory: directory)
        let profile = try store.saveProfile(completeDog())
        let recording = try store.createRecording(profile: profile, source: "replay", questionnaire: completeSession())
        XCTAssertThrowsError(try store.recordingArchive(recordingID: recording.id))
        try store.markRecording(recording.id, status: "failed")
        XCTAssertThrowsError(try store.recordingArchive(recordingID: recording.id))
        let video = directory.appendingPathComponent("source.mp4")
        try Data("original-video".utf8).write(to: video)
        try store.finalize(recording: recording, status: "failed", files: [("video", video, "video.mp4", "video/mp4")],
                           syncJSON: Data("{\"cameraError\":\"interrupted\"}".utf8))
        let original = try store.recordingArchive(recordingID: recording.id)
        let localVideo = try XCTUnwrap(original.files.first { $0.url.lastPathComponent == "video.mp4" }).url
        try Data("corrupt-video".utf8).write(to: localVideo)
        let output = directory.appendingPathComponent("export", isDirectory: true)
        XCTAssertThrowsError(try original.create(in: output))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: output.path).isEmpty)
        XCTAssertEqual(Data("corrupt-video".utf8), try Data(contentsOf: localVideo))
        try FileManager.default.removeItem(at: localVideo)
        let partial = try store.recordingArchive(recordingID: recording.id)
        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: partial.manifest) as? [String: Any])
        XCTAssertEqual(["video.mp4"], manifest["missingFiles"] as? [String])
        XCTAssertEqual(["sync.json"], partial.files.map { $0.url.lastPathComponent })
        for _ in 0..<12 { _ = try store.createRecording(profile: profile, source: "replay", questionnaire: completeSession()) }
        XCTAssertEqual(10, try store.recentRecordings(dogID: profile.id).count)
        XCTAssertEqual(13, try store.recentRecordings(dogID: profile.id, limit: nil).count)
    }

    private func completeDog() -> DogQuestionnaire {
        var dog = DogQuestionnaire()
        dog.numberOrName = "Rex"
        dog.shelterOrPlace = "Shelter 1"
        dog.breedStatus = "unknown"
        dog.size = "unknown"
        dog.ageStatus = "unknown"
        dog.ageSource = "unknown"
        dog.sex = "unknown"
        dog.sterilizationStatus = "unknown"
        dog.weightStatus = "unknown"
        dog.bodyConditionStatus = "unable"
        dog.muscleMass = "unable"
        dog.neckCircumferenceStatus = "not_measured"
        dog.coatLength = "unknown"
        dog.undercoat = "unknown"
        dog.shavedAreasStatus = "unknown"
        dog.observedSigns = ["none"]
        dog.diagnosesStatus = "unknown"
        dog.housing = "unknown"
        dog.walksStatus = "unknown"
        dog.cohabitants = "unknown"
        dog.shelterPermission = "unknown"
        dog.notesStatus = "none"
        return dog
    }

    private func completeSession() -> SessionQuestionnaire {
        var session = SessionQuestionnaire()
        session.sessionLabel = "Morning"
        session.operatorName = "Operator"
        session.activityGroup = "stationary"
        session.activityType = "rest"
        session.location = "indoors"
        session.surface = "concrete"
        session.airTemperatureStatus = "not_measured"
        session.sensorPosition = "dorsal_neck"
        session.collarTightness = "snug"
        session.preMeasurementState = "rest"
        session.pulseStatus = "not_measured"
        session.respirationStatus = "not_measured"
        session.bodyTemperatureStatus = "not_measured"
        return session
    }
}

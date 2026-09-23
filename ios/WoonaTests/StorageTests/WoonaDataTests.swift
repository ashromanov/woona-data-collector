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

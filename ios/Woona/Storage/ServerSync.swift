import Foundation
import Security

struct ServerConfiguration: Equatable {
    var baseURL: String
    var wifiOnly: Bool
}

struct ServerRecordingSummary: Codable, Identifiable, Equatable {
    let id: UUID
    let source: String
    let captureStatus: String
    let ingestStatus: String
    let startedAtUtc: String
    let endedAtUtc: String?
    let sessionLabel: String
    let videoRequested: Bool
}

struct ServerArtifact: Decodable, Identifiable {
    let id: UUID
    let type: String
    let fileName: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String
    let storageStatus: String
}

struct ServerProfileVersion: Decodable, Identifiable {
    let id: UUID
    let schemaVersion: Int
    let validationState: String
    let questionnaire: DogQuestionnaire
    let contentSha256: String
    let clientCreatedAtUtc: String
    let supersededAtUtc: String?
}

struct ServerRecordingDetail: Decodable {
    let id: UUID
    let dogId: UUID
    let profileVersion: ServerProfileVersion
    let source: String
    let captureStatus: String
    let ingestStatus: String
    let startedAtUtc: String
    let endedAtUtc: String?
    let timezone: String
    let sessionLabel: String
    let questionnaireSchemaVersion: Int
    let questionnaireValidationState: String
    let sessionQuestionnaire: JSONValue
    let videoRequested: Bool
    let sync: JSONValue
    let artifacts: [ServerArtifact]
}

enum JSONValue: Codable {
    case null
    case bool(Bool)
    case integer(Int64)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let decoded = try? value.decode(Bool.self) { self = .bool(decoded) }
        else if let decoded = try? value.decode(Int64.self) { self = .integer(decoded) }
        else if let decoded = try? value.decode(Double.self) { self = .number(decoded) }
        else if let decoded = try? value.decode(String.self) { self = .string(decoded) }
        else if let decoded = try? value.decode([JSONValue].self) { self = .array(decoded) }
        else { self = .object(try value.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .null: try value.encodeNil()
        case .bool(let decoded): try value.encode(decoded)
        case .integer(let decoded): try value.encode(decoded)
        case .number(let decoded): try value.encode(decoded)
        case .string(let decoded): try value.encode(decoded)
        case .array(let decoded): try value.encode(decoded)
        case .object(let decoded): try value.encode(decoded)
        }
    }
}

struct ServerProfileReceipt: Decodable {
    let dogRevision: Int
}

enum ServerSyncError: LocalizedError {
    case invalidURL
    case tokenMissing
    case responseInvalid
    case requestFailed(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: "Server URL is invalid"
        case .tokenMissing: "Server token is missing"
        case .responseInvalid: "Server response is invalid"
        case .requestFailed(let status, let message): "Server returned \(status): \(message)"
        }
    }
}

final class KeychainTokenStore {
    private let service = "com.woona.apps.server"
    private let account = "bearer-token"

    func load() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    func save(_ token: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if token.isEmpty {
            SecItemDelete(key as CFDictionary)
            return
        }
        let data = Data(token.utf8)
        let updateStatus = SecItemUpdate(
            key as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw ServerSyncError.requestFailed(Int(updateStatus), "Unable to update token")
        }
        var value = key
        value[kSecValueData as String] = data
        value[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(value as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw ServerSyncError.requestFailed(Int(status), "Unable to store token")
        }
    }
}

@MainActor
final class WoonaServerClient {
    private let baseURL: URL
    private let token: String
    private let session: URLSession

    init(configuration: ServerConfiguration, token: String) throws {
        guard let url = URL(string: configuration.baseURL), url.host != nil,
              ["http", "https"].contains(url.scheme?.lowercased()) else {
            throw ServerSyncError.invalidURL
        }
        guard !token.isEmpty else { throw ServerSyncError.tokenMissing }
        self.baseURL = url
        self.token = token
        let sessionConfiguration = URLSessionConfiguration.default
        sessionConfiguration.allowsCellularAccess = !configuration.wifiOnly
        sessionConfiguration.allowsExpensiveNetworkAccess = !configuration.wifiOnly
        sessionConfiguration.waitsForConnectivity = true
        self.session = URLSession(configuration: sessionConfiguration)
    }

    func deviceID() async throws -> UUID {
        struct Me: Decodable { let deviceId: UUID }
        return try await json(method: "GET", path: "/v1/me", body: Optional<Data>.none, as: Me.self).deviceId
    }

    func upload(profile: DogProfile) async throws -> Int {
        let questionnaire = try JSONSerialization.jsonObject(with: WoonaStore.dogQuestionnaireData(profile.questionnaire))
        let payload: [String: Any] = [
            "dog": [
                "id": profile.id.uuidString,
                "numberOrName": profile.numberOrName,
                "expectedRevision": profile.revision,
            ],
            "profileVersion": [
                "id": profile.profileVersionID.uuidString,
                "schemaVersion": 1,
                "validationState": "complete",
                "questionnaire": questionnaire,
                "contentSha256": profile.contentSha256,
                "clientCreatedAtUtc": profile.updatedAtUTC,
            ],
        ]
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .withoutEscapingSlashes])
        let receipt: ServerProfileReceipt = try await json(
            method: "PUT",
            path: "/v1/dogs/\(profile.id.uuidString)/profile-versions/\(profile.profileVersionID.uuidString)",
            body: body,
            as: ServerProfileReceipt.self
        )
        return receipt.dogRevision
    }

    func restoreProfiles(into store: WoonaStore) async throws -> [DogProfile] {
        struct Dog: Decodable {
            let id: UUID
            let numberOrName: String
            let revision: Int
            let updatedAtUtc: String?
            let profileVersion: ServerProfileVersion
            let profileVersions: [ServerProfileVersion]
        }
        struct Summary: Decodable { let id: UUID }
        struct Page: Decodable { let items: [Summary]; let nextCursor: UUID? }
        var cursor: UUID?
        repeat {
            let suffix = cursor.map { "?limit=200&cursor=\($0.uuidString)" } ?? "?limit=200"
            let page: Page = try await json(method: "GET", path: "/v1/dogs\(suffix)", body: Optional<Data>.none, as: Page.self)
            for summary in page.items {
                let dog: Dog = try await json(
                    method: "GET",
                    path: "/v1/dogs/\(summary.id.uuidString)",
                    body: Optional<Data>.none,
                    as: Dog.self
                )
                _ = try store.upsertRemoteProfile(
                    dogID: dog.id,
                    profileVersionID: dog.profileVersion.id,
                    revision: dog.revision,
                    questionnaire: dog.profileVersion.questionnaire,
                    contentSha256: dog.profileVersion.contentSha256,
                    updatedAtUTC: dog.updatedAtUtc ?? dog.profileVersion.clientCreatedAtUtc
                )
                for version in dog.profileVersions where version.id != dog.profileVersion.id {
                    try store.restoreRemoteProfileVersion(dogID: dog.id, version: version)
                }
            }
            cursor = page.nextCursor
        } while cursor != nil
        return try store.profiles()
    }

    func recordings(dogID: UUID, limit: Int = 10) async throws -> [ServerRecordingSummary] {
        struct Page: Decodable { let items: [ServerRecordingSummary]; let nextCursor: UUID? }
        let page: Page = try await json(
            method: "GET",
            path: "/v1/dogs/\(dogID.uuidString)/recordings?limit=\(limit)",
            body: Optional<Data>.none,
            as: Page.self
        )
        return page.items
    }

    @discardableResult
    func restoreRecordings(dogIDs: [UUID], into store: WoonaStore) async throws -> Int {
        struct Page: Decodable { let items: [ServerRecordingSummary]; let nextCursor: UUID? }
        var restored = 0
        for dogID in dogIDs {
            var cursor: UUID?
            repeat {
                let suffix = cursor.map { "?limit=200&cursor=\($0.uuidString)" } ?? "?limit=200"
                let page: Page = try await json(
                    method: "GET",
                    path: "/v1/dogs/\(dogID.uuidString)/recordings\(suffix)",
                    body: Optional<Data>.none,
                    as: Page.self
                )
                for summary in page.items where summary.ingestStatus == "complete" {
                    let detail: ServerRecordingDetail = try await json(
                        method: "GET",
                        path: "/v1/recordings/\(summary.id.uuidString)",
                        body: Optional<Data>.none,
                        as: ServerRecordingDetail.self
                    )
                    if try store.restoreRemoteRecording(detail) { restored += 1 }
                }
                cursor = page.nextCursor
            } while cursor != nil
        }
        return restored
    }

    func upload(recording: WoonaRecording, profile: DogProfile, store: WoonaStore) async throws {
        do {
            try store.updateRecordingSync(recording.id, state: "uploading")
            let artifacts = try store.artifacts(recordingID: recording.id)
            guard !artifacts.isEmpty,
                  let syncData = try store.syncJSON(recordingID: recording.id),
                  var sync = try JSONSerialization.jsonObject(with: syncData) as? [String: Any] else {
                throw ServerSyncError.responseInvalid
            }
            let captureDeviceID = try await deviceID()
            let profileJSON = try JSONSerialization.jsonObject(with: WoonaStore.dogQuestionnaireData(profile.questionnaire))
            guard let questionnaire = recording.questionnaire else { throw ServerSyncError.responseInvalid }
            let sessionJSON = try JSONSerialization.jsonObject(with: WoonaStore.sessionQuestionnaireData(questionnaire))
            let captureErrorCode = sync.removeValue(forKey: "captureErrorCode")
            let captureErrorMessage = sync.removeValue(forKey: "captureErrorMessage")
            let manifest: [String: Any] = [
                "schemaVersion": 1,
                "captureDeviceId": captureDeviceID.uuidString,
                "dog": [
                    "id": profile.id.uuidString,
                    "numberOrName": profile.numberOrName,
                    "expectedRevision": profile.revision,
                ],
                "dogProfileVersion": [
                    "id": profile.profileVersionID.uuidString,
                    "schemaVersion": 1,
                    "validationState": "complete",
                    "questionnaire": profileJSON,
                    "contentSha256": profile.contentSha256,
                    "clientCreatedAtUtc": profile.updatedAtUTC,
                ],
                "recording": [
                    "source": recording.source,
                    "captureStatus": recording.status,
                    "startedAtUtc": recording.startedAtUTC,
                    "endedAtUtc": recording.endedAtUTC.map { $0 as Any } ?? NSNull(),
                    "timezone": recording.timezone,
                    "sessionLabel": recording.sessionLabel,
                    "videoRequested": recording.videoRequested,
                    "sensorHardwareId": NSNull(),
                    "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "ios-dev",
                    "protocolVersion": "1",
                    "questionnaireSchemaVersion": 1,
                    "questionnaireValidationState": "complete",
                    "sessionQuestionnaire": sessionJSON,
                    "captureErrorCode": captureErrorCode ?? NSNull(),
                    "captureErrorMessage": captureErrorMessage ?? NSNull(),
                ],
                "sync": sync,
                "artifacts": artifacts.map { artifact in
                    [
                        "id": artifact.id.uuidString,
                        "type": artifact.type,
                        "fileName": artifact.fileName,
                        "mimeType": artifact.mimeType,
                        "sizeBytes": artifact.sizeBytes,
                        "sha256": artifact.sha256,
                        "clientCreatedAtUtc": recording.endedAtUTC ?? recording.startedAtUTC,
                    ] as [String: Any]
                },
            ]
            let body = try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys, .withoutEscapingSlashes])
            _ = try await rawRequest(
                method: "PUT",
                path: "/v1/recordings/\(recording.id.uuidString)",
                body: body,
                contentType: "application/json"
            )

            for artifact in artifacts {
                let file = store.rootDirectory.appendingPathComponent(artifact.relativePath)
                let offset = try await uploadOffset(artifact.id)
                guard offset <= artifact.sizeBytes else { throw ServerSyncError.responseInvalid }
                try store.updateArtifactProgress(artifact.id, uploadedBytes: offset, state: "uploading")
                let handle = try FileHandle(forReadingFrom: file)
                defer { try? handle.close() }
                try handle.seek(toOffset: UInt64(offset))
                var current = offset
                while current < artifact.sizeBytes {
                    let count = Int(min(8 * 1_024 * 1_024, artifact.sizeBytes - current))
                    guard let chunk = try handle.read(upToCount: count), !chunk.isEmpty else {
                        throw ServerSyncError.responseInvalid
                    }
                    let response = try await rawRequest(
                        method: "PATCH",
                        path: "/v1/artifacts/\(artifact.id.uuidString)/content",
                        body: chunk,
                        contentType: "application/offset+octet-stream",
                        headers: ["Upload-Offset": String(current)]
                    )
                    current = Int64(response.value(forHTTPHeaderField: "Upload-Offset") ?? "")
                        ?? current + Int64(chunk.count)
                    try store.updateArtifactProgress(artifact.id, uploadedBytes: current, state: "uploading")
                }
                _ = try await rawRequest(
                    method: "POST",
                    path: "/v1/artifacts/\(artifact.id.uuidString)/complete",
                    body: Data("{}".utf8),
                    contentType: "application/json"
                )
                try store.updateArtifactProgress(artifact.id, uploadedBytes: artifact.sizeBytes, state: "available")
            }
            _ = try await rawRequest(
                method: "POST",
                path: "/v1/recordings/\(recording.id.uuidString)/complete",
                body: Data("{}".utf8),
                contentType: "application/json"
            )
            try store.updateRecordingSync(recording.id, state: "synced")
        } catch {
            let state: String
            if case ServerSyncError.requestFailed(let status, _) = error,
               [400, 401, 403, 404, 413, 422].contains(status) {
                state = "permanent_error"
            } else {
                state = "retryable_error"
            }
            try? store.updateRecordingSync(recording.id, state: state, error: error)
            throw error
        }
    }

    func download(artifactID: UUID, to target: URL, expectedSize: Int64, sha256: String) async throws {
        let part = target.appendingPathExtension("part")
        let attributes = try? FileManager.default.attributesOfItem(atPath: part.path)
        var offset = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        if attributes != nil, offset == expectedSize {
            let existing = try Data(contentsOf: part, options: .mappedIfSafe)
            if WoonaStore.sha256(existing) == sha256 {
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: part, to: target)
                return
            }
        }
        if offset >= expectedSize {
            try? FileManager.default.removeItem(at: part)
            offset = 0
        }
        var request = try makeRequest(method: "GET", path: "/v1/artifacts/\(artifactID.uuidString)/content")
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        let (temporary, response) = try await session.download(for: request)
        try validate(response: response, data: Data())
        let didResume = offset > 0 && (response as? HTTPURLResponse)?.statusCode == 206
        if !didResume {
            try? FileManager.default.removeItem(at: part)
            try FileManager.default.moveItem(at: temporary, to: part)
        } else {
            let input = try FileHandle(forReadingFrom: temporary)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: part)
            defer { try? output.close() }
            try output.seekToEnd()
            try output.write(contentsOf: input.readDataToEndOfFile())
        }
        let data = try Data(contentsOf: part, options: .mappedIfSafe)
        guard data.count == expectedSize, WoonaStore.sha256(data) == sha256 else {
            throw ServerSyncError.responseInvalid
        }
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: part, to: target)
    }

    private func json<T: Decodable>(method: String, path: String, body: Data?, as type: T.Type) async throws -> T {
        var request = try makeRequest(method: method, path: path)
        request.httpBody = body
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        try validate(response: response, data: data)
        return try JSONDecoder().decode(type, from: data)
    }

    private func uploadOffset(_ artifactID: UUID) async throws -> Int64 {
        let response = try await rawRequest(method: "HEAD", path: "/v1/artifacts/\(artifactID.uuidString)/content")
        guard let value = response.value(forHTTPHeaderField: "Upload-Offset"), let offset = Int64(value) else {
            throw ServerSyncError.responseInvalid
        }
        return offset
    }

    @discardableResult
    private func rawRequest(
        method: String,
        path: String,
        body: Data? = nil,
        contentType: String? = nil,
        headers: [String: String] = [:]
    ) async throws -> HTTPURLResponse {
        var request = try makeRequest(method: method, path: path)
        request.httpBody = body
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, response) = try await session.data(for: request)
        try validate(response: response, data: data)
        guard let response = response as? HTTPURLResponse else { throw ServerSyncError.responseInvalid }
        return response
    }

    private func makeRequest(method: String, path: String) throws -> URLRequest {
        guard let url = URL(string: path, relativeTo: baseURL) else { throw ServerSyncError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(UUID().uuidString, forHTTPHeaderField: "X-Request-ID")
        return request
    }

    private func validate(response: URLResponse, data: Data) throws {
        guard let response = response as? HTTPURLResponse else { throw ServerSyncError.responseInvalid }
        guard (200...299).contains(response.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["message"] as? String
            throw ServerSyncError.requestFailed(response.statusCode, message ?? HTTPURLResponse.localizedString(forStatusCode: response.statusCode))
        }
    }
}

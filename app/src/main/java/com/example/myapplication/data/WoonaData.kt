package com.example.myapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class DogQuestionnaire(
    val numberOrName: String = "",
    val shelterOrPlace: String? = null,
    val breed: String? = null,
    val resembles: String? = null,
    val size: String? = null,
    val ageYears: Int? = null,
    val ageMonths: Int? = null,
    val ageSource: String? = null,
    val sex: String? = null,
    val sterilized: Boolean? = null,
    val weightKg: Double? = null,
    val weightSource: String? = null,
    val bodyConditionScore: Int? = null,
    val bodyConditionUnableToAssess: Boolean? = null,
    val muscleMass: String? = null,
    val neckCircumferenceCm: Double? = null,
    val coatLength: String? = null,
    val undercoat: String? = null,
    val shavedAreas: String? = null,
    val observedSigns: List<String> = emptyList(),
    val diagnosesPresent: Boolean? = null,
    val diagnosesDetails: String? = null,
    val housing: String? = null,
    val walks: String? = null,
    val cohabitants: String? = null,
    val shelterPermission: Boolean? = null,
    val notes: String? = null,
)

data class SessionQuestionnaire(
    val sessionNumber: String = UUID.randomUUID().toString(),
    val date: String = LocalDate.now().toString(),
    val startTime: String = LocalTime.now().withSecond(0).withNano(0).toString(),
    val operator: String? = null,
    val recordingContents: List<String> = emptyList(),
    val surface: String? = null,
    val location: String? = null,
    val airTemperatureC: Int? = null,
    val sensorPosition: String? = null,
    val collarTightness: String? = null,
    val preMeasurementState: String? = null,
    val pulseBpm: Int? = null,
    val respirationPerMinute: Int? = null,
    val bodyTemperatureC: Double? = null,
    val measurementTime: String? = null,
)

enum class RecordingSource(val value: String) {
    LIVE("live"),
    REPLAY("replay"),
}

enum class RecordingStatus(val value: String) {
    PREPARING("preparing"),
    RECORDING("recording"),
    COMPLETED("completed"),
    FAILED("failed"),
    INTERRUPTED("interrupted"),
}

enum class ArtifactType(val value: String) {
    PACKET("packet"),
    RAW("raw"),
    DIAGNOSTIC("diagnostic"),
    CSV("csv"),
    VIDEO("video"),
    METADATA("metadata"),
}

data class SyncClockAnchor(
    val event: String,
    val absoluteUtc: String,
    val wallClockEpochMillis: Long,
    val monotonicTimeNs: Long,
    val monotonicClock: String,
    val samplingUncertaintyNs: Long,
)

data class VideoSyncMetadata(
    val fileName: String = "video.mp4",
    val requestedAtUtc: String,
    val requestedMonotonicNs: Long,
    val firstFrameAtUtc: String? = null,
    val firstFrameEpochMillis: Double? = null,
    val firstFrameMonotonicNs: Long? = null,
    val firstFrameCameraTimestampNs: Long? = null,
    val offsetFromSensorNs: Long? = null,
    val cameraTimestampSource: String? = null,
    val synchronizationQuality: String? = null,
    val cameraId: String? = null,
    val lensFacing: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
    val rotationDegrees: Int? = null,
    val codec: String = "h264",
    val container: String = "mp4",
    val stoppedAtUtc: String? = null,
    val stoppedMonotonicNs: Long? = null,
    val durationNs: Long? = null,
)

data class CaptureSyncMetadata(
    val schemaVersion: Int = 1,
    val recordingId: String,
    val profileId: String,
    val source: String,
    val timezone: String,
    val selectedSessionStartUtc: String,
    val sensor: SyncClockAnchor? = null,
    val video: VideoSyncMetadata? = null,
)

fun monotonicOffsetNs(anchor: SyncClockAnchor, monotonicTimeNs: Long): Long =
    monotonicTimeNs - anchor.monotonicTimeNs

fun absoluteInstantForMonotonic(anchor: SyncClockAnchor, monotonicTimeNs: Long): Instant =
    Instant.ofEpochMilli(anchor.wallClockEpochMillis)
        .plusNanos(monotonicOffsetNs(anchor, monotonicTimeNs))

data class DogProfile(
    val id: String,
    val numberOrName: String,
    val questionnaire: DogQuestionnaire,
    val createdAtUtc: String,
    val updatedAtUtc: String,
)

data class Artifact(
    val id: String,
    val recordingId: String,
    val type: ArtifactType,
    val relativePath: String,
    val sizeBytes: Long,
    val createdAtUtc: String,
)

data class Recording(
    val id: String,
    val profileId: String,
    val source: RecordingSource,
    val status: RecordingStatus,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val timezone: String,
    val questionnaire: SessionQuestionnaire?,
    val relativeDirectory: String,
    val artifacts: List<Artifact> = emptyList(),
)

data class RecordingSummary(
    val recording: Recording,
    val profileName: String,
    val profileQuestionnaire: DogQuestionnaire,
)

fun DogQuestionnaire.toJson(): String = JSONObject().apply {
    put("numberOrName", numberOrName)
    put("species", "dog")
    putNullable("shelterOrPlace", shelterOrPlace)
    putNullable("breed", breed)
    putNullable("resembles", resembles)
    putNullable("size", size)
    putNullable("ageYears", ageYears)
    putNullable("ageMonths", ageMonths)
    putNullable("ageSource", ageSource)
    putNullable("sex", sex)
    putNullable("sterilized", sterilized)
    putNullable("weightKg", weightKg)
    putNullable("weightSource", weightSource)
    putNullable("bodyConditionScore", bodyConditionScore)
    putNullable("bodyConditionUnableToAssess", bodyConditionUnableToAssess)
    putNullable("muscleMass", muscleMass)
    putNullable("neckCircumferenceCm", neckCircumferenceCm)
    putNullable("coatLength", coatLength)
    putNullable("undercoat", undercoat)
    putNullable("shavedAreas", shavedAreas)
    put("observedSigns", JSONArray(observedSigns))
    putNullable("diagnosesPresent", diagnosesPresent)
    putNullable("diagnosesDetails", diagnosesDetails)
    putNullable("housing", housing)
    putNullable("walks", walks)
    putNullable("cohabitants", cohabitants)
    putNullable("shelterPermission", shelterPermission)
    putNullable("notes", notes)
}.toString()

fun dogQuestionnaireFromJson(json: String): DogQuestionnaire {
    val value = JSONObject(json)
    return DogQuestionnaire(
        numberOrName = value.getString("numberOrName"),
        shelterOrPlace = value.nullableString("shelterOrPlace"),
        breed = value.nullableString("breed"),
        resembles = value.nullableString("resembles"),
        size = value.nullableString("size"),
        ageYears = value.nullableInt("ageYears"),
        ageMonths = value.nullableInt("ageMonths"),
        ageSource = value.nullableString("ageSource"),
        sex = value.nullableString("sex"),
        sterilized = value.nullableBoolean("sterilized"),
        weightKg = value.nullableDouble("weightKg"),
        weightSource = value.nullableString("weightSource"),
        bodyConditionScore = value.nullableInt("bodyConditionScore"),
        bodyConditionUnableToAssess = value.nullableBoolean("bodyConditionUnableToAssess"),
        muscleMass = value.nullableString("muscleMass"),
        neckCircumferenceCm = value.nullableDouble("neckCircumferenceCm"),
        coatLength = value.nullableString("coatLength"),
        undercoat = value.nullableString("undercoat"),
        shavedAreas = value.nullableString("shavedAreas"),
        observedSigns = value.optJSONArray("observedSigns").toStringList(),
        diagnosesPresent = value.nullableBoolean("diagnosesPresent"),
        diagnosesDetails = value.nullableString("diagnosesDetails"),
        housing = value.nullableString("housing"),
        walks = value.nullableString("walks"),
        cohabitants = value.nullableString("cohabitants"),
        shelterPermission = value.nullableBoolean("shelterPermission"),
        notes = value.nullableString("notes"),
    )
}

fun SessionQuestionnaire.toJson(): String = JSONObject().apply {
    put("sessionNumber", sessionNumber)
    put("date", date)
    put("startTime", startTime)
    putNullable("operator", operator)
    put("recordingContents", JSONArray(recordingContents))
    putNullable("surface", surface)
    putNullable("location", location)
    putNullable("airTemperatureC", airTemperatureC)
    putNullable("sensorPosition", sensorPosition)
    putNullable("collarTightness", collarTightness)
    putNullable("preMeasurementState", preMeasurementState)
    putNullable("pulseBpm", pulseBpm)
    putNullable("respirationPerMinute", respirationPerMinute)
    putNullable("bodyTemperatureC", bodyTemperatureC)
    putNullable("measurementTime", measurementTime)
}.toString()

fun CaptureSyncMetadata.toJson(
    dogQuestionnaire: DogQuestionnaire,
    sessionQuestionnaire: SessionQuestionnaire?,
): String = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("recordingId", recordingId)
    put("profileId", profileId)
    put("source", source)
    put("timezone", timezone)
    put("selectedSessionStartUtc", selectedSessionStartUtc)
    put("dogQuestionnaire", JSONObject(dogQuestionnaire.toJson()))
    put(
        "sessionQuestionnaire",
        sessionQuestionnaire?.let { JSONObject(it.toJson()) } ?: JSONObject.NULL,
    )
    put(
        "sensor",
        sensor?.let {
            JSONObject().apply {
                put("event", it.event)
                put("absoluteUtc", it.absoluteUtc)
                put("wallClockEpochMillis", it.wallClockEpochMillis)
                put("monotonicTimeNs", it.monotonicTimeNs)
                put("monotonicClock", it.monotonicClock)
                put("samplingUncertaintyNs", it.samplingUncertaintyNs)
            }
        } ?: JSONObject.NULL,
    )
    put(
        "video",
        video?.let {
            JSONObject().apply {
                put("fileName", it.fileName)
                put("requestedAtUtc", it.requestedAtUtc)
                put("requestedMonotonicNs", it.requestedMonotonicNs)
                putNullable("firstFrameAtUtc", it.firstFrameAtUtc)
                putNullable("firstFrameEpochMillis", it.firstFrameEpochMillis)
                putNullable("firstFrameMonotonicNs", it.firstFrameMonotonicNs)
                putNullable("firstFrameCameraTimestampNs", it.firstFrameCameraTimestampNs)
                putNullable("offsetFromSensorNs", it.offsetFromSensorNs)
                putNullable("cameraTimestampSource", it.cameraTimestampSource)
                putNullable("synchronizationQuality", it.synchronizationQuality)
                putNullable("cameraId", it.cameraId)
                putNullable("lensFacing", it.lensFacing)
                putNullable("width", it.width)
                putNullable("height", it.height)
                putNullable("frameRate", it.frameRate)
                putNullable("rotationDegrees", it.rotationDegrees)
                put("codec", it.codec)
                put("container", it.container)
                putNullable("stoppedAtUtc", it.stoppedAtUtc)
                putNullable("stoppedMonotonicNs", it.stoppedMonotonicNs)
                putNullable("durationNs", it.durationNs)
            }
        } ?: JSONObject.NULL,
    )
}.toString()

fun sessionQuestionnaireFromJson(json: String): SessionQuestionnaire {
    val value = JSONObject(json)
    return SessionQuestionnaire(
        sessionNumber = value.getString("sessionNumber"),
        date = value.getString("date"),
        startTime = value.getString("startTime"),
        operator = value.nullableString("operator"),
        recordingContents = value.optJSONArray("recordingContents").toStringList(),
        surface = value.nullableString("surface"),
        location = value.nullableString("location"),
        airTemperatureC = value.nullableInt("airTemperatureC"),
        sensorPosition = value.nullableString("sensorPosition"),
        collarTightness = value.nullableString("collarTightness"),
        preMeasurementState = value.nullableString("preMeasurementState"),
        pulseBpm = value.nullableInt("pulseBpm"),
        respirationPerMinute = value.nullableInt("respirationPerMinute"),
        bodyTemperatureC = value.nullableDouble("bodyTemperatureC"),
        measurementTime = value.nullableString("measurementTime"),
    )
}

fun sessionStartInstant(
    questionnaire: SessionQuestionnaire?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): Instant {
    if (questionnaire == null) return now
    return LocalDate.parse(questionnaire.date)
        .atTime(LocalTime.parse(questionnaire.startTime))
        .atZone(zoneId)
        .toInstant()
}

fun recordingRelativeDirectory(
    profileId: String,
    recordingId: String,
    startedAt: Instant,
    timezone: String,
): String {
    val day = startedAt.atZone(ZoneId.of(timezone)).toLocalDate()
    return "recordings/$profileId/$day/$recordingId"
}

class WoonaDatabase(
    context: Context,
    val rootDirectory: File = File(context.filesDir, "Woona"),
) : SQLiteOpenHelper(
    context,
    File(rootDirectory, "woona.sqlite").absolutePath,
    null,
    DATABASE_VERSION,
) {
    init {
        rootDirectory.mkdirs()
        writableDatabase
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dog_profiles (
                id TEXT PRIMARY KEY,
                number_or_name TEXT NOT NULL CHECK(length(trim(number_or_name)) > 0),
                questionnaire_json TEXT NOT NULL,
                created_at_utc TEXT NOT NULL,
                updated_at_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE recordings (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL REFERENCES dog_profiles(id),
                source TEXT NOT NULL CHECK(source IN ('live','replay')),
                status TEXT NOT NULL CHECK(status IN ('preparing','recording','completed','failed','interrupted')),
                started_at_utc TEXT NOT NULL,
                ended_at_utc TEXT,
                timezone TEXT NOT NULL,
                questionnaire_json TEXT,
                relative_directory TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        createArtifactsTable(db)
        db.execSQL("CREATE INDEX recordings_profile_started ON recordings(profile_id, started_at_utc DESC)")
        db.execSQL("CREATE INDEX artifacts_recording ON artifacts(recording_id)")
        db.execSQL("PRAGMA user_version=2")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE artifacts RENAME TO artifacts_v1")
            db.execSQL("DROP INDEX IF EXISTS artifacts_recording")
            createArtifactsTable(db)
            db.execSQL(
                """
                INSERT INTO artifacts(id,recording_id,type,relative_path,size_bytes,created_at_utc)
                SELECT id,recording_id,type,relative_path,size_bytes,created_at_utc FROM artifacts_v1
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE artifacts_v1")
            db.execSQL("CREATE INDEX artifacts_recording ON artifacts(recording_id)")
        }
    }

    fun interruptUnfinished(now: Instant = Instant.now()): Int {
        val values = ContentValues().apply {
            put("status", RecordingStatus.INTERRUPTED.value)
            put("ended_at_utc", now.toString())
        }
        return writableDatabase.update(
            "recordings",
            values,
            "status IN (?, ?)",
            arrayOf(RecordingStatus.PREPARING.value, RecordingStatus.RECORDING.value),
        )
    }

    fun saveProfile(
        questionnaire: DogQuestionnaire,
        profileId: String? = null,
        now: Instant = Instant.now(),
    ): DogProfile {
        val name = questionnaire.numberOrName.trim()
        require(name.isNotEmpty()) { "Dog number or name is required" }
        val id = profileId ?: UUID.randomUUID().toString()
        val existing = profile(id)
        val createdAt = existing?.createdAtUtc ?: now.toString()
        val values = ContentValues().apply {
            put("id", id)
            put("number_or_name", name)
            put("questionnaire_json", questionnaire.copy(numberOrName = name).toJson())
            put("created_at_utc", createdAt)
            put("updated_at_utc", now.toString())
        }
        writableDatabase.insertWithOnConflict(
            "dog_profiles",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return requireNotNull(profile(id))
    }

    fun profiles(): List<DogProfile> {
        return readableDatabase.query(
            "dog_profiles",
            PROFILE_COLUMNS,
            null,
            null,
            null,
            null,
            "updated_at_utc DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toProfile())
            }
        }
    }

    fun profile(id: String): DogProfile? {
        return readableDatabase.query(
            "dog_profiles",
            PROFILE_COLUMNS,
            "id=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toProfile() else null }
    }

    fun beginRecording(
        profileId: String,
        source: RecordingSource,
        questionnaire: SessionQuestionnaire?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Recording {
        requireNotNull(profile(profileId)) { "Unknown dog profile" }
        val id = questionnaire?.sessionNumber ?: UUID.randomUUID().toString()
        val startedAt = sessionStartInstant(questionnaire, zoneId, now)
        val timezone = zoneId.id
        val relativeDirectory = recordingRelativeDirectory(profileId, id, startedAt, timezone)
        recordingDirectory(relativeDirectory).mkdirs()
        val values = ContentValues().apply {
            put("id", id)
            put("profile_id", profileId)
            put("source", source.value)
            put("status", RecordingStatus.PREPARING.value)
            put("started_at_utc", startedAt.toString())
            putNull("ended_at_utc")
            put("timezone", timezone)
            if (questionnaire == null) putNull("questionnaire_json") else put("questionnaire_json", questionnaire.toJson())
            put("relative_directory", relativeDirectory)
        }
        check(writableDatabase.insertOrThrow("recordings", null, values) != -1L)
        return requireNotNull(recording(id))
    }

    fun markRecording(recordingId: String) {
        val values = ContentValues().apply {
            put("status", RecordingStatus.RECORDING.value)
        }
        writableDatabase.update(
            "recordings",
            values,
            "id=? AND status=?",
            arrayOf(recordingId, RecordingStatus.PREPARING.value),
        )
    }

    fun finishRecording(
        recordingId: String,
        status: RecordingStatus,
        now: Instant = Instant.now(),
    ) {
        require(status in TERMINAL_STATUSES)
        val recording = requireNotNull(recording(recordingId))
        val files = artifactFilesIn(recordingDirectory(recording.relativeDirectory))
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("status", status.value)
                put("ended_at_utc", now.toString())
            }
            writableDatabase.update("recordings", values, "id=?", arrayOf(recordingId))
            files.forEach { (type, file) -> insertArtifact(recordingId, type, file, now) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun registerCsv(recordingId: String, file: File, now: Instant = Instant.now()) {
        registerArtifact(recordingId, ArtifactType.CSV, file, now)
    }

    fun registerArtifact(
        recordingId: String,
        type: ArtifactType,
        file: File,
        now: Instant = Instant.now(),
    ) {
        writableDatabase.beginTransaction()
        try {
            insertArtifact(recordingId, type, file, now)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun writeSyncMetadata(
        recordingId: String,
        metadata: CaptureSyncMetadata,
        now: Instant = Instant.now(),
    ): File {
        val recording = requireNotNull(recording(recordingId))
        require(metadata.recordingId == recordingId && metadata.profileId == recording.profileId)
        val profile = requireNotNull(profile(recording.profileId))
        val directory = recordingDirectory(recording.relativeDirectory)
        val target = File(directory, "sync.json")
        val temporary = File(directory, "sync.json.tmp")
        temporary.writeText(
            metadata.toJson(profile.questionnaire, recording.questionnaire),
            Charsets.UTF_8,
        )
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        registerArtifact(recordingId, ArtifactType.METADATA, target, now)
        return target
    }

    fun recentRecordings(profileId: String, limit: Int = 10): List<Recording> {
        return readableDatabase.query(
            "recordings",
            RECORDING_COLUMNS,
            "profile_id=?",
            arrayOf(profileId),
            null,
            null,
            "started_at_utc DESC",
            limit.coerceAtLeast(0).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val recording = cursor.toRecording()
                    add(recording.copy(artifacts = artifacts(recording.id)))
                }
            }
        }
    }

    fun recording(id: String): Recording? {
        return readableDatabase.query(
            "recordings",
            RECORDING_COLUMNS,
            "id=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val recording = cursor.toRecording()
                recording.copy(artifacts = artifacts(recording.id))
            } else {
                null
            }
        }
    }

    fun recordingSummary(id: String): RecordingSummary? {
        val recording = recording(id) ?: return null
        val profile = profile(recording.profileId) ?: return null
        return RecordingSummary(recording, profile.numberOrName, profile.questionnaire)
    }

    fun artifactFiles(recordingId: String): List<File> {
        return recording(recordingId)?.artifacts.orEmpty().mapNotNull { artifact ->
            resolveRelativePath(artifact.relativePath).takeIf(File::exists)
        }
    }

    fun recordingDirectory(relativeDirectory: String): File = resolveRelativePath(relativeDirectory)

    fun resolveRelativePath(relativePath: String): File {
        val file = File(rootDirectory, relativePath).canonicalFile
        require(file.toPath().startsWith(rootDirectory.canonicalFile.toPath())) { "Path escapes Woona directory" }
        return file
    }

    private fun artifacts(recordingId: String): List<Artifact> {
        return readableDatabase.query(
            "artifacts",
            ARTIFACT_COLUMNS,
            "recording_id=?",
            arrayOf(recordingId),
            null,
            null,
            "created_at_utc, relative_path",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Artifact(
                            id = cursor.getString(0),
                            recordingId = cursor.getString(1),
                            type = ArtifactType.entries.first { it.value == cursor.getString(2) },
                            relativePath = cursor.getString(3),
                            sizeBytes = cursor.getLong(4),
                            createdAtUtc = cursor.getString(5),
                        ),
                    )
                }
            }
        }
    }

    private fun insertArtifact(
        recordingId: String,
        type: ArtifactType,
        file: File,
        now: Instant,
    ) {
        if (!file.exists()) return
        val relativePath = rootDirectory.canonicalFile.toPath()
            .relativize(file.canonicalFile.toPath())
            .toString()
            .replace(File.separatorChar, '/')
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("recording_id", recordingId)
            put("type", type.value)
            put("relative_path", relativePath)
            put("size_bytes", file.length())
            put("created_at_utc", now.toString())
        }
        writableDatabase.insertWithOnConflict(
            "artifacts",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun artifactFilesIn(directory: File): List<Pair<ArtifactType, File>> {
        return listOf(
            ArtifactType.PACKET to File(directory, "packets.bin"),
            ArtifactType.RAW to File(directory, "raw_fragments.binlog"),
            ArtifactType.DIAGNOSTIC to File(directory, "diagnostics.log"),
            ArtifactType.CSV to File(directory, "channel.csv"),
            ArtifactType.VIDEO to File(directory, "video.mp4"),
            ArtifactType.METADATA to File(directory, "sync.json"),
        ).filter { (_, file) -> file.exists() }
    }

    private fun createArtifactsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE artifacts (
                id TEXT PRIMARY KEY,
                recording_id TEXT NOT NULL REFERENCES recordings(id),
                type TEXT NOT NULL CHECK(type IN ('packet','raw','diagnostic','csv','video','metadata')),
                relative_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                created_at_utc TEXT NOT NULL,
                UNIQUE(recording_id, relative_path)
            )
            """.trimIndent(),
        )
    }

    private fun android.database.Cursor.toProfile(): DogProfile = DogProfile(
        id = getString(0),
        numberOrName = getString(1),
        questionnaire = dogQuestionnaireFromJson(getString(2)),
        createdAtUtc = getString(3),
        updatedAtUtc = getString(4),
    )

    private fun android.database.Cursor.toRecording(): Recording = Recording(
        id = getString(0),
        profileId = getString(1),
        source = RecordingSource.entries.first { it.value == getString(2) },
        status = RecordingStatus.entries.first { it.value == getString(3) },
        startedAtUtc = getString(4),
        endedAtUtc = if (isNull(5)) null else getString(5),
        timezone = getString(6),
        questionnaire = if (isNull(7)) null else sessionQuestionnaireFromJson(getString(7)),
        relativeDirectory = getString(8),
    )

    companion object {
        private const val DATABASE_VERSION = 2
        private val PROFILE_COLUMNS = arrayOf(
            "id",
            "number_or_name",
            "questionnaire_json",
            "created_at_utc",
            "updated_at_utc",
        )
        private val RECORDING_COLUMNS = arrayOf(
            "id",
            "profile_id",
            "source",
            "status",
            "started_at_utc",
            "ended_at_utc",
            "timezone",
            "questionnaire_json",
            "relative_directory",
        )
        private val ARTIFACT_COLUMNS = arrayOf(
            "id",
            "recording_id",
            "type",
            "relative_path",
            "size_bytes",
            "created_at_utc",
        )
        private val TERMINAL_STATUSES = setOf(
            RecordingStatus.COMPLETED,
            RecordingStatus.FAILED,
            RecordingStatus.INTERRUPTED,
        )
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)

private fun JSONObject.nullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else getDouble(key)

private fun JSONObject.nullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else getBoolean(key)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> getString(index) }
}

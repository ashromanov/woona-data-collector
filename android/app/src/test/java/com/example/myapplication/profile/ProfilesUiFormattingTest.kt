package com.example.myapplication.profile

import com.example.myapplication.data.Artifact
import com.example.myapplication.data.ArtifactType
import com.example.myapplication.data.Recording
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.RecordingStatus
import com.example.myapplication.localization.AppLanguage
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilesUiFormattingTest {
    @Test
    fun historyUsesLocalTimeAndHumanDataLabels() {
        val recording = Recording(
            id = "recording",
            profileId = "dog",
            profileVersionId = "dog-v1",
            source = RecordingSource.LIVE,
            status = RecordingStatus.COMPLETED,
            sessionLabel = "Walk",
            videoRequested = true,
            startedAtUtc = "2026-09-05T12:00:00Z",
            endedAtUtc = "2026-09-05T12:10:00Z",
            timezone = "Europe/Moscow",
            questionnaire = null,
            relativeDirectory = "dog/recording",
            artifacts = listOf("packets.bin", "video.mp4", "polar_ecg.csv").mapIndexed { index, name ->
                Artifact(
                    id = "artifact-$index",
                    recordingId = "recording",
                    type = if (name == "video.mp4") ArtifactType.VIDEO else ArtifactType.CSV,
                    fileName = name,
                    mimeType = "application/octet-stream",
                    relativePath = name,
                    sizeBytes = 1,
                    sha256 = "hash",
                    localPresence = "local",
                    uploadState = "pending",
                    uploadedBytes = 0,
                    createdAtUtc = "2026-09-05T12:00:00Z",
                )
            },
        )

        assertEquals("05.09.2026 15:00", recordingDisplayTime(recording.startedAtUtc, ZoneId.of("Europe/Moscow")))
        assertEquals(listOf("collar", "video", "Polar HR/ECG/ACC"), recordingDataLabels(recording, AppLanguage.ENGLISH))
    }
}

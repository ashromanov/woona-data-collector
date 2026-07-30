package com.example.myapplication.drive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDriveSaveCoordinatorTest {
    @Test
    fun save_enqueuesOnlyTheArchiveCreatedForThisRequest() = withTempDirectory { directory ->
        val currentArchive = File(directory, "current-session.zip").apply {
            writeText("archive")
        }
        val store = ManualDriveSaveStore(File(directory, "state"))
        val enqueuedArchives = mutableListOf<File>()
        val wifiOnlyValues = mutableListOf<Boolean>()
        val coordinator = coordinator(
            store = store,
            createArchive = { currentArchive },
            enqueueArchive = { archive, wifiOnly ->
                enqueuedArchives += archive
                wifiOnlyValues += wifiOnly
            },
        )

        val result = coordinator.save(isAuthorized = true, wifiOnly = false)

        assertEquals(ManualDriveSaveResult.Queued, result)
        assertEquals(1, enqueuedArchives.size)
        assertSame(currentArchive, enqueuedArchives.single())
        assertEquals(listOf(false), wifiOnlyValues)
        assertNull(store.pendingArchive())
    }

    @Test
    fun save_persistsTheExactArchiveUntilAuthorizationCompletes() = withTempDirectory { directory ->
        val currentArchive = File(directory, "current-session.zip").apply {
            writeText("archive")
        }
        val stateDirectory = File(directory, "state")
        var createArchiveCalls = 0
        val firstCoordinator = coordinator(
            store = ManualDriveSaveStore(stateDirectory),
            createArchive = {
                createArchiveCalls++
                currentArchive
            },
        )

        val authorizationResult = firstCoordinator.save(
            isAuthorized = false,
            wifiOnly = true,
        )

        assertEquals(ManualDriveSaveResult.AuthorizationRequired, authorizationResult)
        assertEquals(1, createArchiveCalls)

        val restoredStore = ManualDriveSaveStore(stateDirectory)
        assertEquals(currentArchive.absolutePath, restoredStore.pendingArchive()?.absolutePath)
        val enqueuedArchives = mutableListOf<File>()
        val restoredCoordinator = coordinator(
            store = restoredStore,
            createArchive = {
                createArchiveCalls++
                File(directory, "unexpected.zip")
            },
            enqueueArchive = { archive, _ -> enqueuedArchives += archive },
        )

        val uploadResult = restoredCoordinator.save(
            isAuthorized = true,
            wifiOnly = true,
        )

        assertEquals(ManualDriveSaveResult.Queued, uploadResult)
        assertEquals(1, createArchiveCalls)
        assertEquals(listOf(currentArchive.absolutePath), enqueuedArchives.map { it.absolutePath })
        assertNull(restoredStore.pendingArchive())
    }

    @Test
    fun save_doesNotCreateAnArchiveWhenThePendingQueueIsFull() = withTempDirectory { directory ->
        var createArchiveCalls = 0
        var enqueueCalls = 0
        val coordinator = coordinator(
            store = ManualDriveSaveStore(File(directory, "state")),
            hasPendingCapacity = { false },
            createArchive = {
                createArchiveCalls++
                File(directory, "unexpected.zip")
            },
            enqueueArchive = { _, _ -> enqueueCalls++ },
        )

        val result = coordinator.save(isAuthorized = true, wifiOnly = true)

        assertEquals(ManualDriveSaveResult.PendingLimitReached, result)
        assertEquals(0, createArchiveCalls)
        assertEquals(0, enqueueCalls)
    }

    @Test
    fun save_keepsTheArtifactRecordedWhenEnqueueFails() = withTempDirectory { directory ->
        val currentArchive = File(directory, "current-session.zip").apply {
            writeText("archive")
        }
        val failure = IllegalStateException("WorkManager unavailable")
        val store = ManualDriveSaveStore(File(directory, "state"))
        val coordinator = coordinator(
            store = store,
            createArchive = { currentArchive },
            enqueueArchive = { _, _ -> throw failure },
        )

        val result = coordinator.save(isAuthorized = true, wifiOnly = true)

        assertTrue(result is ManualDriveSaveResult.Failed)
        assertSame(failure, (result as ManualDriveSaveResult.Failed).cause)
        assertEquals(currentArchive.absolutePath, store.pendingArchive()?.absolutePath)
    }

    @Test
    fun runtimeClaim_preventsDuplicateWorkUntilReleased() {
        ManualDriveSaveCoordinator.releaseClaim()
        try {
            assertTrue(ManualDriveSaveCoordinator.tryClaim())
            assertFalse(ManualDriveSaveCoordinator.tryClaim())
        } finally {
            ManualDriveSaveCoordinator.releaseClaim()
        }
        assertTrue(ManualDriveSaveCoordinator.tryClaim())
        ManualDriveSaveCoordinator.releaseClaim()
    }

    @Test
    fun discardPendingArchive_removesTheRecordAndArtifact() = withTempDirectory { directory ->
        val archive = File(directory, "current-session.zip").apply {
            writeText("archive")
        }
        val store = ManualDriveSaveStore(File(directory, "state"))
        store.savePendingArchive(archive)

        store.discardPendingArchive()

        assertFalse(archive.exists())
        assertNull(store.pendingArchive())
    }

    private fun coordinator(
        store: ManualDriveSaveStore,
        hasPendingCapacity: () -> Boolean = { true },
        createArchive: () -> File?,
        enqueueArchive: (File, Boolean) -> Unit = { _, _ -> },
    ): ManualDriveSaveCoordinator {
        return ManualDriveSaveCoordinator(
            store = store,
            hasPendingCapacity = hasPendingCapacity,
            createArchive = createArchive,
            enqueueArchive = enqueueArchive,
        )
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "manual-drive-save-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

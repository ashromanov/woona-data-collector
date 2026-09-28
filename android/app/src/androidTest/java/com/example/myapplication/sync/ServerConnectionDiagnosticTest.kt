package com.example.myapplication.sync

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.restoreServerMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ServerConnectionDiagnosticTest {
    @Test
    fun readProductionMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokenFile = File(context.cacheDir, "connection-test.token")
        assumeTrue("Live credential was not supplied", tokenFile.isFile)
        val client = ServerApiClient(ServerSettings("https://cool-trams.digital", tokenFile.readText().trim(), "diagnostic", false))
        val started = System.nanoTime()
        assertTrue(client.readiness())
        val snapshot = client.fetchRestoreSnapshot()
        val root = File(context.cacheDir, "connection-test-db-${System.nanoTime()}")
        val database = WoonaDatabase(context, root)
        try {
            val restored = database.restoreServerMetadata(snapshot.dogs, snapshot.recordings)
            assertEquals(snapshot.dogs.size, restored.dogs)
            assertEquals(snapshot.recordings.size, restored.recordings)
            val repeated = database.restoreServerMetadata(snapshot.dogs, snapshot.recordings)
            assertEquals(0, repeated.dogs)
            assertEquals(0, repeated.recordings)
            val artifacts = snapshot.recordings.flatMap { it.artifacts }.filter { it.storageStatus == "available" }
            artifacts.filter { it.sizeBytes <= 5 * 1024 * 1024 }.groupBy { it.type }.forEach { (type, candidates) ->
                val artifact = candidates.minBy { it.sizeBytes }
                val target = File(root, "live-${artifact.id}-${artifact.fileName}")
                client.downloadArtifact(artifact.id, artifact.sizeBytes, artifact.sha256, target)
                assertEquals(artifact.sizeBytes, target.length())
                Log.i("WOONA_CONNECTION_TEST", "downloadType=$type bytes=${target.length()} shaVerified=true")
                target.delete()
            }
            artifacts.filter { it.type == "video" }.minByOrNull { it.sizeBytes }?.let { video ->
                val connection = URL("https://cool-trams.digital/v1/artifacts/${video.id}/content").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Authorization", "Bearer ${tokenFile.readText().trim()}")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Range", "bytes=0-65535")
                    assertEquals(206, connection.responseCode)
                    val bytes = connection.inputStream.use { it.readBytes() }
                    assertEquals(minOf(65536L, video.sizeBytes), bytes.size.toLong())
                    assertTrue(connection.getHeaderField("Content-Range").startsWith("bytes 0-"))
                    assertEquals(video.sha256, connection.getHeaderField("ETag").trim('"'))
                    Log.i("WOONA_CONNECTION_TEST", "videoRangeBytes=${bytes.size} etagVerified=true")
                } finally {
                    connection.disconnect()
                }
            }
            Log.i("WOONA_CONNECTION_TEST", "dogs=${restored.dogs} recordings=${restored.recordings} artifacts=${restored.artifacts} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}

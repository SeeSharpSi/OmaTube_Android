package dev.omatube.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

// Real-device coverage for the production DataStore and Android Keystore
// paths. Uses isolated file names and cleans up after itself; never touches
// the production datastore file name.
@RunWith(AndroidJUnit4::class)
class DataStoreSettingsStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileNames = mutableListOf<String>()

    @After
    fun tearDown() {
        fileNames.forEach { fileName ->
            File(context.filesDir, "datastore/$fileName.preferences_pb").delete()
            File(context.filesDir, "datastore/$fileName.preferences_pb.tmp").delete()
        }
        fileNames.clear()
        // Only the test-created key file slot is shared; production never runs
        // alongside these tests.
        File(context.noBackupFilesDir, DataStoreSettingsStore.API_KEY_FILE_NAME).delete()
    }

    private fun newFileName(): String = "settings-it-${UUID.randomUUID()}".also { fileNames.add(it) }

    private fun newStore(fileName: String): DataStoreSettingsStore =
        DataStoreSettingsStore(context, false, fileName)

    private fun dataStoreFile(fileName: String): File =
        File(context.filesDir, "datastore/$fileName.preferences_pb")

    private fun apiKeyFile(): File = File(context.noBackupFilesDir, DataStoreSettingsStore.API_KEY_FILE_NAME)

    @Test
    fun sessionOnlyKeyIsNotPersistedAcrossColdRecreation() = runBlocking {
        val fileName = newFileName()
        val first = newStore(fileName)
        first.update { it.copy(apiKey = "session-secret", rememberApiKey = false) }
        assertEquals("session-secret", first.settings.first().apiKey)
        first.closeAndJoin()

        assertFalse(fileContains(dataStoreFile(fileName), "session-secret"))
        assertFalse(apiKeyFile().exists())

        val second = newStore(fileName)
        try {
            assertFalse(second.settings.first().rememberApiKey)
            assertEquals("", second.settings.first().apiKey)
        } finally {
            second.closeAndJoin()
        }
    }

    @Test
    fun rememberedKeyIsEncryptedAtRestAndRestoredAfterReopen() = runBlocking {
        val fileName = newFileName()
        val first = newStore(fileName)
        first.update { it.copy(themeId = "nord", apiKey = "top-secret", rememberApiKey = true) }
        assertEquals("top-secret", first.settings.first().apiKey)
        first.closeAndJoin()

        assertTrue(apiKeyFile().exists())
        assertFalse(fileContains(apiKeyFile(), "top-secret"))
        assertFalse(fileContains(dataStoreFile(fileName), "top-secret"))

        val second = newStore(fileName)
        try {
            val settings = second.settings.first()
            assertTrue(settings.rememberApiKey)
            assertEquals("top-secret", settings.apiKey)
            assertEquals("nord", settings.themeId)
        } finally {
            second.closeAndJoin()
        }
    }

    @Test
    fun clearingRememberDeletesPersistedCiphertextButKeepsSessionKey() = runBlocking {
        val fileName = newFileName()
        val store = newStore(fileName)
        try {
            store.update { it.copy(apiKey = "top-secret", rememberApiKey = true) }
            assertTrue(apiKeyFile().exists())

            store.update { it.copy(rememberApiKey = false) }
            assertFalse(apiKeyFile().exists())

            val settings = store.settings.first()
            assertFalse(settings.rememberApiKey)
            assertEquals("top-secret", settings.apiKey)
        } finally {
            store.closeAndJoin()
        }
    }

    private fun fileContains(file: File, needle: String): Boolean =
        file.exists() && file.readText().contains(needle)
}

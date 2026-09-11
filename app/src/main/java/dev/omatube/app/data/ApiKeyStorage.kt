package dev.omatube.app.data

import android.content.Context
import java.io.File

// Encryption seam so tests can simulate a missing or invalid Keystore key
// without touching the platform Keystore.
internal interface ApiKeyCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String?
}

// Stores only the encrypted API key ciphertext. The platform implementation
// lives under Context.noBackupFilesDir, which is excluded from cloud backup
// and device transfer, while ordinary settings stay in DataStore and remain
// backed up.
internal interface ApiKeyStorage {
    fun read(): String?

    fun write(ciphertext: String)

    fun clear()
}

internal class FileApiKeyStorage(private val file: File) : ApiKeyStorage {

    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    constructor(directory: File, fileName: String) : this(File(directory, fileName))

    @Synchronized
    override fun read(): String? {
        if (!file.exists()) return null
        return file.readText().trim().ifEmpty { null }
    }

    @Synchronized
    override fun write(ciphertext: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(ciphertext)
        if (!temporary.renameTo(file)) {
            file.writeText(ciphertext)
            temporary.delete()
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    companion object {
        const val FILE_NAME = DataStoreSettingsStore.API_KEY_FILE_NAME
    }
}

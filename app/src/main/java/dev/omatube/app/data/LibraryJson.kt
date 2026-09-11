package dev.omatube.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

// Exact desktop-compatible version 1 import/export formats.
internal class LibraryImportException(message: String) : IllegalArgumentException(message)

internal data class ExportedChannel(
    val id: String,
    val originalInput: String,
    val handle: String,
    val title: String,
    val avatarUrl: String,
    val uploadsPlaylistId: String,
    val metadataFetchedAt: Long,
    val categoryNames: List<String>,
)

internal data class ExportedCategory(
    val name: String,
    val channelIds: List<String>,
)

internal data class ImportChannel(
    val id: String,
    val originalInput: String,
    val handle: String,
    val title: String,
    val avatarUrl: String,
    val uploadsPlaylistId: String,
    val metadataFetchedAt: Long,
    val categories: List<String>,
)

internal data class ImportCategory(
    val name: String,
    val channelIds: List<String>,
)

internal sealed interface ParsedImport {
    data class Channels(val records: List<ImportChannel>) : ParsedImport
    data class Categories(val records: List<ImportCategory>) : ParsedImport
}

internal object LibraryJson {

    const val CHANNELS_FORMAT = "omatube-channels"
    const val CATEGORIES_FORMAT = "omatube-categories"
    const val VERSION = 1
    private const val METADATA_FETCHED_AT = "metadataFetchedAt"

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

    fun formatTimestamp(epochMillis: Long): String =
        if (epochMillis <= 0) "" else TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    fun parseTimestamp(text: String): Long {
        if (text.isBlank()) return 0
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (first: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (second: DateTimeParseException) {
                throw LibraryImportException("Import field '$METADATA_FETCHED_AT' is invalid.")
            }
        }
    }

    fun encodeChannels(channels: List<ExportedChannel>): String {
        val array = JSONArray()
        for (channel in channels) {
            val categories = JSONArray()
            for (name in channel.categoryNames) {
                categories.put(name)
            }
            val objectValue = JSONObject()
                .put("id", channel.id)
                .put("originalInput", channel.originalInput)
                .put("handle", channel.handle)
                .put("title", channel.title)
                .put("avatarUrl", channel.avatarUrl)
                .put("uploadsPlaylistId", channel.uploadsPlaylistId)
                .put(METADATA_FETCHED_AT, formatTimestamp(channel.metadataFetchedAt))
                .put("categories", categories)
            array.put(objectValue)
        }
        return JSONObject()
            .put("format", CHANNELS_FORMAT)
            .put("version", VERSION)
            .put("channels", array)
            .toString()
    }

    fun encodeCategories(categories: List<ExportedCategory>): String {
        val array = JSONArray()
        for (category in categories) {
            val channelIds = JSONArray()
            for (channelId in category.channelIds) {
                channelIds.put(channelId)
            }
            val objectValue = JSONObject()
                .put("name", category.name)
                .put("channelIds", channelIds)
            array.put(objectValue)
        }
        return JSONObject()
            .put("format", CATEGORIES_FORMAT)
            .put("version", VERSION)
            .put("categories", array)
            .toString()
    }

    fun parse(json: String): ParsedImport {
        val root = try {
            JSONObject(json)
        } catch (exception: JSONException) {
            throw LibraryImportException("Import file is not a valid JSON object.")
        }

        val format = root.opt("format")
        if (format !is String) {
            throw LibraryImportException("Import file has the wrong OmaTube format.")
        }
        val version = root.opt("version")
        if (version !is Number || version.toDouble() != VERSION.toDouble()) {
            throw LibraryImportException("Import file version is not supported.")
        }

        return when (format) {
            CHANNELS_FORMAT -> ParsedImport.Channels(parseChannels(root))
            CATEGORIES_FORMAT -> ParsedImport.Categories(parseCategories(root))
            else -> throw LibraryImportException("Import file has the wrong OmaTube format.")
        }
    }

    private fun parseChannels(root: JSONObject): List<ImportChannel> {
        val array = root.opt("channels")
        if (array !is JSONArray) {
            throw LibraryImportException("Import file is missing the 'channels' array.")
        }

        val records = ArrayList<ImportChannel>(array.length())
        val seenIds = HashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is JSONObject) {
                throw LibraryImportException("Every channel entry must be a JSON object.")
            }

            val id = requiredString(value, "id").trim()
            val title = requiredString(value, "title").trim()
            val uploadsPlaylistId = requiredString(value, "uploadsPlaylistId").trim()
            if (!seenIds.add(id)) {
                throw LibraryImportException("Import file contains duplicate channel IDs.")
            }

            val originalInput = optionalString(value, "originalInput")
            val handle = optionalString(value, "handle")
            val avatarUrl = optionalString(value, "avatarUrl")

            var metadataFetchedAt = 0L
            if (value.has(METADATA_FETCHED_AT) && !value.isNull(METADATA_FETCHED_AT)) {
                val raw = value.opt(METADATA_FETCHED_AT)
                if (raw !is String) {
                    throw LibraryImportException("Import field '$METADATA_FETCHED_AT' must be a string.")
                }
                metadataFetchedAt = parseTimestamp(raw)
            }

            val categories = ArrayList<String>()
            if (value.has("categories") && !value.isNull("categories")) {
                val categoryArray = value.opt("categories")
                if (categoryArray !is JSONArray) {
                    throw LibraryImportException("Import field 'categories' must be an array.")
                }
                val seenNames = HashSet<String>()
                for (categoryIndex in 0 until categoryArray.length()) {
                    val nameValue = categoryArray.opt(categoryIndex)
                    if (nameValue !is String || nameValue.trim().isEmpty()) {
                        throw LibraryImportException("Channel category names must be non-empty strings.")
                    }
                    val name = nameValue.trim()
                    if (seenNames.add(name)) {
                        categories.add(name)
                    }
                }
            }

            records.add(
                ImportChannel(
                    id = id,
                    originalInput = originalInput,
                    handle = handle,
                    title = title,
                    avatarUrl = avatarUrl,
                    uploadsPlaylistId = uploadsPlaylistId,
                    metadataFetchedAt = metadataFetchedAt,
                    categories = categories,
                ),
            )
        }
        return records
    }

    private fun parseCategories(root: JSONObject): List<ImportCategory> {
        val array = root.opt("categories")
        if (array !is JSONArray) {
            throw LibraryImportException("Import file is missing the 'categories' array.")
        }

        val records = ArrayList<ImportCategory>(array.length())
        val seenNames = HashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is JSONObject) {
                throw LibraryImportException("Every category entry must be a JSON object.")
            }

            val name = requiredString(value, "name").trim()
            if (!seenNames.add(name)) {
                throw LibraryImportException("Import file contains duplicate category names.")
            }

            val channelIds = ArrayList<String>()
            if (value.has("channelIds") && !value.isNull("channelIds")) {
                val idArray = value.opt("channelIds")
                if (idArray !is JSONArray) {
                    throw LibraryImportException("Import field 'channelIds' must be an array.")
                }
                val ids = HashSet<String>()
                for (idIndex in 0 until idArray.length()) {
                    val idValue = idArray.opt(idIndex)
                    if (idValue !is String || idValue.trim().isEmpty()) {
                        throw LibraryImportException("Category channel IDs must be non-empty strings.")
                    }
                    val channelId = idValue.trim()
                    if (ids.add(channelId)) {
                        channelIds.add(channelId)
                    }
                }
            }

            records.add(ImportCategory(name = name, channelIds = channelIds))
        }
        return records
    }

    private fun requiredString(objectValue: JSONObject, key: String): String {
        if (!objectValue.has(key) || objectValue.isNull(key)) {
            throw LibraryImportException("Import field '$key' must be a non-empty string.")
        }
        val value = objectValue.opt(key)
        if (value !is String || value.trim().isEmpty()) {
            throw LibraryImportException("Import field '$key' must be a non-empty string.")
        }
        return value
    }

    private fun optionalString(objectValue: JSONObject, key: String): String {
        if (!objectValue.has(key) || objectValue.isNull(key)) return ""
        val value = objectValue.opt(key)
        if (value !is String) {
            throw LibraryImportException("Import field '$key' must be a string.")
        }
        return value
    }
}

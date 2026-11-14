package sh.hnet.calculator.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calculator's backup file (SPEC §13.3), shaped as Music's, Clock's and News's: the settings, the
 * Programmer's word, the converter's unit pairs, the angle unit and both History lists. Not in it:
 * the current expressions, Ans, the last mode (memories), the app language. Pure Kotlin, unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
    val programmer: BackupProgrammer = BackupProgrammer(),
    val converterPairs: List<BackupPair> = emptyList(),
    val history: List<BackupEntry> = emptyList(),
) {
    companion object {
        const val FORMAT = "sh.hnet.calculator.backup"
        const val FORMAT_VERSION = 1
    }
}

/** Every setting may be absent (a backup from before it existed): a restore then leaves the default. Enum values by name. */
@Serializable
data class BackupSettings(
    /** "automatic" or "0" … "10". */
    val decimalPlaces: String? = null,
    val scientificNotation: String? = null,
    val thousandsSeparator: Boolean? = null,
    val vibrateOnKey: Boolean? = null,
    val angleUnit: String? = null,
    val keepHistory: Boolean? = null,
)

@Serializable
data class BackupProgrammer(val wordSize: Int? = null, val signed: Boolean? = null)

/** A category's unit pair, by the units' ids. */
@Serializable
data class BackupPair(val category: String, val from: String, val to: String)

/**
 * A History entry: its expression and result as stored (language-independent token text), when,
 * and for the Programmer its base, word size and sign.
 */
@Serializable
data class BackupEntry(
    val mode: String,
    val expression: String,
    val result: String,
    /** Milliseconds since the epoch. */
    val createdAt: Long,
    val base: String? = null,
    val bits: Int? = null,
    val signed: Boolean? = null,
)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}

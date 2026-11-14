package sh.hnet.calculator.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.hnet.calculator.BuildConfig
import sh.hnet.calculator.CalcSettings
import sh.hnet.calculator.CalculatorApplication
import sh.hnet.calculator.data.CalculatorDatabase
import sh.hnet.calculator.data.CalculatorStore
import sh.hnet.calculator.data.ConverterStore
import sh.hnet.calculator.data.HistoryEntry
import sh.hnet.calculator.data.HistoryMode
import sh.hnet.calculator.data.HistoryRepository
import sh.hnet.calculator.data.ProgrammerStore
import sh.hnet.calculator.engine.convert.Category
import sh.hnet.calculator.engine.convert.Units
import sh.hnet.calculator.engine.expr.TokenCodec
import sh.hnet.calculator.engine.format.Notation
import sh.hnet.calculator.engine.number.AngleUnit
import sh.hnet.calculator.engine.programmer.Base
import sh.hnet.calculator.engine.programmer.Word
import java.io.IOException
import java.time.Instant

/**
 * Backup and restore (SPEC §13.3), as Music's, Clock's and News's: writes a file the user picked
 * and reads one back; a restore replaces everything in it. Nothing is logged.
 */
class BackupManager(private val app: CalculatorApplication, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val database = CalculatorDatabase.get(app)
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    /** What a restore brought, and what the open modes must take over. */
    class Restored(val calculations: Int, val programmerCalculations: Int, val angle: AngleUnit, val word: Word, val pairs: Map<Category, Pair<String, String>>)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet. */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /**
     * Replaces the settings, the word, the pairs, the angle unit and both History lists with the
     * backup's. What the backup leaves out returns to its default; entries that can't be read (a
     * damaged line) are left out, each list keeps at most its limit.
     */
    suspend fun restore(backup: BackupFile): Restored = withContext(Dispatchers.IO) {
        val s = backup.settings
        app.preferences.replace(
            CalcSettings(
                decimals = s.decimalPlaces?.toIntOrNull()?.takeIf { it in 0..10 },
                notation = s.scientificNotation?.let { name -> Notation.entries.firstOrNull { it.name == name } } ?: Notation.AUTOMATIC,
                grouping = s.thousandsSeparator ?: true,
                vibrate = s.vibrateOnKey ?: true,
            ),
        )
        val angle = s.angleUnit?.let { name -> AngleUnit.entries.firstOrNull { it.name == name } } ?: AngleUnit.DEGREES
        CalculatorStore(app).saveAngle(angle)
        val word = Word(backup.programmer.wordSize?.takeIf { it in setOf(8, 16, 32, 64) } ?: 64, backup.programmer.signed ?: true)
        ProgrammerStore(app).saveWord(word)
        val pairs = backup.converterPairs.mapNotNull { p ->
            val c = Category.entries.firstOrNull { it.name == p.category } ?: return@mapNotNull null
            if (Units.find(c, p.from) == null || Units.find(c, p.to) == null) return@mapNotNull null
            c to (p.from to p.to)
        }.toMap()
        ConverterStore(app).savePairs(pairs)

        val entries = backup.history.mapNotNull { e ->
            val mode = HistoryMode.entries.firstOrNull { it.name == e.mode } ?: return@mapNotNull null
            if (TokenCodec.decode(e.expression) == null || TokenCodec.decodeReal(e.result) == null) return@mapNotNull null
            HistoryEntry(
                mode = mode.name,
                expression = e.expression,
                result = e.result,
                createdAt = e.createdAt,
                base = e.base?.takeIf { name -> Base.entries.any { it.name == name } },
                bits = e.bits,
                signed = e.signed,
            )
        }.groupBy { it.mode }.mapValues { (_, list) -> list.sortedBy { it.createdAt }.takeLast(HistoryRepository.LIMIT) }
        database.history().replaceAll(entries.values.flatten())
        Restored(
            calculations = entries[HistoryMode.CALCULATOR.name]?.size ?: 0,
            programmerCalculations = entries[HistoryMode.PROGRAMMER.name]?.size ?: 0,
            angle = angle,
            word = word,
            pairs = pairs,
        )
    }

    private suspend fun snapshot(): BackupFile {
        val settings = app.preferences.current()
        val calculator = CalculatorStore(app).load()
        val programmer = ProgrammerStore(app).load()
        val converter = ConverterStore(app).load()
        val history = database.history().all()
        return BackupFile(
            appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = Instant.now().toString(),
            settings = BackupSettings(
                decimalPlaces = settings.decimals?.toString() ?: AUTOMATIC,
                scientificNotation = settings.notation.name,
                thousandsSeparator = settings.grouping,
                vibrateOnKey = settings.vibrate,
                angleUnit = calculator.angle.name,
            ),
            programmer = BackupProgrammer(programmer.word.bits, programmer.word.signed),
            converterPairs = converter.memories.map { (c, m) -> BackupPair(c.name, m.from, m.to) },
            history = history.map { BackupEntry(it.mode, it.expression, it.result, it.createdAt, it.base, it.bits, it.signed) },
        )
    }

    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        const val AUTOMATIC = "automatic"
        /** Far above any real backup: two thousand calculations are well under a megabyte. */
        const val MAX_BYTES = 16 * 1_048_576
    }
}

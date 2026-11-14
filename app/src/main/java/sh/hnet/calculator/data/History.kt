package sh.hnet.calculator.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.flow.Flow

/** Which list an entry belongs to (SPEC §10.1): the Calculator's or the Programmer's. */
enum class HistoryMode { CALCULATOR, PROGRAMMER }

/**
 * One calculation (SPEC §10.1): the expression and its result as [sh.hnet.calculator.engine.expr.TokenCodec]
 * text (the result exact, or to its first thousand significant digits), when, and for the
 * Programmer its base, word size and sign. [removedAt] marks an entry removed and waiting for its
 * Undo: hidden everywhere, purged when the snackbar ends without Undo or at the next start (as News).
 */
@Entity(tableName = "history", indices = [Index("mode", "createdAt")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val expression: String,
    val result: String,
    val createdAt: Long,
    val base: String? = null,
    val bits: Int? = null,
    val signed: Boolean? = null,
    val removedAt: Long? = null,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE mode = :mode AND removedAt IS NULL ORDER BY createdAt, id")
    fun observe(mode: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE mode = :mode AND removedAt IS NULL ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun last(mode: String): HistoryEntry?

    @Query("SELECT * FROM history WHERE removedAt IS NULL ORDER BY createdAt, id")
    suspend fun all(): List<HistoryEntry>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Insert
    suspend fun insertAll(entries: List<HistoryEntry>)

    /** Keeps the newest [keep] of a list (SPEC §10.1: 1,000 a list, the oldest dropped silently). */
    @Query(
        "DELETE FROM history WHERE mode = :mode AND id NOT IN " +
            "(SELECT id FROM history WHERE mode = :mode AND removedAt IS NULL ORDER BY createdAt DESC, id DESC LIMIT :keep) AND removedAt IS NULL",
    )
    suspend fun trim(mode: String, keep: Int)

    @Query("UPDATE history SET removedAt = :at WHERE id = :id")
    suspend fun markRemoved(id: Long, at: Long)

    @Query("UPDATE history SET removedAt = :at WHERE mode = :mode AND removedAt IS NULL")
    suspend fun markAllRemoved(mode: String, at: Long)

    @Query("UPDATE history SET removedAt = :at WHERE removedAt IS NULL")
    suspend fun markEverythingRemoved(at: Long)

    @Query("UPDATE history SET removedAt = NULL WHERE removedAt = :at")
    suspend fun restore(at: Long)

    @Query("DELETE FROM history WHERE removedAt = :at")
    suspend fun purge(at: Long)

    @Query("DELETE FROM history WHERE removedAt IS NOT NULL")
    suspend fun purgeAll()

    @Query("DELETE FROM history")
    suspend fun deleteEverything()

    /** Restore from backup: both lists replaced at once (no merging). */
    @Transaction
    suspend fun replaceAll(entries: List<HistoryEntry>) {
        deleteEverything()
        insertAll(entries)
    }
}

@Database(entities = [HistoryEntry::class], version = 1)
abstract class CalculatorDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: CalculatorDatabase? = null

        fun get(context: Context): CalculatorDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder<CalculatorDatabase>(context.applicationContext, "calculator.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
                .also { instance = it }
        }
    }
}

/**
 * The history lists (SPEC §10): an entry for every successful "=", identical consecutive entries
 * within a minute not repeated, 1,000 a list. Removing marks entries with one moment, the key of
 * their Undo.
 */
class HistoryRepository(private val dao: HistoryDao) {

    fun observe(mode: HistoryMode): Flow<List<HistoryEntry>> = dao.observe(mode.name)

    suspend fun add(entry: HistoryEntry) {
        val last = dao.last(entry.mode)
        if (last != null && last.expression == entry.expression && last.result == entry.result &&
            last.base == entry.base && last.bits == entry.bits && last.signed == entry.signed &&
            entry.createdAt - last.createdAt < DUPLICATE_WINDOW
        ) return
        dao.insert(entry)
        dao.trim(entry.mode, LIMIT)
    }

    /** @return the moment that undoes it ([restore]) or makes it final ([purge]) */
    suspend fun remove(id: Long): Long = stamp().also { dao.markRemoved(id, it) }

    suspend fun clear(mode: HistoryMode): Long = stamp().also { dao.markAllRemoved(mode.name, it) }

    /** Both lists at once (Settings › History). */
    suspend fun clearAll(): Long = stamp().also { dao.markEverythingRemoved(it) }

    suspend fun restore(at: Long) = dao.restore(at)

    suspend fun purge(at: Long) = dao.purge(at)

    /** At start: what waited for an Undo when the app went away. */
    suspend fun purgeRemoved() = dao.purgeAll()

    // Distinct moments, also for two removals within one millisecond
    private var lastStamp = 0L

    @Synchronized
    private fun stamp(): Long {
        lastStamp = maxOf(System.currentTimeMillis(), lastStamp + 1)
        return lastStamp
    }

    companion object {
        const val LIMIT = 1000
        private const val DUPLICATE_WINDOW = 60_000L
    }
}

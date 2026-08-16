package com.local.spacedcards.data.state

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.local.spacedcards.core.ReviewState

@Entity(tableName = "review_state")
data class ReviewStateEntity(
    @PrimaryKey val cardUid: String,
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    val reps: Int,
    val lapses: Int,
    val lastReview: Long,
)

@Entity(
    tableName = "review_log",
    indices = [Index("cardUid")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardUid: String,
    val ts: Long,
    val grade: String,
    val elapsedMs: Long,
)

/**
 * Le card segnate "so" (swipe a destra / Good) escono dal giro corrente e non
 * si ripresentano finche' non si esaurisce il mazzo (nessuna card rimasta
 * fuori da questa tabella) o l'utente non fa un reset manuale -- in entrambi
 * i casi la tabella si svuota per intero, e' lo stesso identico gesto.
 *
 * Deliberatamente separata da `review_state`: quella tiene lo storico a
 * lungo termine (stability/due, materiale per un vero FSRS futuro), questa
 * tiene solo "e' fuori dal giro di oggi", un concetto di sessione che non
 * deve influenzare ne' essere influenzato dalla schedulazione a lungo
 * termine.
 */
@Entity(
    tableName = "known_this_pass",
    primaryKeys = ["cardUid", "raccoltaUid"],
)
data class KnownThisPassEntity(
    val cardUid: String,
    val raccoltaUid: String,
)

@Dao
interface KnownPassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markKnown(entity: KnownThisPassEntity)

    @Query("DELETE FROM known_this_pass WHERE raccoltaUid = :raccoltaUid")
    suspend fun clearAll(raccoltaUid: String)

    @Query("SELECT COUNT(*) FROM known_this_pass WHERE raccoltaUid = :raccoltaUid")
    suspend fun countKnown(raccoltaUid: String): Int

    @Query("SELECT cardUid FROM known_this_pass WHERE raccoltaUid = :raccoltaUid")
    suspend fun getAllKnownUids(raccoltaUid: String): List<String>
}

fun ReviewStateEntity.toCore(): ReviewState = ReviewState(
    stability = stability,
    difficulty = difficulty,
    due = due,
    reps = reps,
    lapses = lapses,
    lastReview = lastReview,
)

fun ReviewState.toEntity(cardUid: String): ReviewStateEntity = ReviewStateEntity(
    cardUid = cardUid,
    stability = stability,
    difficulty = difficulty,
    due = due,
    reps = reps,
    lapses = lapses,
    lastReview = lastReview,
)

@Dao
interface ReviewStateDao {
    @Upsert
    suspend fun upsert(state: ReviewStateEntity)

    @Insert
    suspend fun insertLog(log: ReviewLogEntity)

    @Query("SELECT * FROM review_state WHERE cardUid = :cardUid")
    suspend fun get(cardUid: String): ReviewStateEntity?

    @Query("SELECT * FROM review_state")
    suspend fun getAll(): List<ReviewStateEntity>
}

@Database(
    entities = [ReviewStateEntity::class, ReviewLogEntity::class, KnownThisPassEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class StateDb : RoomDatabase() {
    abstract fun reviewStateDao(): ReviewStateDao
    abstract fun knownPassDao(): KnownPassDao

    companion object {
        @Volatile
        private var instance: StateDb? = null

        fun getInstance(context: Context): StateDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StateDb::class.java,
                    "spacedcards-state.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}

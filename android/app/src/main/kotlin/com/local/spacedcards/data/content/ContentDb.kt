package com.local.spacedcards.data.content

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "card")
data class ContentCardEntity(
    @PrimaryKey val uid: String,
    val front: String,
    val back: String,
    val sourceDeck: String?,
)

@Entity(tableName = "raccolta")
data class RaccoltaEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sezione",
    indices = [Index("raccoltaUid")],
)
data class SezioneEntity(
    @PrimaryKey val uid: String,
    val raccoltaUid: String,
    val name: String,
    val srcName: String?,
    val importedAt: Long,
    val cardCount: Int,
)

@Entity(
    tableName = "card_sezione",
    primaryKeys = ["cardUid", "sezioneUid"],
    foreignKeys = [
        ForeignKey(
            entity = ContentCardEntity::class,
            parentColumns = ["uid"],
            childColumns = ["cardUid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SezioneEntity::class,
            parentColumns = ["uid"],
            childColumns = ["sezioneUid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cardUid"), Index("sezioneUid")],
)
data class CardSezioneEntity(
    val cardUid: String,
    val sezioneUid: String,
)

@Dao
interface ContentCardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<ContentCardEntity>): List<Long>

    @Query("SELECT * FROM card ORDER BY uid")
    suspend fun getAll(): List<ContentCardEntity>

    @Query("SELECT COUNT(*) FROM card")
    suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(DISTINCT cs.cardUid)
        FROM card_sezione cs
        INNER JOIN sezione s ON s.uid = cs.sezioneUid
        WHERE s.raccoltaUid = :raccoltaUid
        """,
    )
    suspend fun cardCountInRaccolta(raccoltaUid: String): Int

    @Query(
        """
        SELECT DISTINCT c.*
        FROM card c
        INNER JOIN card_sezione cs ON cs.cardUid = c.uid
        INNER JOIN sezione s ON s.uid = cs.sezioneUid
        WHERE s.raccoltaUid = :raccoltaUid
        ORDER BY c.uid
        """,
    )
    suspend fun cardsInRaccolta(raccoltaUid: String): List<ContentCardEntity>

    @Query(
        """
        SELECT DISTINCT c.uid
        FROM card c
        INNER JOIN card_sezione cs ON cs.cardUid = c.uid
        INNER JOIN sezione s ON s.uid = cs.sezioneUid
        WHERE s.raccoltaUid = :raccoltaUid
        ORDER BY c.uid
        """,
    )
    suspend fun cardUidsInRaccolta(raccoltaUid: String): List<String>
}

@Dao
interface RaccoltaDao {
    @Query("SELECT * FROM raccolta ORDER BY createdAt DESC, name COLLATE NOCASE ASC")
    suspend fun listRaccolte(): List<RaccoltaEntity>

    @Insert
    suspend fun createRaccolta(entity: RaccoltaEntity)

    @Insert
    suspend fun insertSezione(entity: SezioneEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardSezioni(links: List<CardSezioneEntity>)

    @Query("UPDATE sezione SET cardCount = :cardCount WHERE uid = :sezioneUid")
    suspend fun updateSezioneCardCount(sezioneUid: String, cardCount: Int)

    @Query(
        """
        SELECT * FROM sezione
        WHERE raccoltaUid = :raccoltaUid
        ORDER BY importedAt DESC, name COLLATE NOCASE ASC
        """,
    )
    suspend fun sezioniInRaccolta(raccoltaUid: String): List<SezioneEntity>

    @Query("DELETE FROM sezione WHERE uid = :sezioneUid")
    suspend fun deleteSezioneRow(sezioneUid: String)

    @Query("DELETE FROM card WHERE uid NOT IN (SELECT cardUid FROM card_sezione)")
    suspend fun deleteOrphanCards()

    @Transaction
    suspend fun deleteSezione(sezioneUid: String) {
        deleteSezioneRow(sezioneUid)
        deleteOrphanCards()
    }
}

@Database(
    entities = [
        ContentCardEntity::class,
        RaccoltaEntity::class,
        SezioneEntity::class,
        CardSezioneEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ContentDb : RoomDatabase() {
    abstract fun contentCardDao(): ContentCardDao
    abstract fun raccoltaDao(): RaccoltaDao

    companion object {
        @Volatile
        private var instance: ContentDb? = null

        fun getInstance(context: Context): ContentDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContentDb::class.java,
                    "spacedcards-content.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}

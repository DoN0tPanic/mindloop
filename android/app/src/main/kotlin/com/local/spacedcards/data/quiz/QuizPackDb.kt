package com.local.spacedcards.data.quiz

import android.content.Context
import androidx.room.Index
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import java.io.File

@Entity(
    tableName = "quiz_pack",
    indices = [Index("raccoltaUid")],
)
data class QuizPackEntity(
    @PrimaryKey val packUid: String,
    val name: String,
    val cardCount: Int,
    val sectionCount: Int,
    val sectionsJson: String,
    val bakedAt: String?,
    val importedAt: Long,
    val schemaVersion: Int,
    val bakerVersion: String,
    val llmModel: String?,
    val contentPath: String,
    val raccoltaUid: String?,
)

@Dao
interface QuizPackDao {
    @Upsert
    suspend fun upsert(entity: QuizPackEntity)

    @Query("SELECT * FROM quiz_pack ORDER BY importedAt DESC, name COLLATE NOCASE ASC")
    suspend fun listPacks(): List<QuizPackEntity>

    @Query("SELECT * FROM quiz_pack WHERE packUid = :packUid LIMIT 1")
    suspend fun getPack(packUid: String): QuizPackEntity?

    @Query("SELECT * FROM quiz_pack WHERE raccoltaUid = :raccoltaUid ORDER BY importedAt DESC, name COLLATE NOCASE ASC")
    suspend fun listPacksInRaccolta(raccoltaUid: String): List<QuizPackEntity>

    @Query("UPDATE quiz_pack SET raccoltaUid = :raccoltaUid WHERE packUid = :packUid")
    suspend fun updateRaccoltaUid(packUid: String, raccoltaUid: String?)

    @Query("DELETE FROM quiz_pack WHERE packUid = :packUid")
    suspend fun deleteByUid(packUid: String)

    @Query("DELETE FROM quiz_pack WHERE packUid IN (:packUids)")
    suspend fun deleteByUids(packUids: List<String>)
}

@Database(
    entities = [QuizPackEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class QuizPackDb : RoomDatabase() {
    abstract fun quizPackDao(): QuizPackDao

    companion object {
        @Volatile
        private var instance: QuizPackDb? = null

        fun getInstance(context: Context): QuizPackDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuizPackDb::class.java,
                    "spacedcards-quiz.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE quiz_pack ADD COLUMN raccoltaUid TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_quiz_pack_raccoltaUid ON quiz_pack(raccoltaUid)",
                )
            }
        }
    }
}

class QuizPackStore(
    private val context: Context,
    private val db: QuizPackDb = QuizPackDb.getInstance(context.applicationContext),
) {
    private val dao: QuizPackDao
        get() = db.quizPackDao()

    internal suspend fun upsertPack(imported: ImportedQuizPack): QuizPackEntity {
        val entity = imported.toEntity()
        val existing = dao.getPack(entity.packUid)
        db.withTransaction {
            dao.upsert(entity)
        }
        if (existing != null && existing.contentPath != entity.contentPath) {
            deletePackDirectory(existing.contentPath)
        }
        return entity
    }

    suspend fun listPacks(): List<QuizPackEntity> = dao.listPacks()

    suspend fun getPack(packUid: String): QuizPackEntity? = dao.getPack(packUid)

    suspend fun requirePack(packUid: String): QuizPackEntity =
        dao.getPack(packUid) ?: throw QzdImportException(QzdImportError.PackNotFound(packUid))

    suspend fun attachPackToRaccolta(packUid: String, raccoltaUid: String) {
        val pack = dao.getPack(packUid) ?: throw QzdImportException(QzdImportError.PackNotFound(packUid))
        val replaced = dao.listPacksInRaccolta(raccoltaUid).filter { it.packUid != pack.packUid }
        db.withTransaction {
            if (replaced.isNotEmpty()) {
                dao.deleteByUids(replaced.map { it.packUid })
            }
            dao.updateRaccoltaUid(packUid, raccoltaUid)
        }
        replaced.forEach { deletePackDirectory(it.contentPath) }
    }

    suspend fun deletePack(packUid: String) {
        val entity = dao.getPack(packUid) ?: return
        deletePackDirectory(entity.contentPath)
        db.withTransaction {
            dao.deleteByUid(packUid)
        }
    }

    private fun deletePackDirectory(contentPath: String) {
        val rootDir = File(context.filesDir, QUIZPACKS_DIR_NAME).canonicalFile
        val packDir = File(contentPath).parentFile?.canonicalFile ?: return
        if (!packDir.exists()) {
            return
        }

        val rootPrefix = rootDir.path + File.separator
        if (packDir.path == rootDir.path || !packDir.path.startsWith(rootPrefix)) {
            throw QzdImportException(
                QzdImportError.StorageFailure(
                    detail = "Rifiutata la cancellazione fuori da $QUIZPACKS_DIR_NAME: ${packDir.absolutePath}",
                ),
            )
        }

        if (!packDir.deleteRecursively() && packDir.exists()) {
            throw QzdImportException(
                QzdImportError.StorageFailure(
                    detail = "Impossibile cancellare il pacchetto quiz su disco: ${packDir.absolutePath}",
                ),
            )
        }
    }
}

internal fun QuizPackEntity.toSummary(): QuizPackSummary = QuizPackSummary(
    packUid = packUid,
    name = name,
    cardCount = cardCount,
    sectionCount = sectionCount,
    bakedAt = bakedAt,
    importedAt = importedAt,
    schemaVersion = schemaVersion,
    bakerVersion = bakerVersion,
    llmModel = llmModel,
    contentPath = contentPath,
    raccoltaUid = raccoltaUid,
)

private fun ImportedQuizPack.toEntity(): QuizPackEntity = QuizPackEntity(
    packUid = packUid,
    name = name,
    cardCount = cardCount,
    sectionCount = sectionCount,
    sectionsJson = sectionsJson,
    bakedAt = bakedAt,
    importedAt = importedAt,
    schemaVersion = schemaVersion,
    bakerVersion = bakerVersion,
    llmModel = llmModel,
    contentPath = contentPath,
    raccoltaUid = null,
)

internal const val QUIZPACKS_DIR_NAME = "quizpacks"

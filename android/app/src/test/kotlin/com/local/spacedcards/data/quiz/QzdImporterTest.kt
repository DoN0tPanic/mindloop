package com.local.spacedcards.data.quiz

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class QzdImporterTest {
    @Test
    fun invalidPackUidsGenerateSafeStorageNamesInsideRoot() {
        val rootDir = Files.createTempDirectory("quizpacks-root").toFile()
        val safeNameRegex = Regex("^[A-Za-z0-9_-]{1,64}$")
        val rootPrefix = rootDir.canonicalPath + File.separator
        val generatedUid = "generated_uid_123"
        try {
            listOf("..", ".", "", "uid.con.dot", "spazio vuoto", "uid/../evil", "strano!?", "a".repeat(65))
                .forEach { rawPackUid ->
                    val dirName = storageDirName(rawPackUid) { generatedUid }
                    assertTrue(safeNameRegex.matches(dirName), "$rawPackUid -> $dirName")
                    assertNotEquals(rawPackUid.trim(), dirName)
                    assertFalse(dirName.contains("."))

                    val resolved = resolvePackDirectory(rootDir, rawPackUid) { generatedUid }
                    assertEquals(dirName, resolved.name)
                    assertTrue(resolved.canonicalPath.startsWith(rootPrefix), resolved.canonicalPath)
                }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun resolvePackDirectoryRejectsEscapesEvenIfGeneratorIsHostile() {
        val rootDir = Files.createTempDirectory("quizpacks-root").toFile()
        try {
            val error = assertFailsWith<QzdImportException> {
                resolvePackDirectory(rootDir, "") { ".." }
            }
            assertIs<QzdImportError.StorageFailure>(error.error)
        } finally {
            rootDir.deleteRecursively()
        }
    }
}

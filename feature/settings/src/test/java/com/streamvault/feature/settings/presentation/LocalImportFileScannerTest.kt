package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImportFileScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File, name: String, modifiedMs: Long): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText("x")
            setLastModified(modifiedMs)
        }

    @Test
    fun `scans playlist extensions newest first and ignores other files and subfolders`() {
        val downloads = temp.newFolder("Download")
        val appFiles = temp.newFolder("files")
        file(downloads, "accounts.txt", 1_000_000L)
        file(downloads, "Lista.M3U", 3_000_000L)
        file(appFiles, "more.m3u8", 2_000_000L)
        file(appFiles, "backup.json", 4_000_000L)
        file(File(appFiles, "nested"), "deep.txt", 5_000_000L)

        val result = LocalImportFileScanner.scanDirectories(
            listOf(downloads, appFiles, File(temp.root, "missing")),
            LocalImportFileScanner.PLAYLIST_EXTENSIONS
        )

        assertThat(result.map { it.displayName }).containsExactly("Lista.M3U", "more.m3u8", "accounts.txt").inOrder()
        assertThat(result.first().uri).startsWith("file:")
    }

    @Test
    fun `same directory listed twice yields each file once`() {
        val documents = temp.newFolder("Documents")
        file(documents, "backup.json", 1_000_000L)

        val result = LocalImportFileScanner.scanDirectories(
            listOf(documents, File(documents, "../Documents")),
            LocalImportFileScanner.BACKUP_EXTENSIONS
        )

        assertThat(result).hasSize(1)
    }

    @Test
    fun `merge drops the same backup reached through MediaStore and the file system`() {
        val fromMediaStore = LocalImportFile("streamvault.json", "content://media/external/downloads/7", 1_700_000_000_123L)
        val fromFile = LocalImportFile("streamvault.json", "file:/storage/Download/streamvault.json", 1_700_000_000_900L)
        val other = LocalImportFile("older.json", "file:/storage/Download/older.json", 1_600_000_000_000L)

        val merged = LocalImportFileScanner.merge(listOf(fromMediaStore), listOf(fromFile, other))

        assertThat(merged).containsExactly(fromMediaStore, other).inOrder()
    }
}

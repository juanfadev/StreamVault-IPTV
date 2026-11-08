package com.streamvault.feature.settings.presentation

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/** A local file the user can import without the system document picker. */
internal data class LocalImportFile(
    val displayName: String,
    val uri: String,
    val lastModifiedMs: Long
)

/**
 * Picker-free file discovery for TVs that ship no DocumentsUI (or only the Android TV framework
 * stub that cancels `ACTION_OPEN_DOCUMENT` immediately). Looks in the public Downloads folder
 * (MediaStore entries visible to the app plus readable files) and the app's external files dirs.
 */
internal object LocalImportFileScanner {
    const val BACKUPS_SUBDIRECTORY = "Backups"
    val PLAYLIST_EXTENSIONS = setOf("txt", "m3u", "m3u8")
    val BACKUP_EXTENSIONS = setOf("json")

    fun scan(context: Context, extensions: Set<String>): List<LocalImportFile> =
        merge(scanMediaStoreDownloads(context, extensions), scanDirectories(defaultDirectories(context), extensions))

    /** Directories searched, in priority order; missing or unreadable ones are skipped. */
    fun defaultDirectories(context: Context): List<File> {
        val appRoot = context.getExternalFilesDir(null)
        val appDocuments = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        @Suppress("DEPRECATION")
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return listOfNotNull(
            publicDownloads,
            appRoot,
            appDocuments,
            appDocuments?.let { File(it, BACKUPS_SUBDIRECTORY) },
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        )
    }

    /** Human-readable folder the user can copy files into (the app's external files dir). */
    fun appImportDirectoryLabel(context: Context): String =
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath

    /** Lists matching files directly inside [directories] (non-recursive), newest first, without duplicates. */
    fun scanDirectories(directories: List<File>, extensions: Set<String>): List<LocalImportFile> {
        val seenPaths = HashSet<String>()
        return directories.flatMap { directory ->
            directory.takeIf { it.isDirectory && it.canRead() }?.listFiles().orEmpty()
                .filter { file -> file.isFile && file.canRead() && file.hasExtension(extensions) }
                .filter { file -> seenPaths.add(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)) }
                .map { file ->
                    LocalImportFile(
                        displayName = file.name,
                        uri = file.toURI().toString(),
                        lastModifiedMs = file.lastModified()
                    )
                }
        }.sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Combines lists, dropping entries that point to the same file through another route (same name
     * and modification second), keeping the first occurrence; result is newest first.
     */
    fun merge(vararg sources: List<LocalImportFile>): List<LocalImportFile> {
        val seen = HashSet<String>()
        return sources.flatMap { it }
            .filter { file -> seen.add(file.uri) && seen.add("${file.displayName}|${file.lastModifiedMs / 1000L}") }
            .sortedByDescending { it.lastModifiedMs }
    }

    private fun File.hasExtension(extensions: Set<String>): Boolean =
        extension.lowercase(Locale.ROOT) in extensions

    private fun scanMediaStoreDownloads(context: Context, extensions: Set<String>): List<LocalImportFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            buildList {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex).orEmpty()
                        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions) continue
                        add(
                            LocalImportFile(
                                displayName = name,
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(idIndex)
                                ).toString(),
                                lastModifiedMs = cursor.getLong(modifiedIndex) * 1000L
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}

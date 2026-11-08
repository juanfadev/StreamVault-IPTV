package com.streamvault.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.BulkXtreamImport
import com.streamvault.domain.usecase.BulkXtreamImportPhase
import com.streamvault.domain.usecase.BulkXtreamImportState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BulkXtreamImportUiState(
    val isOpen: Boolean = false,
    val input: String = "",
    val fileReadFailed: Boolean = false,
    val importState: BulkXtreamImportState? = null,
    /** In-app file list shown when the system document picker is unavailable; null when hidden. */
    val localFiles: List<LocalImportFile>? = null
) {
    val isRunning: Boolean get() = importState != null && importState.phase != BulkXtreamImportPhase.DONE
}

/** Drives Settings → Providers → Bulk import. Credentials stay in memory and are never logged. */
@HiltViewModel
internal class BulkXtreamImportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bulkXtreamImport: BulkXtreamImport,
    private val preferences: SettingsPreferences
) : ViewModel() {
    private companion object {
        const val MAX_FILE_CHARS = 1_000_000
        /** Highest value reachable with the stepper; beyond it only "No limit" remains. */
        const val MAX_STEPPED_ACTIVE_BACKUP_LIMIT = 99
    }

    private val _uiState = MutableStateFlow(BulkXtreamImportUiState())
    val uiState: StateFlow<BulkXtreamImportUiState> = _uiState.asStateFlow()
    private var importJob: Job? = null

    /** Most enabled backups after an import; [BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS] means no limit. */
    val activeBackupLimit: StateFlow<Int> = preferences.bulkImportActiveBackupLimit
        .stateIn(viewModelScope, SharingStarted.Eagerly, BulkXtreamImport.DEFAULT_ACTIVE_BACKUP_LIMIT)

    /** Steps the limit by [delta]; stepping down from "No limit" starts at the highest stepped value. */
    fun stepActiveBackupLimit(delta: Int) {
        val current = activeBackupLimit.value
        val next = if (current <= BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS) {
            if (delta >= 0) return
            MAX_STEPPED_ACTIVE_BACKUP_LIMIT
        } else {
            (current + delta).coerceIn(1, MAX_STEPPED_ACTIVE_BACKUP_LIMIT)
        }
        viewModelScope.launch { preferences.setBulkImportActiveBackupLimit(next) }
    }

    fun setUnlimitedActiveBackups() {
        viewModelScope.launch { preferences.setBulkImportActiveBackupLimit(BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS) }
    }

    fun open() {
        _uiState.update { state ->
            if (state.isRunning) state.copy(isOpen = true) else BulkXtreamImportUiState(isOpen = true, input = state.input)
        }
    }

    /** Closing keeps a running import going; reopening shows its progress. */
    fun dismiss() {
        _uiState.update { it.copy(isOpen = false) }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(input = text, fileReadFailed = false) }
    }

    fun loadFile(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(MAX_FILE_CHARS)
                        var total = 0
                        while (total < buffer.size) {
                            val read = reader.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        String(buffer, 0, total)
                    }
                }.getOrNull()
            }
            _uiState.update { state ->
                if (text == null) state.copy(fileReadFailed = true) else state.copy(input = text, fileReadFailed = false)
            }
        }
    }

    /** Picker-free fallback: lists .txt/.m3u/.m3u8 files from Downloads and the app's files dirs. */
    fun showLocalFiles() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                LocalImportFileScanner.scan(appContext, LocalImportFileScanner.PLAYLIST_EXTENSIONS)
            }
            _uiState.update { it.copy(localFiles = files) }
        }
    }

    fun hideLocalFiles() {
        _uiState.update { it.copy(localFiles = null) }
    }

    fun loadLocalFile(file: LocalImportFile) {
        hideLocalFiles()
        loadFile(Uri.parse(file.uri))
    }

    fun start(defaultProfileName: String) {
        val input = _uiState.value.input
        if (input.isBlank() || _uiState.value.isRunning) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val limit = preferences.bulkImportActiveBackupLimit.first()
            bulkXtreamImport.run(input, defaultProfileName, activeBackupLimit = limit).collect { state ->
                _uiState.update { it.copy(importState = state) }
            }
        }
    }
}

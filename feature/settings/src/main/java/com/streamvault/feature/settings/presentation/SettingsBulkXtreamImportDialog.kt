package com.streamvault.feature.settings.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.LocalContext
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.usecase.BulkXtreamImport
import com.streamvault.domain.usecase.BulkXtreamImportLineResult
import com.streamvault.domain.usecase.BulkXtreamImportOutcome
import com.streamvault.domain.usecase.BulkXtreamImportPhase
import com.streamvault.feature.settings.R

/** Settings → Providers → Bulk import: paste or pick Xtream URLs, probe slots, add backups. */
@Composable
internal fun BulkXtreamImportDialogHost(
    viewModel: BulkXtreamImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeBackupLimit by viewModel.activeBackupLimit.collectAsStateWithLifecycle()
    if (!uiState.isOpen) return
    val defaultProfileName = stringResource(R.string.settings_bulk_import_default_profile)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::loadFile)
    }
    val context = LocalContext.current
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val importState = uiState.importState
    val localFiles = uiState.localFiles
    if (localFiles != null) {
        LocalImportFileDialog(
            files = localFiles,
            emptyMessage = stringResource(
                R.string.settings_bulk_import_files_empty,
                LocalImportFileScanner.appImportDirectoryLabel(context)
            ),
            onSelect = viewModel::loadLocalFile,
            onDismiss = viewModel::hideLocalFiles
        )
        return
    }

    PremiumDialog(
        title = stringResource(R.string.settings_bulk_import),
        subtitle = stringResource(R.string.settings_bulk_import_description),
        onDismissRequest = viewModel::dismiss,
        widthFraction = 0.6f,
        content = {
            if (importState == null) {
                EpgSourceTextField(
                    value = uiState.input,
                    onValueChange = viewModel::updateInput,
                    placeholder = stringResource(R.string.settings_bulk_import_hint),
                    singleLine = false,
                    maxLines = 8
                )
                if (uiState.fileReadFailed) {
                    Text(
                        text = stringResource(R.string.settings_bulk_import_file_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorColor
                    )
                }
                ActiveBackupLimitControl(
                    limit = activeBackupLimit,
                    onStep = viewModel::stepActiveBackupLimit,
                    onUnlimited = viewModel::setUnlimitedActiveBackups
                )
            } else {
                val total = importState.lines.size
                val processed = importState.lines.count { it.outcome != null }
                Text(
                    text = when (importState.phase) {
                        BulkXtreamImportPhase.CHECKING ->
                            stringResource(R.string.settings_bulk_import_checking, processed, total)
                        BulkXtreamImportPhase.ADDING -> stringResource(R.string.settings_bulk_import_adding)
                        BulkXtreamImportPhase.DONE -> stringResource(
                            R.string.settings_bulk_import_done,
                            importState.profileName ?: defaultProfileName
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                if (importState.phase != BulkXtreamImportPhase.DONE) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else processed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary
                    )
                }
                val counts = importState.lines.mapNotNull { it.outcome }.groupingBy { it }.eachCount()
                if (counts.isNotEmpty()) {
                    Text(
                        text = BulkXtreamImportOutcome.entries
                            .filter { counts.containsKey(it) }
                            .map { outcome ->
                                stringResource(R.string.settings_bulk_import_count, outcomeLabel(outcome), counts.getValue(outcome))
                            }
                            .joinToString(separator = " · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(importState.lines, key = { it.lineNumber }) { line ->
                        BulkImportLineRow(line)
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.player_close),
                onClick = viewModel::dismiss
            )
            if (importState == null) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_bulk_import_choose_file),
                    onClick = {
                        // TVs often ship no DocumentsUI, or only a stub that cancels immediately.
                        if (isTelevisionDevice) {
                            viewModel.showLocalFiles()
                        } else {
                            try {
                                filePicker.launch(
                                    arrayOf("text/*", "audio/x-mpegurl", "application/x-mpegurl", "application/octet-stream")
                                )
                            } catch (_: ActivityNotFoundException) {
                                viewModel.showLocalFiles()
                            }
                        }
                    }
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_bulk_import_start),
                    onClick = { viewModel.start(defaultProfileName) },
                    enabled = uiState.input.isNotBlank(),
                    emphasized = true
                )
            }
        }
    )

/** "Max active backups" stepper with a "No limit" option; extra free accounts become reserves. */
@Composable
private fun ActiveBackupLimitControl(
    limit: Int,
    onStep: (Int) -> Unit,
    onUnlimited: () -> Unit
) {
    val unlimited = limit <= BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS
    val unlimitedLabel = stringResource(R.string.settings_bulk_import_active_limit_unlimited)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_bulk_import_active_limit),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_bulk_import_active_limit_decrease),
                onClick = { onStep(-1) },
                enabled = unlimited || limit > 1
            )
            Text(
                text = if (unlimited) unlimitedLabel else limit.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Primary
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_bulk_import_active_limit_increase),
                onClick = { onStep(1) },
                enabled = !unlimited
            )
            PremiumDialogFooterButton(
                label = unlimitedLabel,
                onClick = onUnlimited,
                enabled = !unlimited
            )
        }
        Text(
            text = stringResource(R.string.settings_bulk_import_active_limit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}
        BulkXtreamImportOutcome.ADDED_RESERVE -> R.string.settings_bulk_import_outcome_added_reserve
        BulkXtreamImportOutcome.UPDATED -> R.string.settings_bulk_import_outcome_updated
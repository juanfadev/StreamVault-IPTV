package com.streamvault.domain.settings

import com.streamvault.domain.model.AppTextSize
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.PlayerBackButtonVisibility
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.TimeshiftBackendPreference
import kotlinx.coroutines.flow.Flow

/** Preference streams and commands consumed by settings; persistence remains in the data layer. */
interface SettingsPreferences {
    val hideStremioAddons: Flow<Boolean>


    /**
     * Bulk import: most combined-source members kept enabled; extra free accounts become disabled
     * Bulk import: maximum enabled members of the combined backup source; extra free accounts become disabled
     */
    val bulkImportActiveBackupLimit: Flow<Int>

    suspend fun setBulkImportActiveBackupLimit(limit: Int)
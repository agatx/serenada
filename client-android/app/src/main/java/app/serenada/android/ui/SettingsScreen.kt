package app.serenada.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.serenada.android.BuildConfig
import app.serenada.android.R
import app.serenada.android.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    host: String,
    selectedLanguage: String,
    isDefaultCameraEnabled: Boolean,
    isDefaultMicrophoneEnabled: Boolean,
    isHdVideoExperimentalEnabled: Boolean,
    areSavedRoomsShownFirst: Boolean,
    areRoomInviteNotificationsEnabled: Boolean,
    hostError: String?,
    isSaving: Boolean,
    onHostChange: (String) -> Unit,
    onLanguageSelect: (String) -> Unit,
    onDefaultCameraChange: (Boolean) -> Unit,
    onDefaultMicrophoneChange: (Boolean) -> Unit,
    onHdVideoExperimentalChange: (Boolean) -> Unit,
    onSavedRoomsShownFirstChange: (Boolean) -> Unit,
    onRoomInviteNotificationsChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val languageOptions = listOf(
        SettingsStore.LANGUAGE_AUTO to stringResource(R.string.settings_language_auto),
        SettingsStore.LANGUAGE_EN to stringResource(R.string.settings_language_english),
        SettingsStore.LANGUAGE_RU to stringResource(R.string.settings_language_russian),
        SettingsStore.LANGUAGE_ES to stringResource(R.string.settings_language_spanish),
        SettingsStore.LANGUAGE_FR to stringResource(R.string.settings_language_french)
    )

    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == selectedLanguage }?.second
        ?: languageOptions.first().second
    var languageMenuExpanded by remember { mutableStateOf(false) }

    val isDefaultHost = host == SettingsStore.DEFAULT_HOST
    val isRuHost = host == SettingsStore.HOST_RU
    val isCustomHost = !isDefaultHost && !isRuHost

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    var pingFailed by remember { mutableStateOf(false) }
    val appVersion = BuildConfig.VERSION_NAME.ifBlank { "-" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = !isSaving) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsSection(
                    title = stringResource(R.string.settings_server_host)
                ) {
                    HostOptionRow(
                        selected = isDefaultHost,
                        label = stringResource(R.string.settings_host_global, SettingsStore.DEFAULT_HOST),
                        onClick = { onHostChange(SettingsStore.DEFAULT_HOST) }
                    )

                    HostOptionRow(
                        selected = isRuHost,
                        label = stringResource(R.string.settings_host_russia, SettingsStore.HOST_RU),
                        onClick = { onHostChange(SettingsStore.HOST_RU) }
                    )

                    HostOptionRow(
                        selected = isCustomHost,
                        label = stringResource(R.string.custom),
                        onClick = { }
                    )

                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text(stringResource(R.string.settings_server_host)) },
                        isError = hostError != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isPinging = true
                                pingResult = null
                                pingFailed = false
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        try {
                                            val targetUrl = if (host.startsWith("http")) host else "https://$host"
                                            val start = System.currentTimeMillis()
                                            val connection = URL(targetUrl).openConnection() as HttpURLConnection
                                            connection.connectTimeout = 3000
                                            connection.readTimeout = 3000
                                            connection.requestMethod = "HEAD"
                                            connection.connect()
                                            connection.responseCode
                                            val end = System.currentTimeMillis()
                                            connection.disconnect()
                                            "${end - start}"
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            null
                                        }
                                    }
                                    pingResult = result
                                    pingFailed = result == null
                                    isPinging = false
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            enabled = !isPinging
                        ) {
                            if (isPinging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(stringResource(R.string.settings_ping))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                onOpenDiagnostics()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Text(stringResource(R.string.settings_device_check))
                        }
                    }

                    if (pingResult != null || pingFailed) {
                        Text(
                            text = if (pingFailed) {
                                stringResource(R.string.settings_ping_error)
                            } else {
                                stringResource(R.string.settings_latency_result, pingResult ?: "")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pingFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!hostError.isNullOrBlank()) {
                        Text(
                            text = hostError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    TextButton(
                        onClick = { uriHandler.openUri("https://github.com/agatx/serenada") },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.create_custom_server))
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_language),
                    subTitle = stringResource(R.string.settings_language_help)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = languageMenuExpanded,
                        onExpandedChange = { languageMenuExpanded = !languageMenuExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedLanguageLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false }
                        ) {
                            languageOptions.forEach { (languageCode, languageLabel) ->
                                DropdownMenuItem(
                                    text = { Text(languageLabel) },
                                    onClick = {
                                        onLanguageSelect(languageCode)
                                        languageMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_call_defaults)
                ) {
                    SettingsSwitchRow(
                        label = stringResource(R.string.camera_enabled),
                        subLabel = stringResource(R.string.camera_enabled_info),
                        checked = isDefaultCameraEnabled,
                        onCheckedChange = onDefaultCameraChange
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingsSwitchRow(
                        label = stringResource(R.string.microphone_enabled),
                        subLabel = stringResource(R.string.microphone_enabled_info),
                        checked = isDefaultMicrophoneEnabled,
                        onCheckedChange = onDefaultMicrophoneChange
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingsSwitchRow(
                        label = stringResource(R.string.settings_hd_video_experimental),
                        subLabel = stringResource(R.string.settings_hd_video_experimental_info),
                        checked = isHdVideoExperimentalEnabled,
                        onCheckedChange = onHdVideoExperimentalChange
                    )
                }

                SettingsSection(
                    title = stringResource(R.string.settings_saved_rooms_title),
                    subTitle = stringResource(R.string.settings_saved_rooms_help)
                ) {
                    SettingsSwitchRow(
                        label = stringResource(R.string.settings_saved_rooms_show_first),
                        subLabel = stringResource(R.string.settings_saved_rooms_show_first_info),
                        checked = areSavedRoomsShownFirst,
                        onCheckedChange = onSavedRoomsShownFirstChange
                    )
                }

                SettingsSection(
                    title = stringResource(R.string.settings_invites_title)
                ) {
                    SettingsSwitchRow(
                        label = stringResource(R.string.settings_invite_notifications),
                        subLabel = stringResource(R.string.settings_invite_notifications_info),
                        checked = areRoomInviteNotificationsEnabled,
                        onCheckedChange = onRoomInviteNotificationsChange
                    )
                }

                Text(
                    text = stringResource(R.string.settings_app_version, appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subTitle.isNullOrBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    subLabel: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                }
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun HostOptionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val rowShape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp)
        )
    }
}

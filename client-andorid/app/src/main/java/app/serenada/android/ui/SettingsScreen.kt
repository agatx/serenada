package app.serenada.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.serenada.android.R
import app.serenada.android.data.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    host: String,
    selectedLanguage: String,
    onHostChange: (String) -> Unit,
    onLanguageSelect: (String) -> Unit,
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
                    TextButton(onClick = onSave) {
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
                .padding(24.dp)
        ) {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text(stringResource(R.string.settings_server_host)) },
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = languageMenuExpanded,
                    onExpandedChange = { languageMenuExpanded = !languageMenuExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    OutlinedTextField(
                        value = selectedLanguageLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_language)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
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
        }
    }
}

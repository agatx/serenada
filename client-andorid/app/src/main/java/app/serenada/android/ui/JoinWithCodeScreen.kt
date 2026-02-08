package app.serenada.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.serenada.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinWithCodeScreen(
        roomInput: String,
        isBusy: Boolean,
        statusMessage: String,
        errorMessage: String?,
        onRoomInputChange: (String) -> Unit,
        onJoinCall: () -> Unit,
        onBack: () -> Unit
) {
    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text(stringResource(R.string.join_with_code_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.common_back)
                                )
                            }
                        },
                        actions = {
                            TextButton(
                                    onClick = onJoinCall,
                                    enabled = !isBusy && roomInput.isNotBlank()
                            ) { Text(stringResource(R.string.join_with_code_action)) }
                        }
                )
            }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                        text = stringResource(R.string.join_with_code_hint),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
                OutlinedTextField(
                        value = roomInput,
                        onValueChange = onRoomInputChange,
                        placeholder = { Text(stringResource(R.string.join_with_code_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isBusy
                )

                if (isBusy) {
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator()
                    if (statusMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(statusMessage)
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

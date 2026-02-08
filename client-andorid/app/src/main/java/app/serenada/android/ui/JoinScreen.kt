package app.serenada.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.serenada.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    isBusy: Boolean,
    statusMessage: String,
    onOpenJoinWithCode: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartCall: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenJoinWithCode() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = stringResource(R.string.join_enter_code_or_link),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, enabled = !isBusy) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.join_settings)
                        )
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.app_name), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.join_subtitle))
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onStartCall,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoCall,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.join_start_call), fontSize = 18.sp)
                }

                if (isBusy) {
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    if (statusMessage.isNotBlank()) {
                        Text(statusMessage)
                    }
                }
            }
        }
    }
}

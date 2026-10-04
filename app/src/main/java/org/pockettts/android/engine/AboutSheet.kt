package org.pockettts.android.engine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutSheet(dismiss: () -> Unit) {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showNotices) {
        if (showNotices && notices == null) notices = withContext(Dispatchers.IO) {
            context.assets.open("deepfilter/NOTICES.txt").bufferedReader().use { it.readText() }
        }
    }
    GlassSheet(stringResource(R.string.about_title), dismiss) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.about_intro), modifier = Modifier.padding(top = 12.dp))
        }
        item {
            GlassCard(Modifier.fillMaxWidth().testTag("about-reader")) {
                SectionTitle(stringResource(R.string.about_reader_title))
                Text(stringResource(R.string.about_reader_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.about_voices_title))
                Text(stringResource(R.string.about_voices_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionTitle(stringResource(R.string.about_privacy_title))
            Text(stringResource(R.string.about_privacy_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionTitle(stringResource(R.string.about_credits_title))
            Text(stringResource(R.string.about_credits_body), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { showNotices = !showNotices }, modifier = Modifier.testTag("about-notices")) {
                Text(stringResource(R.string.about_notices))
            }
        }
        if (showNotices) item {
            if (notices == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else SelectionContainer { Text(notices!!, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

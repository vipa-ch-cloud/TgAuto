package com.v1pach.telescript.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.AppSectionCard
import com.v1pach.telescript.ui.Hint

fun moduleIcon(key: String): ImageVector = when (key) {
    "shield" -> Icons.Default.Shield
    "chat_reply" -> Icons.AutoMirrored.Filled.Reply
    "bot" -> Icons.Default.SmartToy
    "tool" -> Icons.Default.Build
    "star" -> Icons.Default.Star
    "bell" -> Icons.Default.Notifications
    "lock" -> Icons.Default.Lock
    else -> Icons.Default.Code
}

fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Store card (classic 1.9.0 look): a plain section card with title + version, description, status pills
 * and a small info line (author · date · repository link).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreCard(
    title: String, description: String, meta: ModuleMeta, version: String, author: String,
    modifier: Modifier = Modifier, badges: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    AppSectionCard(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$title  v$version", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (description.isNotBlank()) Hint(description)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { badges() }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val parts = listOfNotNull(author.takeIf { it.isNotBlank() }, meta.published)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            meta.repo?.let { r ->
                Text((if (parts.isNotEmpty()) "· " else "") + stringResource(R.string.store_repo_link), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openLink(context, r) })
            }
        }
    }
}

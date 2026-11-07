package com.v1pach.telescript.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1pach.telescript.R

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
 * Store card: a large rounded plate in the module's colour (theme colour by default), a rounded icon
 * tile sticking out above its top-left corner, title and description to the right, a micro-info line
 * (version · author · date · repository link) at the bottom. Text colour follows the plate's brightness.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreCard(
    title: String, description: String, meta: ModuleMeta, version: String, author: String,
    modifier: Modifier = Modifier, badges: @Composable (Color) -> Unit = {},
) {
    val context = LocalContext.current
    val custom = StoreMeta.rgb(meta.color)
    val bg = custom?.let { Color(0xFF000000.toInt() or it) } ?: MaterialTheme.colorScheme.primaryContainer
    val fg = when {
        custom == null -> MaterialTheme.colorScheme.onPrimaryContainer
        StoreMeta.darkTextOn(custom) -> Color(0xFF14141A)
        else -> Color.White
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 22.dp)) {
        val narrow = maxWidth < 340.dp
        val tile = if (narrow) 56.dp else 76.dp
        val lift = if (narrow) 16.dp else 22.dp
        val inset = if (narrow) 12.dp else 16.dp
        Surface(shape = RoundedCornerShape(if (narrow) 22.dp else 28.dp), color = bg, contentColor = fg, shadowElevation = 4.dp,
            border = BorderStroke(1.dp, fg.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = inset, end = inset, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.heightIn(min = tile - lift + 6.dp)) {
                    Spacer(Modifier.width(tile + 12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.9f),
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { badges(fg) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val parts = listOfNotNull("v$version", author.takeIf { it.isNotBlank() }, meta.published)
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.85f))
                    meta.repo?.let { r ->
                        Text("· " + stringResource(R.string.store_repo_link), style = MaterialTheme.typography.labelMedium, color = fg,
                            textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { openLink(context, r) })
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(if (narrow) 16.dp else 22.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shadowElevation = 6.dp,
            modifier = Modifier.padding(start = inset).offset(y = -lift).size(tile)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(moduleIcon(meta.icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(tile * 0.55f))
            }
        }
    }
}

/** Small status chip readable on any plate colour: icon + text in the plate's text colour. */
@Composable
fun CardBadge(text: String, icon: ImageVector, fg: Color) {
    Surface(shape = RoundedCornerShape(50), color = fg.copy(alpha = 0.16f), border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
        }
    }
}

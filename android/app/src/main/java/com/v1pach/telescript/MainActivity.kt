package com.v1pach.telescript

import androidx.compose.ui.res.stringResource
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.ui.AccountTab
import com.v1pach.telescript.ui.LogsTab
import com.v1pach.telescript.ui.ScriptsTab
import com.v1pach.telescript.ui.SettingsTab
import com.v1pach.telescript.ui.TeleScriptTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val startupPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Claude account mode needs to run Claude Code in Termux.
            if (FlavorHooks.cliOrder(AppSettings.llmOrder.value) != "api" && LlmBridge.termuxInstalled() && !LlmBridge.hasPermission())
                add(LlmBridge.PERMISSION)
        }
        if (wanted.isNotEmpty()) startupPermissions.launch(wanted.toTypedArray())
        setContent {
            val themeMode by AppSettings.themeMode.flow.collectAsStateWithLifecycle()
            val dynamicColor by AppSettings.dynamicColor.flow.collectAsStateWithLifecycle()
            val palette by AppSettings.palette.flow.collectAsStateWithLifecycle()
            TeleScriptTheme(themeMode = themeMode, dynamicColor = dynamicColor, themePalette = palette) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppBackdrop(Modifier.matchParentSize())
                        Surface(Modifier.fillMaxSize(), color = Color.Transparent) { MainContent() }
                    }
                }
            }
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
private fun MainContent() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val navItems = listOf(
        NavItem(stringResource(R.string.nav_scripts), Icons.Default.Code),
        NavItem(stringResource(R.string.nav_account), Icons.Default.AccountCircle),
        NavItem(stringResource(R.string.nav_logs), Icons.Default.Terminal),
        NavItem(stringResource(R.string.nav_settings), Icons.Default.Settings),
    )
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 96.dp

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        containerColor = Color.Transparent,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .pointerInput(selectedTab) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f; dragTargetIndex = -1; dragProgress = 0f },
                        onDragCancel = { dragTargetIndex = -1; dragProgress = 0f },
                        onDragEnd = {
                            if (dragTargetIndex in navItems.indices && dragProgress >= 0.5f) selectedTab = dragTargetIndex
                            dragTargetIndex = -1
                            dragProgress = 0f
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        if (abs(totalDrag) < 12f) {
                            dragTargetIndex = -1; dragProgress = 0f
                            return@detectHorizontalDragGestures
                        }
                        val candidate = if (totalDrag < 0f) selectedTab + 1 else selectedTab - 1
                        if (candidate !in navItems.indices) {
                            dragTargetIndex = -1; dragProgress = 0f
                            return@detectHorizontalDragGestures
                        }
                        dragTargetIndex = candidate
                        dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                    }
                }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(225)) },
                modifier = Modifier.fillMaxSize().padding(bottom = navOverlayReserve),
                label = "tab_content"
            ) { page ->
                when (page) {
                    0 -> ScriptsTab(onOpenAccount = { selectedTab = 1 }, onOpenLogs = { selectedTab = 2 })
                    1 -> AccountTab()
                    2 -> LogsTab()
                    3 -> SettingsTab()
                }
            }
            PillNavigationBar(
                navItems = navItems,
                selectedTab = selectedTab,
                dragTargetIndex = dragTargetIndex,
                dragProgress = dragProgress,
                onTabSelected = { selectedTab = it; dragTargetIndex = -1; dragProgress = 0f },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun PillNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val shellColor = if (isDark) colors.surface.copy(alpha = 0.78f)
    else lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    val shellBorder = if (isDark) colors.outlineVariant.copy(alpha = 0.42f) else colors.outline.copy(alpha = 0.16f)
    val indicatorColor = if (isDark) colors.primaryContainer.copy(alpha = 0.84f)
    else lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.97f)
    val indicatorIndex = remember { Animatable(selectedTab.toFloat()) }
    val visualIndex = indicatorIndex.value

    LaunchedEffect(selectedTab) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(
                selectedTab.toFloat(),
                tween(durationMillis = 720, easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f))
            )
        }
    }
    LaunchedEffect(selectedTab, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            indicatorIndex.snapTo(selectedTab.toFloat() + (dragTargetIndex - selectedTab) * dragProgress)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        val trackPadding = 8.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * visualIndex
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            shadowElevation = if (isDark) 10.dp else 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth().height(72.dp)) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = indicatorColor,
                    modifier = Modifier.offset(x = indicatorOffset).padding(vertical = 6.dp).width(itemWidth).fillMaxHeight()
                ) {}
                Row(Modifier.fillMaxSize().padding(horizontal = trackPadding, vertical = 6.dp)) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - visualIndex)).coerceIn(0f, 1f)
                        val tint = lerp(unselectedColor, selectedColor, emphasis)
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onTabSelected(index) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp), tint = tint)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun orbShape(points: Int, innerRatio: Float): Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = min(size.width, size.height) / 2f
    val inner = outer * innerRatio
    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val r = if (i % 2 == 0) outer else inner
        val x = cx + (r * cos(angle)).toFloat()
        val y = cy + (r * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val OrbLarge: Shape = orbShape(18, 0.90f)
private val OrbMedium: Shape = orbShape(20, 0.92f)
private val OrbSmall: Shape = orbShape(16, 0.88f)

@Composable
private fun AppBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant) {
        Brush.verticalGradient(
            if (isDark) listOf(lerp(colors.background, colors.surface, 0.42f), colors.background,
                lerp(colors.surfaceVariant, colors.background, 0.35f))
            else listOf(lerp(colors.background, colors.surface, 0.78f), colors.background,
                lerp(colors.surfaceVariant, colors.background, 0.30f))
        )
    }
    val topGlow = if (isDark) colors.primary.copy(alpha = 0.16f)
    else lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.32f)
    val leftGlow = if (isDark) colors.tertiary.copy(alpha = 0.11f)
    else lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.24f)
    val bottomGlow = if (isDark) colors.primary.copy(alpha = 0.10f)
    else lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.22f)
    val outline = colors.outlineVariant.copy(alpha = 0.26f)

    Box(modifier.fillMaxSize().background(baseBrush)) {
        Box(
            Modifier.align(Alignment.TopStart).offset(x = (-86).dp, y = (-126).dp).size(258.dp)
                .clip(OrbLarge).background(topGlow)
                .then(if (isDark) Modifier else Modifier.border(1.dp, outline, OrbLarge))
        )
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = (-44).dp, y = 28.dp).size(146.dp)
                .clip(OrbSmall).background(leftGlow)
                .then(if (isDark) Modifier else Modifier.border(1.dp, outline.copy(alpha = 0.22f), OrbSmall))
        )
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 62.dp, y = (-208).dp).size(198.dp)
                .clip(OrbMedium).background(bottomGlow)
                .then(if (isDark) Modifier else Modifier.border(1.dp, outline.copy(alpha = 0.20f), OrbMedium))
        )
    }
}

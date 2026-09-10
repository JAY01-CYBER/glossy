/**
 * Glossy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.ui.screens.settings

import com.jay.glossy.R

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.jay.glossy.LocalDatabase
import com.jay.glossy.LocalPlayerAwareWindowInsets
import com.jay.glossy.LocalPlayerConnection
import com.jay.glossy.constants.EnableSongCacheKey
import com.jay.glossy.constants.MaxImageCacheSizeKey
import com.jay.glossy.constants.MaxSongCacheSizeKey
import com.jay.glossy.constants.EnableCanvasKey
import com.jay.glossy.constants.MaxCanvasCacheSizeKey
import com.jay.glossy.extensions.tryOrNull
import com.jay.glossy.ui.component.ActionPromptDialog
import com.jay.glossy.ui.component.IconButton
import com.jay.glossy.ui.component.Material3SettingsGroup
import com.jay.glossy.ui.component.Material3SettingsItem
import android.text.format.Formatter
import com.jay.glossy.playback.CanvasPlayerCache
import com.jay.glossy.ui.utils.backToMain
import com.jay.glossy.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
    navController: NavController
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val coroutineScope = rememberCoroutineScope()
    val songCacheString = stringResource(R.string.song_cache).lowercase()
    val imageCacheString = stringResource(R.string.image_cache).lowercase()
    val canvasCacheString = "canvas cache"

    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = 1024
    )
    val (enableSongCache, onEnableSongCacheChange) = rememberPreference(
        key = EnableSongCacheKey,
        defaultValue = true
    )
    
    // 🚀 Canvas Preferences
    val (enableCanvas, onEnableCanvasChange) = rememberPreference(
        key = EnableCanvasKey, 
        defaultValue = true
    )
    val (maxCanvasCacheSize, onMaxCanvasCacheSizeChange) = rememberPreference(
        key = MaxCanvasCacheSizeKey, 
        defaultValue = 256
    )

    var clearDownloads by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }
    var clearCanvasCacheDialog by remember { mutableStateOf(false) }

    // State for the confirmation dialog
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheType by remember { mutableStateOf("") }
    var cacheUsage by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    var imageCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    var canvasCacheSize by remember { mutableLongStateOf(0L) }

    val imageCacheProgress by animateFloatAsState(
        targetValue = (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(0f, 1f),
        label = "imageCacheProgress",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue = (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(0f, 1f),
        label = "playerCacheProgress",
    )
    val canvasCacheProgress by animateFloatAsState(
        targetValue = if (maxCanvasCacheSize > 0) (canvasCacheSize.toFloat() / (maxCanvasCacheSize * 1024 * 1024L)).coerceIn(0f, 1f) else 0f,
        label = "canvasCacheProgress"
    )

    LaunchedEffect(maxImageCacheSize) {
        SingletonImageLoader.reset()
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) { imageDiskCache.clear() }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key -> playerCache.removeResource(key) }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
            
            val dir = File(context.cacheDir, "canvas_video_cache")
            canvasCacheSize = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() else 0L
        }
    }

    if (clearDownloads) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_all_downloads),
            onDismiss = { clearDownloads = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    downloadCache.keys.forEach { key -> downloadCache.removeResource(key) }
                }
                clearDownloads = false
            },
            onCancel = { clearDownloads = false },
            content = { Text(text = stringResource(R.string.clear_downloads_dialog)) },
        )
    }
    if (clearCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_song_cache),
            onDismiss = { clearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key -> playerCache.removeResource(key) }
                }
                clearCacheDialog = false
            },
            onCancel = { clearCacheDialog = false },
            content = { Text(text = stringResource(R.string.clear_song_cache_dialog)) },
        )
    }
    if (clearImageCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_image_cache),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    val urlsToPreserve = mutableSetOf<String>()
                    val downloadedSongs = try { database.downloadedSongsByNameAsc().first() } catch (e: Exception) { emptyList() }
                    downloadedSongs.forEach { song ->
                        song.song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                        song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                    }
                    val directory = imageDiskCache.directory.toFile()
                    if (directory.exists() && directory.isDirectory) {
                        directory.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith("journal")) {
                                val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                                if (!isPreserved) file.delete()
                            }
                        }
                    }
                    imageDiskCache.clear()
                }
                clearImageCacheDialog = false
            },
            onCancel = { clearImageCacheDialog = false },
            content = { Text(text = stringResource(R.string.clear_image_cache_dialog)) },
        )
    }
    if (clearCanvasCacheDialog) {
        ActionPromptDialog(
            title = "Clear Canvas Cache",
            onDismiss = { clearCanvasCacheDialog = false },
            onConfirm = {
                CanvasPlayerCache.clearCache(context)
                clearCanvasCacheDialog = false
            },
            onCancel = { clearCanvasCacheDialog = false },
            content = { Text(text = "Are you sure you want to clear the canvas video cache? This will delete all downloaded canvas loops.") },
        )
    }

    if (showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCacheWarningDialog = false },
            title = { Text(stringResource(R.string.cache_size_warning_title)) },
            text = {
                Text(stringResource(R.string.cache_size_warning_message, Formatter.formatShortFileSize(context, cacheUsage), cacheType))
            },
            confirmButton = {
                TextButton(onClick = { onConfirmAction(); showCacheWarningDialog = false }) {
                    Text(stringResource(R.string.cache_size_warning_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheWarningDialog = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        
        Material3SettingsGroup(
            title = stringResource(R.string.storage),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text(stringResource(R.string.downloaded_songs)) },
                    description = { Text(text = Formatter.formatShortFileSize(context, downloadCacheSize)) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_all_downloads)) },
                    onClick = { clearDownloads = true },
                ),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.song_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.enable_song_cache)) },
                    description = { Text(stringResource(R.string.enable_song_cache_desc)) },
                    trailingContent = {
                        Switch(
                            checked = enableSongCache,
                            onCheckedChange = onEnableSongCacheChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(id = if (enableSongCache) R.drawable.check else R.drawable.close),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onEnableSongCacheChange(!enableSongCache) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.max_song_cache_size)) },
                    enabled = enableSongCache,
                    description = {
                        val songCacheValues = remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1) }
                        Column {
                            Text(
                                text = when (maxSongCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    -1 -> stringResource(R.string.unlimited)
                                    else -> Formatter.formatShortFileSize(context, maxSongCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = songCacheValues.indexOf(maxSongCacheSize).toFloat(),
                                enabled = enableSongCache,
                                onValueChange = {
                                    val newValue = songCacheValues[it.roundToInt()]
                                    val newLimitInBytes = if (newValue == -1) Long.MAX_VALUE else newValue * 1024 * 1024L

                                    if (newLimitInBytes < playerCacheSize) {
                                        cacheUsage = playerCacheSize
                                        cacheType = songCacheString
                                        onConfirmAction = { onMaxSongCacheSizeChange(newValue) }
                                        showCacheWarningDialog = true
                                    } else {
                                        onMaxSongCacheSizeChange(newValue)
                                    }
                                },
                                steps = songCacheValues.size - 2,
                                valueRange = 0f..(songCacheValues.size - 1).toFloat(),
                            )
                            LinearProgressIndicator(
                                progress = { playerCacheProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round,
                            )
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text(
                                text = if (maxSongCacheSize == -1) Formatter.formatShortFileSize(context, playerCacheSize) 
                                       else "${Formatter.formatShortFileSize(context, playerCacheSize)} / ${Formatter.formatShortFileSize(context, maxSongCacheSize * 1024 * 1024L)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_song_cache)) },
                    onClick = { clearCacheDialog = true },
                ),
            ),
        )

        // 🚀 NEW: Canvas Cache Settings 🚀
        Material3SettingsGroup(
            title = "Canvas Cache",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text("Enable Canvas") },
                    description = { Text("Automatically download and play background canvas videos") },
                    trailingContent = {
                        Switch(
                            checked = enableCanvas,
                            onCheckedChange = onEnableCanvasChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(id = if (enableCanvas) R.drawable.check else R.drawable.close),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onEnableCanvasChange(!enableCanvas) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text("Max Canvas cache size") },
                    enabled = enableCanvas,
                    description = {
                        val canvasCacheValues = remember { listOf(0, 128, 256, 512, 1024, 2048, 4096) }
                        Column {
                            Text(
                                text = when (maxCanvasCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    else -> Formatter.formatShortFileSize(context, maxCanvasCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = canvasCacheValues.indexOf(maxCanvasCacheSize).toFloat(),
                                enabled = enableCanvas,
                                onValueChange = {
                                    val newValue = canvasCacheValues[it.roundToInt()]
                                    val newLimitInBytes = newValue * 1024 * 1024L

                                    if (newLimitInBytes < canvasCacheSize) {
                                        cacheUsage = canvasCacheSize
                                        cacheType = canvasCacheString
                                        onConfirmAction = { onMaxCanvasCacheSizeChange(newValue) }
                                        showCacheWarningDialog = true
                                    } else {
                                        onMaxCanvasCacheSizeChange(newValue)
                                    }
                                },
                                steps = canvasCacheValues.size - 2,
                                valueRange = 0f..(canvasCacheValues.size - 1).toFloat(),
                            )
                            LinearProgressIndicator(
                                progress = { canvasCacheProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round,
                            )
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text(
                                text = "${Formatter.formatShortFileSize(context, canvasCacheSize)} / ${Formatter.formatShortFileSize(context, maxCanvasCacheSize * 1024 * 1024L)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text("Clear canvas cache") },
                    onClick = { clearCanvasCacheDialog = true },
                ),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.image_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.manage_search),
                    title = { Text(stringResource(R.string.max_image_cache_size)) },
                    description = {
                        val imageCacheValues = remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }
                        Column {
                            Text(
                                text = when (maxImageCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    else -> Formatter.formatShortFileSize(context, maxImageCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = imageCacheValues.indexOf(maxImageCacheSize).toFloat(),
                                onValueChange = {
                                    val newValue = imageCacheValues[it.roundToInt()]
                                    val newLimitInBytes = newValue * 1024 * 1024L

                                    if (newLimitInBytes < imageCacheSize) {
                                        cacheUsage = imageCacheSize
                                        cacheType = imageCacheString
                                        onConfirmAction = { onMaxImageCacheSizeChange(newValue) }
                                        showCacheWarningDialog = true
                                    } else {
                                        onMaxImageCacheSizeChange(newValue)
                                    }
                                },
                                steps = imageCacheValues.size - 2,
                                valueRange = 0f..(imageCacheValues.size - 1).toFloat(),
                            )
                            LinearProgressIndicator(
                                progress = { imageCacheProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round,
                            )
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text(
                                text = "${Formatter.formatShortFileSize(context, imageCacheSize)} / ${Formatter.formatShortFileSize(context, maxImageCacheSize * 1024 * 1024L)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_image_cache)) },
                    onClick = { clearImageCacheDialog = true },
                ),
            ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

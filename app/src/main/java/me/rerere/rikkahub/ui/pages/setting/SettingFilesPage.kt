/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var protectInUse by remember { mutableStateOf(true) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }

    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())
    val settings by settingsStore.settingsFlow.collectAsState(initial = Settings.dummy())
    val settingsRaw = remember(settings) { settings.toString() }

    fun isFileProtected(file: ManagedFileEntity, fileOnDisk: File): Boolean {
        if (!protectInUse) return false
        val idStr = file.id.toString()
        val diskName = fileOnDisk.name
        return (idStr.isNotEmpty() && settingsRaw.contains(idStr)) ||
            (diskName.isNotEmpty() && settingsRaw.contains(diskName))
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) {
                                selectedKeys = selectedKeys - target.id.toString()
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (pendingBatchDelete) {
        val targets = files.filter { file ->
            val disk = filesManager.getFile(file)
            selectedKeys.contains(file.id.toString()) && !isFileProtected(file, disk)
        }
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text("确定要删除选中的 ${targets.size} 个文件吗？（正在使用的头像和背景已自动跳过保护）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            var successCount = 0
                            targets.forEach { target ->
                                val ok = filesManager.delete(target.id, deleteFromDisk = true)
                                if (ok) successCount++
                            }
                            selectedKeys = emptySet()
                            isSelectionMode = false
                            pendingBatchDelete = false
                            if (successCount > 0) {
                                toaster.show("已清理 $successCount 个文件")
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (files.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                isSelectionMode = !isSelectionMode
                                if (!isSelectionMode) selectedKeys = emptySet()
                            }
                        ) {
                            Text(if (isSelectionMode) "完成" else "多选")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it },
                isSelectionMode = isSelectionMode,
                protectInUse = protectInUse,
                onToggleProtect = {
                    protectInUse = !protectInUse
                    if (protectInUse) {
                        val protectedIds = files.filter { isFileProtected(it, filesManager.getFile(it)) }
                            .map { it.id.toString() }
                            .toSet()
                        selectedKeys = selectedKeys - protectedIds
                    }
                },
                selectedCount = selectedKeys.size,
                onSelectAll = {
                    val selectableIds = files.filter { !isFileProtected(it, filesManager.getFile(it)) }
                        .map { it.id.toString() }
                        .toSet()
                    selectedKeys = if (selectedKeys.containsAll(selectableIds) && selectableIds.isNotEmpty()) {
                        emptySet()
                    } else {
                        selectableIds
                    }
                },
                onDeleteSelected = {
                    if (selectedKeys.isNotEmpty()) {
                        pendingBatchDelete = true
                    }
                }
            )

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        val diskFile = filesManager.getFile(file)
                        val isProtected = isFileProtected(file, diskFile)
                        val key = file.id.toString()
                        val isSelected = selectedKeys.contains(key)

                        FileItem(
                            file = file,
                            fileOnDisk = diskFile,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            isProtected = isProtected,
                            onToggleSelect = {
                                if (isProtected) {
                                    toaster.show("该文件正作为头像或背景使用，受白名单保护")
                                } else {
                                    selectedKeys = if (isSelected) selectedKeys - key else selectedKeys + key
                                }
                            },
                            onDelete = {
                                if (isProtected) {
                                    toaster.show("该文件正作为头像或背景使用，如需删除请先关闭右上角白名单保护")
                                } else {
                                    pendingDelete = file
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
    isSelectionMode: Boolean,
    protectInUse: Boolean,
    onToggleProtect: () -> Unit,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }

        FilterChip(
            selected = protectInUse,
            onClick = onToggleProtect,
            label = { Text(if (protectInUse) "保留头像/背景: 开" else "保留头像/背景: 关") }
        )

        if (isSelectionMode) {
            FilterChip(
                selected = false,
                onClick = onSelectAll,
                label = { Text("全选可清理项") }
            )
            FilterChip(
                selected = selectedCount > 0,
                onClick = onDeleteSelected,
                label = { Text("删除所选($selectedCount)") }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    else -> folder
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isProtected: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSelectionMode) {
                    onToggleSelect()
                }
            },
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        enabled = !isProtected,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                } else {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                if (isProtected) {
                    Text(
                        text = "使用中 · 已保护",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}

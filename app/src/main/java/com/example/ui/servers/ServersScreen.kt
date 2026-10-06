package com.example.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.data.model.VlessProfile
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.panels.PanelType
import com.example.ui.components.ThemeToggleSwitch
import com.example.ui.theme.AppTheme
import com.example.ui.viewmodel.ServerSortOption
import com.example.ui.viewmodel.ServerViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel

@Composable
fun ServersScreen(
    serverViewModel: ServerViewModel,
    vpnViewModel: VpnViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToAddServer: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToSubscriptions: () -> Unit,
    onNavigateToBenchmark: () -> Unit,
    onNavigateToPanels: () -> Unit = {},
    onNavigateToProtocols: () -> Unit = {},
    onNavigateToFreeConfigs: () -> Unit = {},
    onNavigateToVip: () -> Unit = {},
    onNavigateToEditServer: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val serverList by serverViewModel.serverList.collectAsStateWithLifecycle()
    val searchQuery by serverViewModel.searchQuery.collectAsStateWithLifecycle()
    val onlyFavorites by serverViewModel.onlyFavorites.collectAsStateWithLifecycle()
    val sortOption by serverViewModel.sortOption.collectAsStateWithLifecycle()
    val isTestingAll by serverViewModel.isTestingAll.collectAsStateWithLifecycle()
    val testAllProgress by serverViewModel.testAllProgress.collectAsStateWithLifecycle()
    val testingStates by serverViewModel.serverTestingStates.collectAsStateWithLifecycle()

    val selectedProfileId by serverViewModel.selectedProfileId.collectAsStateWithLifecycle()
    val connectionState by vpnViewModel.connectionState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    var shareProfile by remember { mutableStateOf<VlessProfile?>(null) }

    val managedPanels by serverViewModel.managedPanels.collectAsStateWithLifecycle()
    val selected3xuiPanel by serverViewModel.selected3xuiPanel.collectAsStateWithLifecycle()
    val isGenerating3xuiConfig by serverViewModel.isGenerating3xuiConfig.collectAsStateWithLifecycle()
    val lastGenerated3xuiResult by serverViewModel.lastGenerated3xuiResult.collectAsStateWithLifecycle()
    val showGeneratedDialog by serverViewModel.showGeneratedDialog.collectAsStateWithLifecycle()
    val generation3xuiError by serverViewModel.generation3xuiError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        serverViewModel.refreshPanels()
    }
    val appSettings by com.example.RayApplication.instance.settingsRepository.settingsFlow.collectAsStateWithLifecycle()
    val freeConfigsEnabled = appSettings.freeConfigsEnabled

    var showSortMenu by remember { mutableStateOf(false) }
    var confirmDeleteFree by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().testTag("servers_screen")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Screen Title & Action Bar with Theme Toggle Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Proxy Nodes",
                        color = AppTheme.colors.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${serverList.size} universal nodes configured",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                // Theme Toggle Switch at Top
                ThemeToggleSwitch(
                    isDark = settings.darkTheme,
                    onThemeChange = { isDark ->
                        settingsViewModel.setDarkTheme(isDark)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Hub Navigation Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item { VipChip(onClick = onNavigateToVip) }
                item {
                    HubChip(
                        icon = Icons.Default.Hub,
                        title = "Protocols",
                        onClick = onNavigateToProtocols
                    )
                }
                // Hidden while the user has free configs turned off in Settings.
                if (freeConfigsEnabled) item {
                    HubChip(
                        icon = Icons.Default.CloudDownload,
                        title = "Free Configs",
                        onClick = onNavigateToFreeConfigs
                    )
                }
                item {
                    HubChip(
                        icon = Icons.Default.FileDownload,
                        title = "Universal Import",
                        onClick = onNavigateToImport
                    )
                }
                item {
                    HubChip(
                        icon = Icons.Default.RssFeed,
                        title = "Subscriptions",
                        onClick = onNavigateToSubscriptions
                    )
                }
                item {
                    HubChip(
                        icon = Icons.Default.Speed,
                        title = "Benchmark Lab",
                        onClick = onNavigateToBenchmark
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3X-UI Automatic Inbound Generator Card & Mobile Dropdown Interface
            ThreeXUiGeneratorCard(
                serverViewModel = serverViewModel,
                panels = managedPanels.filter { it.type == PanelType.XUI },
                selectedPanel = selected3xuiPanel,
                isGenerating = isGenerating3xuiConfig,
                lastResult = lastGenerated3xuiResult,
                showResultDialog = showGeneratedDialog,
                onConnectProfile = { profileId ->
                    serverList.firstOrNull { it.id == profileId }?.let { prof ->
                        serverViewModel.selectServer(prof)
                    }
                },
                onNavigateToPanels = onNavigateToPanels,
                generationError = generation3xuiError
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Nodes header: count and Ping All on one slim line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ALL NODES · ${serverList.size}",
                    color = AppTheme.colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                // Only while downloaded free configs are in the list; VIP and imported ones are never touched.
                val freeCount = serverList.count { serverViewModel.isFreeConfig(it) }
                if (freeCount > 0) {
                    TextButton(
                        onClick = { confirmDeleteFree = true },
                        enabled = !isTestingAll,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("delete_free_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Delete Free",
                            tint = AppTheme.colors.statusError,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Free", color = AppTheme.colors.statusError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(
                    // While a run is going the button stops it.
                    onClick = { if (isTestingAll) serverViewModel.stopTestAll() else serverViewModel.testAllServers() },
                    enabled = isTestingAll || serverList.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("test_all_servers_button")
                ) {
                    if (isTestingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = AppTheme.colors.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop · ${testAllProgress.first}/${testAllProgress.second}", color = AppTheme.colors.primary, fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.NetworkPing,
                            contentDescription = "Ping All",
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ping All", color = AppTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                }
            }

            if (confirmDeleteFree) {
                val context = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDeleteFree = false },
                    containerColor = AppTheme.colors.surfaceElevated,
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = AppTheme.colors.statusError) },
                    title = { Text("Delete all free configurations?", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "This will remove all downloaded free configurations. VIP and manually imported configurations will not be affected.",
                            color = AppTheme.colors.textSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                confirmDeleteFree = false
                                serverViewModel.deleteAllFree { n ->
                                    android.widget.Toast.makeText(context, "Deleted $n free configurations", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.statusError),
                            modifier = Modifier.testTag("delete_free_confirm")
                        ) { Text("Delete All", color = Color.Black, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDeleteFree = false }) { Text("Cancel", color = AppTheme.colors.primary) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Search Bar & Filters (40dp high instead of the 56dp Material text field)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { serverViewModel.setSearchQuery(it) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = AppTheme.colors.textPrimary, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppTheme.colors.primary),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("server_search_input"),
                    decorationBox = { inner ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppTheme.colors.surfaceCard)
                                .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(12.dp))
                                .padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = AppTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search nodes, host, protocol",
                                        color = AppTheme.colors.textMuted,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { serverViewModel.setSearchQuery("") },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = AppTheme.colors.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Favorite Filter Toggle
                // Plain Box: IconButton would pad itself to 48dp and outgrow the 40dp search field.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { serverViewModel.toggleFavoritesFilter() }
                        .background(
                            if (onlyFavorites) {
                                if (AppTheme.colors.isDark) Color(0xFF2E2410) else Color(0xFFFEF3C7)
                            } else AppTheme.colors.surfaceCard
                        )
                        .border(
                            1.dp,
                            if (onlyFavorites) AppTheme.colors.statusWarning.copy(alpha = 0.5f) else AppTheme.colors.borderSubtle,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (onlyFavorites) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Filter Favorites",
                        tint = if (onlyFavorites) AppTheme.colors.statusWarning else AppTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Sort Menu
                Box {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSortMenu = true }
                            .background(AppTheme.colors.surfaceCard)
                            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Sort Options",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier
                            .background(AppTheme.colors.surfaceCard)
                            .border(1.dp, AppTheme.colors.borderSubtle)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Default Order", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.DEFAULT)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Highest Overall Score", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.SCORE)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Lowest Latency / Ping", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.LATENCY)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Highest Download Speed", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.DOWNLOAD_SPEED)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Highest Stability", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.STABILITY)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Alphabetical by Name", color = AppTheme.colors.textPrimary) },
                            onClick = {
                                serverViewModel.setSortOption(ServerSortOption.NAME)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Server List or Empty State
            if (serverList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = AppTheme.colors.textMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank() || onlyFavorites) "No matching nodes found" else "No proxy nodes configured",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Universal Import' or '+' to add proxy configurations",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(serverList, key = { it.id }) { profile ->
                        ServerItemCard(
                            profile = profile,
                            isSelected = profile.id == selectedProfileId,
                            isConnected = profile.id == selectedProfileId && connectionState.isConnected,
                            testingStatus = testingStates[profile.id],
                            onSelect = {
                                serverViewModel.selectServer(profile)
                            },
                            onConnectDirect = {
                                serverViewModel.selectServer(profile)
                            },
                            onToggleFavorite = {
                                serverViewModel.toggleFavorite(profile)
                            },
                            onTestPing = {
                                serverViewModel.testServer(profile)
                            },
                            onDuplicate = {
                                serverViewModel.duplicateServer(profile)
                            },
                            onDelete = {
                                serverViewModel.deleteServer(profile.id)
                            },
                            onExportUri = {
                                serverViewModel.exportUri(it)
                            },
                            onShareOffline = { shareProfile = profile },
                            onEdit = if (profile.profileType == com.example.data.model.ProfileType.VLESS) {
                                { onNavigateToEditServer(profile.id) }
                            } else null
                        )
                    }
                }
            }
        }

        // Floating Action Button to Add Server
        FloatingActionButton(
            onClick = onNavigateToAddServer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_server_fab"),
            containerColor = AppTheme.colors.primary,
            contentColor = AppTheme.colors.onPrimary,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Node",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    shareProfile?.let { profile ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val allProfiles by androidx.compose.runtime.produceState(initialValue = serverList, profile) {
            value = com.example.RayApplication.instance.serverRepository.getAllProfilesOnce()
        }
        com.example.ui.share.ShareOfflineSheet(
            profile = profile,
            allProfiles = allProfiles,
            onCopy = { text ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { shareProfile = null }
        )
    }
}

/** The gold entry to the VIP section, first in the row. */
@Composable
private fun VipChip(onClick: () -> Unit) {
    val ink = androidx.compose.ui.graphics.Color(0xFF1A1205)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFFF7DFA0), androidx.compose.ui.graphics.Color(0xFFD4A651), androidx.compose.ui.graphics.Color(0xFF9C7230))
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Default.WorkspacePremium, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "VIP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = ink)
    }
}

@Composable
private fun HubChip(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

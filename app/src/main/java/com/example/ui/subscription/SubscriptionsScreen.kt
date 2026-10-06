package com.example.ui.subscription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.SecretRedactor
import com.example.data.model.SubscriptionInfo
import com.example.vpn.hub.FreeConfigList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Healthy = Color(0xFF34D399)
private val Failing = Color(0xFFF87171)

/** One colour per subscription, shared by its icon and its share of the node bar. */
private val SeriesColors = listOf(
    Color(0xFF8B7CF6), Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFFBBF24),
    Color(0xFFF472B6), Color(0xFF60A5FA), Color(0xFFA3E635), Color(0xFFFB923C)
)

@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null
) {
    val subscriptions by viewModel.subscriptionsList.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SubscriptionsContent(
        subscriptions = subscriptions,
        uiState = uiState,
        modifier = modifier,
        onNavigateBack = onNavigateBack,
        onAddClick = { viewModel.showAddDialog(true) },
        onAddSource = { name, url -> viewModel.addSubscription(name, url) },
        onSync = { viewModel.syncSubscription(it) },
        onSyncAll = { viewModel.syncAll() },
        onDelete = { viewModel.deleteSubscription(it) },
        onDismissMessages = { viewModel.clearMessages() }
    )

    if (uiState.showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { viewModel.showAddDialog(false) },
            onAdd = { name, url -> viewModel.addSubscription(name, url) }
        )
    }
}

/**
 * Subscriptions get the whole page: an overview of where the nodes come from, one card per
 * subscription, and the free sources folded into one row at the bottom.
 */
@Composable
internal fun SubscriptionsContent(
    subscriptions: List<SubscriptionInfo>,
    uiState: SubscriptionsUiState,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    onAddClick: () -> Unit,
    onAddSource: (name: String, url: String) -> Unit,
    onSync: (SubscriptionInfo) -> Unit,
    onSyncAll: () -> Unit,
    onDelete: (SubscriptionInfo) -> Unit,
    onDismissMessages: () -> Unit,
    initialHubExpanded: Boolean = false
) {
    var hubExpanded by remember { mutableStateOf(initialHubExpanded) }
    var pendingDelete by remember { mutableStateOf<SubscriptionInfo?>(null) }

    Scaffold(
        modifier = modifier.testTag("subscriptions_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("subscriptions_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = "Subscriptions",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (subscriptions.isNotEmpty()) {
                    TextButton(
                        onClick = onSyncAll,
                        enabled = !uiState.isSyncing,
                        modifier = Modifier.testTag("sync_all_subscriptions")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update all", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add subscription", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.testTag("add_subscription_fab")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (subscriptions.isNotEmpty()) {
                item(key = "overview") { OverviewCard(subscriptions) }
            }

            val message = uiState.errorMessage ?: uiState.statusMessage
            if (message != null) {
                item(key = "banner") {
                    MessageBanner(text = message, isError = uiState.errorMessage != null, onDismiss = onDismissMessages)
                }
            }

            if (subscriptions.isEmpty()) {
                item(key = "empty") { EmptySubscriptions(onAddClick) }
            } else {
                item(key = "list_title") { SectionLabel("Your subscriptions") }
                items(subscriptions, key = { it.id }) { sub ->
                    SubscriptionCard(
                        subscription = sub,
                        color = colorFor(subscriptions.indexOf(sub)),
                        isSyncing = uiState.isSyncing && uiState.syncingSubscriptionId == sub.id,
                        syncEnabled = !uiState.isSyncing,
                        onSync = { onSync(sub) },
                        onDelete = { pendingDelete = sub }
                    )
                }
            }

            item(key = "hub") {
                val hasFreeList = subscriptions.any { FreeConfigList.isList(it.url) || it.name == FreeConfigList.NAME }
                FreeSourcesSection(
                    expanded = hubExpanded,
                    onToggle = { hubExpanded = !hubExpanded },
                    offerFreeList = !hasFreeList && FreeConfigList.available(),
                    busy = uiState.isSyncing,
                    onAdd = onAddSource
                )
            }
        }
    }

    pendingDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${sub.name}?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Its ${sub.nodeCount} nodes are removed from the server list too.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(sub)
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_subscription")
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun colorFor(index: Int): Color = SeriesColors[index.coerceAtLeast(0) % SeriesColors.size]

@Composable
private fun SurfaceCard(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) { content() }
}

/** Total nodes, and a bar that shows how much of them each subscription brings. */
@Composable
private fun OverviewCard(subscriptions: List<SubscriptionInfo>) {
    val total = subscriptions.sumOf { it.nodeCount }
    val failing = subscriptions.count { it.lastError != null }
    val lastUpdate = subscriptions.maxOfOrNull { it.lastUpdated } ?: 0L

    SurfaceCard {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Total nodes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%,d".format(total),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${subscriptions.size} ${if (subscriptions.size == 1) "subscription" else "subscriptions"}",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (failing > 0) "$failing need attention" else if (lastUpdate > 0) "Updated ${timeAgo(lastUpdate)} ago" else "Not updated yet",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (failing > 0) Failing else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Share of nodes per subscription.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (total > 0) {
                    subscriptions.forEachIndexed { i, sub ->
                        if (sub.nodeCount > 0) {
                            Box(
                                Modifier
                                    .weight(sub.nodeCount.toFloat())
                                    .fillMaxHeight()
                                    .background(colorFor(i))
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                subscriptions.forEachIndexed { i, sub ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorFor(i)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            sub.name,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (total > 0) "${(sub.nodeCount * 100f / total).toInt()}%" else "—",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun MessageBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val tint = if (isError) Failing else Healthy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionInfo,
    color: Color,
    isSyncing: Boolean,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val failing = subscription.lastError != null
    SurfaceCard(
        modifier = Modifier.testTag("subscription_card_${subscription.id}"),
        borderColor = if (failing) Failing.copy(alpha = 0.4f) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = subscription.name.trim().take(1).uppercase().ifBlank { "#" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = displayHost(subscription.url),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%,d".format(subscription.nodeCount),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (subscription.nodeCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("nodes", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusColor = when {
                failing -> Failing
                subscription.lastUpdated > 0 -> Healthy
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLine(subscription, isSyncing),
                fontSize = 12.5.sp,
                color = if (failing && !isSyncing) Failing else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onSync,
                enabled = syncEnabled,
                modifier = Modifier.testTag("sync_subscription_${subscription.id}")
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Update", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_subscription_${subscription.id}")) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun statusLine(sub: SubscriptionInfo, syncing: Boolean): String {
    if (syncing) return "Updating…"
    sub.lastError?.let { return "Update failed · $it" }
    if (sub.lastUpdated <= 0) return "Not updated yet"
    val time = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(sub.lastUpdated))
    val mirrors = if (sub.mirrors.isNotEmpty()) " · ${sub.mirrors.size} mirrors" else ""
    return "Active · $time$mirrors"
}

@Composable
private fun EmptySubscriptions(onAddClick: () -> Unit) {
    SurfaceCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("No subscriptions yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a provider link, or add one of the free sources below.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddClick, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add subscription", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FreeSourcesSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    offerFreeList: Boolean,
    busy: Boolean,
    onAdd: (name: String, url: String) -> Unit
) {
    val sources = buildList {
        if (offerFreeList) {
            add(
                FreeConfigSource(
                    id = "maximus_free",
                    name = FreeConfigList.NAME,
                    description = "The signed MAXIMUS list, tested every 6 hours.",
                    protocols = "Recommended",
                    url = FreeConfigList.URL
                )
            )
        }
        addAll(FreeConfigHubSources.sources)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Free sources")
        SurfaceCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("free_config_hub_toggle"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Free Config Hub", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "${sources.size} public sources, checked before import",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    sources.forEach { source ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                .testTag("free_config_source_${source.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(source.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                    if (source.id == "maximus_free") {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Recommended",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Healthy,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Healthy.copy(alpha = 0.12f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (source.id == "maximus_free") source.description else "${source.description} ${source.protocols}.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onAdd(source.name, source.url) },
                                enabled = !busy,
                                modifier = Modifier.testTag("add_free_config_${source.id}")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add ${source.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "Public relays are not trusted infrastructure. Avoid sensitive traffic until a node has passed Maximus health and security checks.",
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/** The link's host, which tells subscriptions apart without showing tokens in the path or query. */
private fun displayHost(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.")
        ?: SecretRedactor.redact(url)

private fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - millis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "moments"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Add Subscription", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider / Name (Optional)") },
                    placeholder = { Text("e.g. My Premium Proxy Sub") },
                    modifier = Modifier.fillMaxWidth().testTag("sub_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Subscription HTTPS URL") },
                    placeholder = { Text("https://example.com/api/v1/client/subscribe?token=...") },
                    modifier = Modifier.fillMaxWidth().testTag("sub_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_sub_button")
            ) {
                Text("Add & Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


private data class FreeConfigSource(
    val id: String,
    val name: String,
    val description: String,
    val protocols: String,
    val url: String
)

private object FreeConfigHubSources {
    val sources = listOf(
        FreeConfigSource(
            id = "patterniha",
            name = "Patterniha Free Configs",
            description = "Aggregated and tested public configurations; upstream refreshes the list automatically.",
            protocols = "VLESS • VMess • Trojan • Shadowsocks",
            url = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
        ),
        FreeConfigSource(
            id = "whitedns",
            name = "WhiteDNS Public",
            description = "Public Mihomo catalogue used by the WhiteVPN ecosystem.",
            protocols = "Mihomo/Clash • VLESS • VMess • Trojan • Shadowsocks",
            url = "https://raw.githubusercontent.com/iampedii/whitedns-sub/main/mihomo.yaml"
        ),
        FreeConfigSource(
            id = "vify_vless",
            name = "Vify Public VLESS",
            description = "Vify's currently alive public VLESS subscription feed.",
            protocols = "VLESS",
            url = "https://raw.githubusercontent.com/Mr-Meshky/vify/main/configs/alive/vless.txt"
        )
    )
}

package com.example.ui.subscription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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

private val Healthy = Color(0xFF4ADE80)
private val Failing = Color(0xFFF87171)

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
 * Subscriptions get the whole page: one scrolling list with a summary at the top, a large card per
 * subscription, and the Free Config Hub folded into one row at the bottom.
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
    onDelete: (SubscriptionInfo) -> Unit,
    onDismissMessages: () -> Unit,
    initialHubExpanded: Boolean = false
) {
    var hubExpanded by remember { mutableStateOf(initialHubExpanded) }
    var pendingDelete by remember { mutableStateOf<SubscriptionInfo?>(null) }

    Scaffold(
        modifier = modifier.testTag("subscriptions_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add link", fontWeight = FontWeight.Bold) },
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
            item(key = "header") {
                SubscriptionsHeader(subscriptions = subscriptions, onNavigateBack = onNavigateBack)
            }

            val message = uiState.errorMessage ?: uiState.statusMessage
            if (message != null) {
                item(key = "banner") {
                    MessageBanner(
                        text = message,
                        isError = uiState.errorMessage != null,
                        onDismiss = onDismissMessages
                    )
                }
            }

            item(key = "list_title") {
                SectionTitle(
                    title = "Your subscriptions",
                    trailing = if (subscriptions.isEmpty()) null else "${subscriptions.size}"
                )
            }

            if (subscriptions.isEmpty()) {
                item(key = "empty") { EmptySubscriptions(onAddClick) }
            } else {
                items(subscriptions, key = { it.id }) { sub ->
                    SubscriptionCard(
                        subscription = sub,
                        isSyncing = uiState.isSyncing && uiState.syncingSubscriptionId == sub.id,
                        syncEnabled = !uiState.isSyncing,
                        onSync = { onSync(sub) },
                        onDelete = { pendingDelete = sub }
                    )
                }
            }

            item(key = "hub") {
                val hasFreeList = subscriptions.any { FreeConfigList.isList(it.url) || it.name == FreeConfigList.NAME }
                FreeConfigHubSection(
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
            title = { Text("Delete ${sub.name}?", fontWeight = FontWeight.Bold) },
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

@Composable
private fun SubscriptionsHeader(
    subscriptions: List<SubscriptionInfo>,
    onNavigateBack: (() -> Unit)?
) {
    val totalNodes = subscriptions.sumOf { it.nodeCount }
    val failing = subscriptions.count { it.lastError != null }
    val lastUpdate = subscriptions.maxOfOrNull { it.lastUpdated } ?: 0L

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onNavigateBack != null) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("subscriptions_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(4.dp))
            }
            Column {
                Text(
                    text = "Subscriptions",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Provider links and the nodes they bring",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        )
                    )
                )
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryStat(value = totalNodes.toString(), label = "Nodes")
            SummaryDivider()
            SummaryStat(value = subscriptions.size.toString(), label = "Links")
            SummaryDivider()
            SummaryStat(
                value = if (failing == 0) "OK" else failing.toString(),
                label = if (failing == 0) "Health" else "Failing",
                valueColor = if (failing == 0) Healthy else Failing
            )
            SummaryDivider()
            SummaryStat(value = if (lastUpdate > 0) timeAgo(lastUpdate) else "—", label = "Updated")
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, valueColor: Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
    )
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = trailing,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val tint = if (isError) Failing else Healthy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionInfo,
    isSyncing: Boolean,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val failing = subscription.lastError != null
    val neverSynced = subscription.lastUpdated <= 0L && !failing
    val statusColor = when {
        failing -> Failing
        neverSynced -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Healthy
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (failing) Failing.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
            .testTag("subscription_card_${subscription.id}"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = subscription.name.trim().take(1).uppercase().ifBlank { "#" },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayHost(subscription.url),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // The node count is the headline of the card.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = subscription.nodeCount.toString(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (subscription.nodeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (subscription.nodeCount == 1) "node" else "nodes",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    val updated = if (subscription.lastUpdated > 0) {
                        "Updated " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(subscription.lastUpdated))
                    } else "Not updated yet"
                    val mirrors = subscription.mirrors.size
                    Text(
                        text = if (mirrors > 0) "$updated • $mirrors mirrors" else updated,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(
                text = when {
                    failing -> "Error"
                    neverSynced -> "New"
                    else -> "Active"
                },
                color = statusColor
            )
        }

        subscription.lastError?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Failing.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Failing, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Last update failed: $err",
                    fontSize = 12.sp,
                    color = Failing
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = onSync,
                enabled = syncEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("sync_subscription_${subscription.id}"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Updating…", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Update", fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("delete_subscription_${subscription.id}"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Failing.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Failing),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun EmptySubscriptions(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("No subscriptions yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste a provider link, or add one of the free sources below.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddClick, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add link", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FreeConfigHubSection(
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
                    description = "The signed MAXIMUS list. Add it back to get the free servers again.",
                    protocols = "Signed • tested every 6 hours",
                    url = FreeConfigList.URL
                )
            )
        }
        addAll(FreeConfigHubSources.sources)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp)
                .testTag("free_config_hub_toggle"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Free Config Hub", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (offerFreeList) "${sources.size} public sources • MAXIMUS Free can be restored" else "${sources.size} public sources",
                    fontSize = 11.5.sp,
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
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp)
                            .testTag("free_config_source_${source.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(source.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(source.protocols, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        FilledTonalButton(
                            onClick = { onAdd(source.name, source.url) },
                            enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(36.dp).testTag("add_free_config_${source.id}")
                        ) {
                            Text("Add", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                Text(
                    text = "Public relays are not trusted infrastructure. Avoid sensitive traffic until a node has passed Maximus health and security checks.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        minutes < 1 -> "now"
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

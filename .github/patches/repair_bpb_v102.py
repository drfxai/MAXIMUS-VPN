#!/usr/bin/env python3
"""Apply tested branch-only fixes AFTER unpacking the exact uploaded v1.0.2 ZIP."""
from pathlib import Path

panel = Path("app/src/main/java/com/example/panels/PanelProvisioner.kt")
src = panel.read_text()
def replace_once(old, new):
    global src
    assert src.count(old) == 1, f"Expected exactly one source block: {old[:90]!r}"
    src = src.replace(old, new, 1)

# The screenshot comes from a build that discarded Cloudflare's 400 response.
# Surface Cloudflare's actual machine-readable errors, omit raw HTTP bodies and
# redact the user's API token even if a provider reflects it in error text.
replace_once(
'''                val hint = if (response.code == 403 || message.contains("10000")) {''',
'''                val safeMessage = message.replace(token, "[REDACTED]", ignoreCase = false)
                val hint = if (response.code == 403 || safeMessage.contains("10000")) {'''
)
replace_once(
'''                error("Cloudflare API error: $message$hint")''',
'''                error("Cloudflare HTTP ${response.code}: $safeMessage$hint")'''
)

# Pin the compatibility behavior BPB was developed for rather than relying on
# the machine's changing current date and automatically enabled 2026 flags.
replace_once(
'''            .put("compatibility_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))''',
'''            .put("compatibility_date", "2026-08-03")'''
)
replace_once(
'''        cf(Request.Builder().url("$root/workers/scripts/$workerName").put(body), token)
        onLog("[CF] Worker $workerName uploaded")''',
'''        try {
            cf(Request.Builder().url("$root/workers/scripts/$workerName").put(body), token)
        } catch (ex: IllegalStateException) {
            onLog("[ERROR] Cloudflare rejected the Worker upload: ${ex.message}")
            throw ex
        }
        onLog("[CF] Worker $workerName uploaded")'''
)
panel.write_text(src)

# The uploaded ZIP has an incomplete ServerViewModel API; the previous GitHub
# run fails before executing the Cloudflare regression tests. Restore the
# state and handlers consumed by ServersScreen without modifying main.
vm = Path("app/src/main/java/com/example/ui/viewmodel/ServerViewModel.kt")
text = vm.read_text()
anchor = '''    init {
        refreshPanels()
    }'''
assert text.count(anchor) == 1
text = text.replace(anchor, '''    val selectedProfileId: StateFlow<String?> = settingsRepository.settingsFlow
        .map { it.selectedProfileId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val serverList: StateFlow<List<VlessProfile>> =
        combine(repository.allProfiles, _searchQuery, _onlyFavorites, _sortOption) { profiles, query, favorites, sort ->
            val filtered = profiles.filter { profile ->
                (!favorites || profile.isFavorite) && (query.isBlank() ||
                    profile.name.contains(query, ignoreCase = true) ||
                    profile.address.contains(query, ignoreCase = true))
            }
            when (sort) {
                ServerSortOption.SCORE -> filtered.sortedByDescending { it.overallScore }
                ServerSortOption.LATENCY -> filtered.sortedBy { it.lastLatencyMs ?: Long.MAX_VALUE }
                ServerSortOption.DOWNLOAD_SPEED -> filtered.sortedByDescending { it.downloadMbps }
                ServerSortOption.STABILITY -> filtered.sortedByDescending { it.stability }
                ServerSortOption.NAME -> filtered.sortedBy { it.name.lowercase() }
                ServerSortOption.DEFAULT -> filtered
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun toggleFavoritesFilter() { _onlyFavorites.value = !_onlyFavorites.value }
    fun setSortOption(option: ServerSortOption) { _sortOption.value = option }
    fun selectServer(profile: VlessProfile) { settingsRepository.setSelectedProfileId(profile.id) }
    fun toggleFavorite(profile: VlessProfile) {
        launchSave("toggle favorite") { repository.toggleFavorite(profile.id, !profile.isFavorite) }
    }
    fun deleteServer(id: String) {
        launchSave("delete server") { repository.delete(id) }
    }

    init {
        refreshPanels()
    }''', 1)
text = text.replace("import kotlinx.coroutines.flow.stateIn", "import kotlinx.coroutines.flow.map\nimport kotlinx.coroutines.flow.stateIn", 1)
assert "import kotlinx.coroutines.flow.map\n" in text
vm.write_text(text)
print("Applied BPB diagnostics/compatibility patch and ServerViewModel compilation repair")

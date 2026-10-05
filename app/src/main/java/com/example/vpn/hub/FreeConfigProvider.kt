package com.example.vpn.hub

/** Where free configs come from. Every source is untrusted input, the official one included. */
data class FreeConfigProvider(
    val id: String,
    val name: String,
    /** The first address is the source; the rest are mirrors of the same content. */
    val urls: List<String>,
    val kind: Kind
) {
    enum class Kind {
        /** The project's own subscription (the Telegram bot's Worker, later the signed aggregator). */
        OFFICIAL,
        /** A public list someone else publishes. */
        PUBLIC,
        /** Added by the user. */
        USER
    }
}

/**
 * The providers the hub syncs. Provider definitions live here, not in UI code; the official one comes
 * from OfficialSubscriptions and appears once its address exists.
 */
class ProviderRegistry(
    officialUrls: List<String> = com.example.vpn.subscription.OfficialSubscriptions.URLS,
    extra: List<FreeConfigProvider> = emptyList()
) {
    val providers: List<FreeConfigProvider> = buildList {
        if (officialUrls.isNotEmpty()) add(FreeConfigProvider(OFFICIAL_ID, "MAXIMUS", officialUrls, FreeConfigProvider.Kind.OFFICIAL))
        addAll(extra.filter { it.id != OFFICIAL_ID && it.urls.isNotEmpty() })
    }

    fun byId(id: String): FreeConfigProvider? = providers.firstOrNull { it.id == id }

    companion object {
        const val OFFICIAL_ID = "official"
    }
}

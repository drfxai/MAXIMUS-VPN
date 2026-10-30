package com.example.ui.browser

internal object GodBrowser {
    const val HOME = "https://www.google.com"

    /** What the address bar opens: a link as typed, a bare domain over https, anything else a Google search. */
    fun target(input: String): String {
        val text = input.trim()
        return when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
            text.isEmpty() -> HOME
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
    }

    val OUTSIDE_VPN_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;padding:24px;line-height:1.5;background:#121212;color:#e0e0e0">
<h3>Browser paused</h3>
<p>The engine you are connected with leaves this app outside the VPN, so pages opened here would use your normal connection.</p>
<p>Connect with a regular server (VLESS, VMess, Trojan, Shadowsocks and similar) to browse here, or use your usual browser, which still goes through the VPN.</p>
</body></html>"""
}

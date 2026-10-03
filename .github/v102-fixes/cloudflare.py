from pathlib import Path
p = Path("app/src/main/java/com/example/panels/PanelProvisioner.kt")
s = p.read_text()
before = 'cf(Request.Builder().url("$root/workers/scripts/$workerName").put(body), token)'
assert s.count(before) == 1, "Expected exact ZIP source: Worker upload anchor missing"
s = s.replace(before, 'cf(Request.Builder().url("$root/workers/scripts/$workerName").put(body), token, "BPB Worker upload")')
start = s.index('    private fun cf(builder: Request.Builder, token: String): JSONObject {')
end = s.index("\n    companion object {", start)
s = s[:start] + '''    private fun cf(builder: Request.Builder, token: String, stage: String = "Cloudflare request"): JSONObject {
        val request = builder.header("Authorization", "Bearer $token").build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("success", false) != true) {
                val reason = cloudflareFailureMessage(response.code, body, token, stage)
                val ray = response.header("CF-Ray")?.take(80)?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }
                error(if (ray == null) reason else "$reason (CF-Ray: $ray)")
            }
            json
        }
    }
''' + s[end:]
anchor = "    companion object {\n"
assert s.count(anchor) == 1
s = s.replace(anchor, '''    companion object {
        /** Preserve Cloudflare's actual error code and message, never the raw HTTP body or Bearer token. */
        internal fun cloudflareFailureMessage(status: Int, body: String, token: String, stage: String): String {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val errors = json?.optJSONArray("errors")
            val entries = (0 until (errors?.length() ?: 0)).mapNotNull { index ->
                val error = errors?.optJSONObject(index) ?: return@mapNotNull null
                val code = error.optInt("code", 0)
                val message = error.optString("message").replace(token, "[REDACTED]").take(900)
                if (code != 0) "[$code] $message" else message
            }.filter { it.isNotBlank() }
            val detail = entries.joinToString("; ").ifBlank { "Cloudflare did not provide error details" }
            val hint = when {
                entries.any { it.contains("[10021]") } -> " — Cloudflare rejected the Worker JavaScript; inspect its compilation error above."
                status == 403 || entries.any { it.contains("[10000]") } -> " — check this token's Workers Scripts Edit, Workers KV Storage Edit and account scope."
                else -> ""
            }
            return "$stage failed (HTTP $status): $detail$hint"
        }
''', 1)
p.write_text(s)
print("Cloudflare upload error diagnostic patch applied")

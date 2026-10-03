package com.example.panels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareApiErrorTest {
    @Test fun workerCompileErrorIsSpecificAndSafe() {
        val token = "private-bearer-token"
        val json = """{"success":false,"errors":[{"code":10021,"message":"Worker syntax error; hidden $token"}]}"""
        val output = PanelProvisioner.cloudflareFailureMessage(400, json, token, "BPB Worker upload")
        assertTrue(output.contains("HTTP 400"))
        assertTrue(output.contains("[10021]"))
        assertTrue(output.contains("Worker syntax error"))
        assertTrue(output.contains("rejected the Worker JavaScript"))
        assertFalse(output.contains(token))
        assertFalse(output.contains("check this token's Workers Scripts Edit"))
    }

    @Test fun missingPermissionHasRelevantHint() {
        val json = """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
        val output = PanelProvisioner.cloudflareFailureMessage(403, json, "secret", "BPB Worker upload")
        assertTrue(output.contains("[10000]"))
        assertTrue(output.contains("Workers Scripts Edit"))
    }

    @Test fun malformedBodyIsNotEchoed() {
        val body = "html with secret-token"
        val output = PanelProvisioner.cloudflareFailureMessage(400, body, "secret-token", "BPB Worker upload")
        assertTrue(output.contains("Cloudflare did not provide error details"))
        assertFalse(output.contains("secret-token"))
    }
}

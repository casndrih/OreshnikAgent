package com.example.oreshik

import com.example.oreshik.logging.ToolAuditLog
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolAuditLogTest {
    @Test
    fun redactsSecretFieldsRecursively() {
        val input = JsonParser.parseString(
            """{"api_key":"secret-value","nested":{"password":"pass-value"},"safe":"hello"}"""
        )
        val output = ToolAuditLog.redactArgumentsForTesting(input).toString()
        assertFalse(output.contains("secret-value"))
        assertFalse(output.contains("pass-value"))
        assertEquals(true, output.contains("[REDACTED]"))
        assertEquals(true, output.contains("hello"))
    }

    @Test
    fun redactsInlineCredentialsAndBearerTokens() {
        val input = JsonParser.parseString(
            """{"command":"curl -H 'Authorization: Bearer abcdefghijklmnop' --api-key=supersecret"}"""
        )
        val output = ToolAuditLog.redactArgumentsForTesting(input).toString()
        assertFalse(output.contains("abcdefghijklmnop"))
        assertFalse(output.contains("supersecret"))
        assertEquals(true, output.contains("[REDACTED]"))
    }
}

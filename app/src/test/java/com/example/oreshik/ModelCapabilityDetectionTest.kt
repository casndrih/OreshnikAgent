package com.example.oreshik

import com.example.oreshik.ai.ModelTester
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityDetectionTest {
    @Test
    fun explicitReasoningParameterIsDetectedFromProviderMetadata() {
        val entry = JsonParser.parseString(
            """{"id":"vendor/model-x","supported_parameters":["tools","reasoning_effort"],"reasoning_effort_levels":["low","high"]}""",
        ).asJsonObject
        val capability = ModelCapabilities.reasoningCapability(entry, "vendor/model-x")
        assertTrue(capability.supported)
        assertEquals(listOf("low", "high"), capability.levels)
        assertEquals(true, capability.verified)
    }

    @Test
    fun explicitMetadataCanRejectReasoningEvenWhenModelNameLooksLikeReasoner() {
        val entry = JsonParser.parseString(
            """{"id":"deepseek-r1","supported_parameters":["tools","temperature"]}""",
        ).asJsonObject
        val capability = ModelCapabilities.reasoningCapability(entry, "deepseek-r1")
        assertEquals(false, capability.supported)
        assertEquals(listOf("off"), capability.levels)
        assertEquals(false, capability.verified)
    }

    @Test
    fun nameHeuristicIsOnlyAnUnverifiedProbeCandidate() {
        val capability = ModelCapabilities.reasoningCapability(JsonParser.parseString("{}").asJsonObject, "qwen3-thinking")
        assertTrue(capability.supported)
        assertNull(capability.verified)
    }

    @Test
    fun modelStatusDoesNotCallCredentialProblemsOffline() {
        assertEquals(
            ModelTester.ModelStatus.RESTRICTED,
            ModelTester.statusFor(Model("m", tested = false, testNote = "invalid key (HTTP 401)")),
        )
        assertEquals(
            ModelTester.ModelStatus.UNAVAILABLE,
            ModelTester.statusFor(Model("m", tested = false, testNote = "model not found on provider")),
        )
        assertEquals(
            ModelTester.ModelStatus.TIMEOUT,
            ModelTester.statusFor(Model("m", tested = false, testNote = "provider error (HTTP 503)")),
        )
        assertEquals(
            ModelTester.ModelStatus.OFFLINE,
            ModelTester.statusFor(Model("m", tested = false, testNote = "Unable to resolve host")),
        )
    }
}

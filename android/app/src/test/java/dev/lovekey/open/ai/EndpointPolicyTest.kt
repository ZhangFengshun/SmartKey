package dev.lovekey.open.ai

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun acceptsCloudAndCompatiblePrefixes() {
        assertEquals("https://api.openai.com/v1/chat/completions", endpoint(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com"))
        assertEquals("https://example.com/compatible-mode/v1/chat/completions", endpoint(ProviderKind.OPENAI_COMPATIBLE, "https://example.com/compatible-mode/v1/"))
        assertEquals("https://api.anthropic.com/v1/messages", endpoint(ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent", endpoint(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta"))
        assertEquals("http://192.168.1.2:11434/api/chat", endpoint(ProviderKind.OLLAMA, "http://192.168.1.2:11434"))
    }

    @Test fun acceptsOnlyLiteralLocalAddressesForHttp() {
        listOf("localhost", "127.0.0.1", "10.0.0.3", "172.16.0.1", "172.31.255.255", "192.168.1.2", "::1", "fd12::4").forEach {
            assertTrue("Expected local: $it", EndpointPolicy.isLocalHost(it))
        }
        listOf("example.com", "localhost.evil.test", "192.168.1.2.evil.test", "172.15.1.1", "172.32.1.1", "8.8.8.8", "256.1.1.1", "127.1", "2130706433", "010.0.0.1", "192.168.001.1", "2001:4860:4860::8888").forEach {
            assertFalse("Expected remote: $it", EndpointPolicy.isLocalHost(it))
        }
    }

    @Test fun rejectsPublicCleartextAndCredentialUrls() {
        listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret", "file:///tmp/key", "https://example.com/a/../v1", "https://example.com:0/v1").forEach { url ->
            val error = runCatching { endpoint(ProviderKind.OPENAI_COMPATIBLE, url) }.exceptionOrNull()
            assertTrue("Expected rejection: $url", error is AiException)
            assertFalse(error!!.message!!.contains("secret"))
        }
    }

    @Test fun fullEndpointsAreNotDuplicated() {
        assertEquals("https://example.com/v1/messages", endpoint(ProviderKind.ANTHROPIC, "https://example.com/v1/messages"))
        assertEquals("http://127.0.0.1:11434/api/chat", endpoint(ProviderKind.OLLAMA, "http://127.0.0.1:11434/api/chat"))
    }

    private fun endpoint(kind: ProviderKind, base: String) = EndpointPolicy.endpoint(AiSettings(provider = kind, baseUrl = base, model = "test-model")).toString()
}

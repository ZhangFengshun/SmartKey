package dev.lovekey.open.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

class ModelCatalogClientTest {
    private fun settings(kind: ProviderKind) = AiSettings(provider = kind, baseUrl = kind.defaultBaseUrl, apiKey = "catalog-test-secret", model = "")

    @Test fun catalogEndpointsDoNotRequireAModelAndPreserveCustomPrefixes() {
        assertEquals("https://api.openai.com/v1/models", EndpointPolicy.catalogEndpoint(settings(ProviderKind.OPENAI_COMPATIBLE)).toString())
        assertEquals("https://example.com/compatible-mode/v1/models", EndpointPolicy.catalogEndpoint(settings(ProviderKind.OPENAI_COMPATIBLE).copy(baseUrl = "https://example.com/compatible-mode/v1/chat/completions")).toString())
        assertEquals("/v1/models", EndpointPolicy.catalogEndpoint(settings(ProviderKind.ANTHROPIC).copy(baseUrl = "https://example.com/v1/messages")).path)
        assertEquals("/v1beta/models", EndpointPolicy.catalogEndpoint(settings(ProviderKind.GEMINI).copy(baseUrl = "https://example.com/v1beta/models/example-model:generateContent")).path)
        assertEquals("/api/tags", EndpointPolicy.catalogEndpoint(settings(ProviderKind.OLLAMA).copy(baseUrl = "http://127.0.0.1:11434/api/chat")).path)
    }

    @Test fun openAiReturnsDeduplicatedSortedIdsWithBearerInHeader() {
        val transport = CatalogTransport(listOf(data("z-model", "a-model", "a-model")))
        val result = ModelCatalogClient(transport).list(settings(ProviderKind.OPENAI_COMPATIBLE).copy(demoMode = true))
        assertEquals(listOf(AvailableModel("a-model"), AvailableModel("z-model")), result)
        assertEquals(1, transport.requests.size)
        assertEquals("Bearer catalog-test-secret", transport.requests.first().second["Authorization"])
        assertNull(transport.requests.first().first.query)
    }

    @Test fun anthropicPaginatesAndKeepsHumanReadableNames() {
        val first = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "claude-a").put("display_name", "Claude A")))
            .put("has_more", true).put("last_id", "cursor/one")
        val transport = CatalogTransport(listOf(first, data("claude-b").put("has_more", false)))
        val result = ModelCatalogClient(transport).list(settings(ProviderKind.ANTHROPIC))
        assertEquals(AvailableModel("claude-a", "Claude A"), result.first())
        assertEquals(2, result.size)
        assertEquals("limit=100&after_id=cursor%2Fone", transport.requests[1].first.query)
        assertEquals("2023-06-01", transport.requests.first().second["anthropic-version"])
        assertEquals("catalog-test-secret", transport.requests.first().second["x-api-key"])
    }

    @Test fun geminiFiltersEmbeddingOnlyModelsAndEncodesCursorOnConfiguredOrigin() {
        val first = JSONObject().put("models", JSONArray()
            .put(gemini("gemini-a", "Gemini A", "generateContent"))
            .put(gemini("embedding-only", "Embedding", "embedContent")))
            .put("nextPageToken", "https://evil.example/x?a=1&other=2")
        val second = JSONObject().put("models", JSONArray().put(gemini("gemini-b", "Gemini B", "generateContent")))
        val transport = CatalogTransport(listOf(first, second))
        val result = ModelCatalogClient(transport).list(settings(ProviderKind.GEMINI))
        assertEquals(listOf("gemini-a", "gemini-b"), result.map { it.id })
        assertEquals("Gemini A", result.first().displayName)
        assertEquals("generativelanguage.googleapis.com", transport.requests[1].first.host)
        assertTrue(transport.requests[1].first.query.contains("pageToken=https%3A%2F%2Fevil.example"))
        assertFalse(transport.requests[1].first.toString().contains("catalog-test-secret"))
        assertEquals("catalog-test-secret", transport.requests.first().second["x-goog-api-key"])
    }

    @Test fun ollamaAllowsKeylessLocalCatalogAndUsesModelOrName() {
        val response = JSONObject().put("models", JSONArray()
            .put(JSONObject().put("name", "model-a:latest").put("model", "model-a:latest"))
            .put(JSONObject().put("name", "model-b:latest")))
        val transport = CatalogTransport(listOf(response))
        val result = ModelCatalogClient(transport).list(settings(ProviderKind.OLLAMA).copy(apiKey = ""))
        assertEquals(listOf("model-a:latest", "model-b:latest"), result.map { it.id })
        assertTrue(transport.requests.first().second.isEmpty())
    }

    @Test fun unsafeUrlsFailBeforeNetworkWithoutDisclosingCredentials() {
        listOf("http://example.com/v1", "https://user:private-secret@example.com/v1", "https://example.com/v1?key=private-secret").forEach { url ->
            val transport = CatalogTransport(emptyList())
            val message = error { ModelCatalogClient(transport).list(settings(ProviderKind.OPENAI_COMPATIBLE).copy(baseUrl = url)) }.message!!
            assertFalse(message.contains("private-secret"))
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun cloudRequiresAKeyButDoesNotRequireModel() {
        listOf(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.ANTHROPIC, ProviderKind.GEMINI).forEach { kind ->
            val transport = CatalogTransport(emptyList())
            assertTrue(error { ModelCatalogClient(transport).list(settings(kind).copy(apiKey = "")) }.message!!.contains("API Key"))
            assertTrue(transport.requests.isEmpty())
        }
        val local = CatalogTransport(listOf(data("local-model")))
        assertEquals("local-model", ModelCatalogClient(local).list(settings(ProviderKind.OPENAI_COMPATIBLE).copy(baseUrl = "http://192.168.1.2:8000/v1", apiKey = "")).single().id)
    }

    @Test fun malformedEmptyAndUnavailableCatalogsDoNotFabricateModels() {
        listOf(JSONObject().put("error", "catalog-test-secret raw server debug"), data()).forEach { response ->
            val error = error { ModelCatalogClient(CatalogTransport(listOf(response))).list(settings(ProviderKind.OPENAI_COMPATIBLE)) }
            assertFalse(error.message!!.contains("catalog-test-secret"))
            assertFalse(error.message!!.contains("server debug"))
        }
        val transport = object : HttpTransport {
            override fun post(call: ProviderCall): JSONObject = throw AssertionError("Catalog must use GET")
            override fun get(url: URL, headers: Map<String, String>): JSONObject = throw AiException("模型服务拒绝访问，请检查 API Key 和账号权限。")
        }
        assertTrue(error { ModelCatalogClient(transport).list(settings(ProviderKind.OPENAI_COMPATIBLE).copy(demoMode = true)) }.message!!.contains("拒绝访问"))
    }

    @Test fun repeatedCursorStopsInsteadOfLooping() {
        val response = data("claude-a").put("has_more", true).put("last_id", "repeated")
        val transport = CatalogTransport(listOf(response, response))
        assertTrue(error { ModelCatalogClient(transport).list(settings(ProviderKind.ANTHROPIC)) }.message!!.contains("重复"))
        assertEquals(2, transport.requests.size)
    }

    @Test fun paginationAndModelTotalsAreBounded() {
        val pages = (0 until ModelCatalogClient.MAX_PAGES).map { index ->
            data("model-$index").put("has_more", true).put("last_id", "cursor-$index")
        }
        val transport = CatalogTransport(pages)
        assertEquals(ModelCatalogClient.MAX_PAGES, ModelCatalogClient(transport).list(settings(ProviderKind.ANTHROPIC)).size)
        assertEquals(ModelCatalogClient.MAX_PAGES, transport.requests.size)
        val many = JSONObject().put("data", JSONArray().apply { repeat(2_100) { put(JSONObject().put("id", "model-$it")) } })
        assertEquals(ModelCatalogClient.MAX_MODELS, ModelCatalogClient(CatalogTransport(listOf(many))).list(settings(ProviderKind.OPENAI_COMPATIBLE)).size)
    }

    @Test fun invalidEntriesAreSkippedAndInvalidDisplayNamesFallBackToId() {
        val response = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "valid-model").put("display_name", "invalid\nname"))
            .put(JSONObject().put("id", "invalid\nid"))
            .put(JSONObject().put("id", 123)).put("not an object"))
        assertEquals(listOf(AvailableModel("valid-model")), ModelCatalogClient(CatalogTransport(listOf(response))).list(settings(ProviderKind.ANTHROPIC)))
    }

    @Test fun oversizedOrKeyContainingPaginationTokensAreRejected() {
        val next = data("claude-a").put("has_more", true).put("last_id", "x".repeat(2_049))
        val transport = CatalogTransport(listOf(next))
        assertTrue(error { ModelCatalogClient(transport).list(settings(ProviderKind.ANTHROPIC)) }.message!!.contains("分页"))
        assertEquals(1, transport.requests.size)
        val keyToken = settings(ProviderKind.GEMINI).apiKey
        val response = JSONObject().put("models", JSONArray().put(gemini("gemini-a", "A", "generateContent"))).put("nextPageToken", keyToken)
        val withKey = CatalogTransport(listOf(response))
        val message = error { ModelCatalogClient(withKey).list(settings(ProviderKind.GEMINI)) }.message!!
        assertFalse(message.contains(keyToken))
        assertEquals(1, withKey.requests.size)
    }

    private fun data(vararg ids: String) = JSONObject().put("data", JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } })
    private fun gemini(id: String, displayName: String, method: String) = JSONObject().put("name", "models/$id").put("displayName", displayName)
        .put("supportedGenerationMethods", JSONArray().put(method))
    private fun error(action: () -> Unit): AiException {
        val result = runCatching(action).exceptionOrNull()
        assertTrue("Expected safe AiException", result is AiException)
        return result as AiException
    }
    private class CatalogTransport(private val pages: List<JSONObject>) : HttpTransport {
        val requests = mutableListOf<Pair<URL, Map<String, String>>>()
        override fun post(call: ProviderCall): JSONObject = throw AssertionError("Catalog must use GET")
        override fun get(url: URL, headers: Map<String, String>): JSONObject {
            requests.add(url to headers)
            return pages[requests.lastIndex]
        }
    }
}

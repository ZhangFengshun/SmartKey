package dev.lovekey.open.validation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.ai.*
import dev.lovekey.open.data.SettingsRepository
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in real-model benchmark. Reports counts/timing only, never credentials or content. */
@RunWith(AndroidJUnit4::class)
class GenerationLatencyInstrumentedTest {
    @Test fun compareThinkingModes() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("latencyBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context).load()
        val request = ReplyRequest("今天真的好累，想早点休息。")
        val prompt = PromptComposer.compose(settings, request)
        for (mode in listOf("baseline", "fast", "baseline", "fast")) {
            val call = ProviderAdapters.forKind(settings.provider).build(settings, prompt)
            if (mode == "baseline") { call.body.remove("thinking"); call.body.remove("enable_thinking") }
            if (mode == "fast") {
                call.body.put("thinking", JSONObject().put("type", "disabled"))
                call.body.put("enable_thinking", false)

            }
            val start = System.nanoTime()
            val response = UrlConnectionTransport().post(call)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val usage = response.optJSONObject("usage")
            android.util.Log.i("SmartKeyLatency", "mode=$mode elapsedMs=$elapsed reasoningChars=${message.optString("reasoning_content").length} outputChars=${message.optString("content").length} completionTokens=${usage?.optInt("completion_tokens", -1)}")
            AiClient().parseCandidates(ProviderAdapters.forKind(settings.provider).read(response))
        }
    }
}

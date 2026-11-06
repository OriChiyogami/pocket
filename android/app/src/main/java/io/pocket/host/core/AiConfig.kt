package io.pocket.host.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 模型配置与调用。
 *
 * 两种入口：
 *  - DeepSeek：只需填 API Key（地址固定 https://api.deepseek.com，模型默认 deepseek-chat）
 *  - 其他 / 中转站：OpenAI 兼容格式，填 baseUrl + key + model
 *
 * 页面侧通过 `pocket.ai.chat()` 调用，不各自配置 Key。
 */
object AiConfig {

    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_CUSTOM = "custom"

    private const val PREFS = "pocket"
    private const val K_PROVIDER = "ai_provider"
    private const val K_DS_KEY = "ai_ds_key"
    private const val K_DS_MODEL = "ai_ds_model"
    private const val K_CU_BASE = "ai_cu_base"
    private const val K_CU_KEY = "ai_cu_key"
    private const val K_CU_MODEL = "ai_cu_model"

    data class Settings(
        val provider: String,
        val deepseekKey: String,
        val deepseekModel: String,
        val customBase: String,
        val customKey: String,
        val customModel: String
    ) {
        fun active(): Active? = when (provider) {
            PROVIDER_CUSTOM -> if (customBase.isNotBlank() && customKey.isNotBlank()) {
                Active(customBase.trim().trimEnd('/'), customKey.trim(), customModel.trim(), PROVIDER_CUSTOM)
            } else null

            else -> if (deepseekKey.isNotBlank()) {
                Active(
                    "https://api.deepseek.com",
                    deepseekKey.trim(),
                    deepseekModel.trim().ifBlank { "deepseek-chat" },
                    PROVIDER_DEEPSEEK
                )
            } else null
        }

        fun summary(): String = when {
            active() == null -> "未配置"
            provider == PROVIDER_DEEPSEEK -> "DeepSeek · ${deepseekModel.trim().ifBlank { "deepseek-chat" }}"
            else -> customModel.trim().ifBlank { "自定义模型" }
        }
    }

    data class Active(val baseUrl: String, val key: String, val model: String, val provider: String)

    /** 一次回复：正文 + 可选的思考过程（部分模型/中转站会单独给 reasoning_content） */
    data class Reply(val text: String, val reasoning: String = "")

    class AiException(message: String) : Exception(message)

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            provider = p.getString(K_PROVIDER, PROVIDER_DEEPSEEK) ?: PROVIDER_DEEPSEEK,
            deepseekKey = p.getString(K_DS_KEY, "").orEmpty(),
            deepseekModel = p.getString(K_DS_MODEL, "deepseek-chat").orEmpty(),
            customBase = p.getString(K_CU_BASE, "").orEmpty(),
            customKey = p.getString(K_CU_KEY, "").orEmpty(),
            customModel = p.getString(K_CU_MODEL, "").orEmpty()
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_PROVIDER, s.provider)
            .putString(K_DS_KEY, s.deepseekKey.trim())
            .putString(K_DS_MODEL, s.deepseekModel.trim())
            .putString(K_CU_BASE, s.customBase.trim())
            .putString(K_CU_KEY, s.customKey.trim())
            .putString(K_CU_MODEL, s.customModel.trim())
            .apply()
    }

    fun active(context: Context): Active? = load(context).active()

    fun available(context: Context): Boolean = active(context) != null

    /**
     * 发起一次对话，**阻塞**调用，需放在子线程。
     * @param messages 角色到内容的列表（role: system / user / assistant）
     */
    fun chat(
        context: Context,
        messages: List<Pair<String, String>>,
        system: String? = null,
        temperature: Double = 0.7,
        maxTokens: Int = 0
    ): Reply {
        val cfg = active(context) ?: throw AiException("还没有配置 AI 模型，去 设置 → AI 模型 填一下")

        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("stream", false)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                if (!system.isNullOrBlank()) {
                    put(JSONObject().put("role", "system").put("content", system))
                }
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role.ifBlank { "user" }).put("content", content))
                }
            })
            if (maxTokens > 0) put("max_tokens", maxTokens)
        }

        val connection = try {
            (URL(cfg.baseUrl + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${cfg.key}")
            }
        } catch (e: Throwable) {
            throw AiException("连接失败：${e.message}")
        }

        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw AiException("AI 接口返回 $code：${text.take(180).ifBlank { "无响应内容" }}")
            }
            val message = runCatching {
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            }.getOrNull()

            var content = message?.optString("content").orEmpty()
            var reasoning = message?.optString("reasoning_content").orEmpty()
            if (reasoning.isBlank()) reasoning = message?.optString("reasoning").orEmpty()
            if (reasoning.isBlank()) reasoning = message?.optString("thinking").orEmpty()

            // 有些中转站会把思考过程直接写进 content：<thinking>…</thinking>
            val tag = Regex("<thinking>([\\s\\S]*?)</thinking>", RegexOption.IGNORE_CASE)
            val tagHit = tag.find(content)
            if (tagHit != null) {
                if (reasoning.isBlank()) reasoning = tagHit.groupValues[1].trim()
                content = tag.replace(content, "").trim()
            }

            if (content.isBlank() && reasoning.isBlank()) {
                throw AiException("AI 返回为空，检查模型名或额度")
            }
            return Reply(text = content.trim(), reasoning = reasoning.trim())
        } finally {
            connection.disconnect()
        }
    }
}

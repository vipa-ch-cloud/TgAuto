package com.v1pach.telescript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

/**
 * Nightly: which AI backend scripts may use. The choice is turned into things scripts already
 * understand: the claude/gemini/codex shims on PATH, the providers file (TELESCRIPT_LLM_PROVIDERS_FILE,
 * same format, "order" as chosen) and ANTHROPIC_API_KEY. TELESCRIPT_LLM_BACKEND additionally carries
 * the chain in automod's "backend" vocabulary (cli, gemini, providers, <provider id>, api).
 */
object FlavorHooks {
    const val managesBackend = true

    fun cliOrder(pref: String): String = AiBackend.cliOrder(AiBackend.plan(pref))

    fun codexOn(): Boolean = "codex" in AiBackend.plan(AppSettings.llmOrder.value)

    @Composable
    fun codexState(pref: String): Boolean {
        cliOrderState(pref) // subscribes to the same preferences
        val on by AiBackend.codexOn.flow.collectAsStateWithLifecycle()
        val mode by AiBackend.mode.flow.collectAsStateWithLifecycle()
        return "codex" in AiBackend.plan(pref, mode, AiBackend.chain.value, AiBackend.off.value, AiBackend.provider.value,
            Providers.list.value.map { it.id }, on)
    }

    @Composable
    fun cliOrderState(pref: String): String {
        val mode by AiBackend.mode.flow.collectAsStateWithLifecycle()
        val chain by AiBackend.chain.flow.collectAsStateWithLifecycle()
        val off by AiBackend.off.flow.collectAsStateWithLifecycle()
        val provider by AiBackend.provider.flow.collectAsStateWithLifecycle()
        val list by Providers.list.collectAsStateWithLifecycle()
        val codex by AiBackend.codexOn.flow.collectAsStateWithLifecycle()
        return AiBackend.cliOrder(AiBackend.plan(pref, mode, chain, off, provider, list.map { it.id }, codex))
    }

    fun providersFile(): String = Providers.writeEffective(AiBackend.providerIds(AiBackend.plan(AppSettings.llmOrder.value)))

    fun apiKey(raw: String): String = if ("api" in AiBackend.plan(AppSettings.llmOrder.value)) raw else ""

    fun putEnv(env: JSONObject) {
        env.put("TELESCRIPT_LLM_BACKEND", AiBackend.scriptNames(AiBackend.plan(AppSettings.llmOrder.value)).joinToString(","))
    }
}

/** Selection model: mode auto | claude | gemini | provider, and for auto an ordered chain with switches. */
object AiBackend {
    val KINDS = listOf("claude", "gemini", "codex", "providers", "api")

    /** "" = not chosen yet: inherit the old LLM order setting so an upgrade keeps behaving the same. */
    val mode = AppSettings.Pref("ai_mode", "")
    val provider = AppSettings.Pref("ai_provider", "")
    val chain = AppSettings.Pref("ai_chain", KINDS.joinToString(","))
    val off = AppSettings.Pref("ai_off", "")
    /** Codex joins the Auto chain only when switched on (off by default, so upgrades behave as before). */
    val codexOn = AppSettings.Pref("ai_codex_on", false)

    fun normalizedChain(raw: String): List<String> {
        val given = raw.split(',').map { it.trim() }.filter { it in KINDS }.distinct()
        return given + KINDS.filter { it !in given }
    }

    fun plan(legacyOrder: String): List<String> = plan(legacyOrder, mode.value, chain.value, off.value, provider.value,
        Providers.list.value.map { it.id })

    /** Ordered backends in use: claude, gemini, providers, provider:<id>, api. */
    fun plan(legacyOrder: String, mode: String, chain: String, off: String, provider: String, ids: List<String>,
             codexOn: Boolean = AiBackend.codexOn.value): List<String> {
        var m = mode
        var disabled = off.split(',').map { it.trim() }.toSet()
        if (!codexOn) disabled = disabled + "codex"
        if (m.isEmpty()) { // legacy llm_order: both | claude | gemini | api
            m = when (legacyOrder) { "claude" -> "claude"; "gemini" -> "gemini"; else -> "auto" }
            if (legacyOrder == "api") disabled = setOf("claude", "gemini")
        }
        return when (m) {
            "claude" -> listOf("claude")
            "gemini" -> listOf("gemini")
            "codex" -> listOf("codex")
            "provider" -> if (provider in ids) listOf("provider:$provider") else normalizedChain(chain).filter { it !in disabled }
            else -> normalizedChain(chain).filter { it !in disabled }
        }
    }

    fun cliOrder(plan: List<String>): String {
        val c = "claude" in plan
        val g = "gemini" in plan
        return when { c && g -> "both"; c -> "claude"; g -> "gemini"; "codex" in plan -> "codex"; else -> "api" }
    }

    fun providerIds(plan: List<String>): List<String> = when {
        "providers" in plan -> Providers.list.value.map { it.id }
        else -> plan.filter { it.startsWith("provider:") }.map { it.removePrefix("provider:") }
    }

    /** Names in automod's "backend" vocabulary. */
    fun scriptNames(plan: List<String>): List<String> = plan.map {
        when {
            it == "claude" -> "cli"
            it.startsWith("provider:") -> it.removePrefix("provider:")
            else -> it
        }
    }

    fun move(kind: String, delta: Int) {
        val l = normalizedChain(chain.value).toMutableList()
        val i = l.indexOf(kind)
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        ensureChosen(); chain.set(l.joinToString(","))
    }

    fun setEnabled(kind: String, on: Boolean) {
        ensureChosen()
        if (kind == "codex") { codexOn.set(on); return }
        val s = off.value.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (on) s.remove(kind) else s.add(kind)
        off.set(s.joinToString(","))
    }

    /** First explicit edit pins the legacy value so later edits do not change meaning. */
    fun ensureChosen() {
        if (mode.value.isEmpty()) {
            val legacy = AppSettings.llmOrder.value
            if (legacy == "api") off.set("claude,gemini")
            mode.set(when (legacy) { "claude" -> "claude"; "gemini" -> "gemini"; else -> "auto" })
        }
    }

    fun select(newMode: String) { ensureChosen(); mode.set(newMode) }
}

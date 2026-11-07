package com.v1pach.telescript.store

/**
 * Best-effort static scan of a script's Python sources, shown before installation. It cannot prove a
 * script is safe (code can build strings at runtime); it surfaces what is visible so the user can judge.
 */
data class ScanResult(
    val hosts: List<String>,
    val risky: List<String>,
    val telegram: List<String>,
)

object ScriptScan {
    private val URL = Regex("""https?://([A-Za-z0-9.\-]+)""")
    private val RISKY = mapOf(
        "subprocess" to Regex("""\b(import\s+subprocess|from\s+subprocess)"""),
        "os.system / popen" to Regex("""\bos\.(system|popen|exec\w*|spawn\w*)\s*\("""),
        "eval / exec" to Regex("""(?<![\w.])(eval|exec)\s*\("""),
        "ctypes" to Regex("""\b(import\s+ctypes|from\s+ctypes)"""),
        "pickle / marshal" to Regex("""\b(import\s+(pickle|marshal)|from\s+(pickle|marshal))"""),
        "dynamic import" to Regex("""\b(__import__\s*\(|importlib)"""),
        "raw sockets" to Regex("""\b(import\s+socket|from\s+socket)"""),
        "file deletion" to Regex("""\b(shutil\.rmtree|os\.(remove|unlink|rmdir)\s*\()"""),
        "pip install at runtime" to Regex("""pip['" ,\]]+install"""),
    )
    private val TELEGRAM = mapOf(
        "send_message" to Regex("""\.(send_message|send_file|forward_messages)\s*\("""),
        "delete_messages" to Regex("""\.delete_messages\s*\("""),
        "ban / kick / admin" to Regex("""\.(edit_permissions|kick_participant|edit_admin|EditBannedRequest)\b"""),
        "join / leave" to Regex("""(JoinChannelRequest|LeaveChannelRequest|ImportChatInviteRequest)"""),
    )

    fun scan(files: Map<String, ByteArray>): ScanResult {
        val hosts = sortedSetOf<String>(); val risky = sortedSetOf<String>(); val tg = sortedSetOf<String>()
        for ((path, data) in files) {
            if (!path.endsWith(".py")) continue
            val text = String(data, Charsets.UTF_8)
            URL.findAll(text).forEach { hosts += it.groupValues[1].lowercase() }
            RISKY.forEach { (name, re) -> if (re.containsMatchIn(text)) risky += name }
            TELEGRAM.forEach { (name, re) -> if (re.containsMatchIn(text)) tg += name }
        }
        return ScanResult(hosts.toList(), risky.toList(), tg.toList())
    }
}

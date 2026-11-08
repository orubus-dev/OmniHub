package com.omnihub.source.core

/**
 * Strip thinking + provider chrome so the bubble is the answer only.
 *
 * v1.3.0 — NON-DESTRUCTIVE BY DESIGN.
 *
 * The old version carried a blocklist of chrome *phrases* ("chat", "terms of
 * service", ".*can make mistakes.*", product names…). Any real reply that
 * happened to contain those words — a line starting with "Chat is a data
 * structure…", a genuine "Analysis:" section, a reply to an AI-safety
 * question — was silently deleted, and the caller then reported
 * REPLY_TIMEOUT while the answer was sitting on screen. That was a word ban,
 * and it is gone.
 *
 * What remains is STRUCTURAL only:
 *  - <think>/<reasoning> tags and their fenced equivalents (markup, not words)
 *  - lines that ARE positional role labels ("You said:", "ChatGPT said:")
 *  - standalone timestamp lines
 * Content is never matched, never banned, never dropped for what it says.
 * Visual chrome (action bars, footers, buttons) is removed upstream by the
 * engine's DOM-level extractReplyText — structure, not text matching.
 */
object ReplySanitizer {

    private val BLOCK_TAGS = Regex(
        "(?is)<\\s*(?:think(?:ing)?|reasoning|thought|analysis|scratchpad)\\b[^>]*>.*?</\\s*(?:think(?:ing)?|reasoning|thought|analysis|scratchpad)\\s*>"
    )
    private val FENCE = Regex(
        "(?is)```(?:thinking|reasoning|thought|analysis|internal)[\\s\\S]*?```"
    )
    private val HEADER_BLOCK = Regex(
        "(?im)^(?:thought process|thinking|reasoning|chain of thought|internal monologue|scratchpad)\\s*[:\\-]?\\s*\\n(?:.*\\n)*?(?=\\n\\n|\\z)"
    )
    private val HEADER_LINE = Regex(
        "(?im)^(?:thought process|thinking|reasoning|chain of thought|internal monologue)\\s*[:\\-]?\\s*$"
    )
    private val INLINE_LEAD = Regex(
        "(?is)^\\s*(?:thought process|thinking|reasoning)\\s*[:\\-]\\s*"
    )

    /** Positional role labels — known role names only, never content words. */
    private val ROLE_LABEL = Regex(
        """(?i)^(?:you|user|assistant|chatgpt|gemini|claude|perplexity|grok|copilot|meta\s+ai|deepseek|duck\.ai|duckduckgo)\s+said\b.*$"""
    )
    private val YOU_SAID_BLOCK = Regex(
        "(?im)^(?:You said|Gemini said|ChatGPT said|Claude said|Assistant said|Ask a follow-up)\\s*:?\\s*.*$"
    )
    /** Standalone clock lines — chrome, not content. */
    private val TIMESTAMP_LINE = Regex(
        """(?i)^\d{1,2}:\d{2}\s*(?:am|pm)?$"""
    )

    fun strip(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        t = BLOCK_TAGS.replace(t, "")
        t = FENCE.replace(t, "")
        t = HEADER_BLOCK.replace(t, "")
        t = t.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { HEADER_LINE.matches(it) }
            .filterNot { ROLE_LABEL.matches(it) }
            .filterNot { YOU_SAID_BLOCK.matches(it) }
            .filterNot { TIMESTAMP_LINE.matches(it) }
            .joinToString("\n")
        t = INLINE_LEAD.replace(t, "")
        t = t.replace(Regex("\n{3,}"), "\n\n").trim()
        return t
    }

    /**
     * Cut everything before the final occurrence of the user's message (the
     * transcript echo), then strip structurally. The old <400-chars
     * "keep only last paragraph" collapse is GONE — it chopped real answers.
     */
    fun lastTurnOnly(raw: String, userMessage: String): String {
        var t = strip(raw)
        val um = userMessage.trim()
        if (um.isNotEmpty()) {
            val idx = t.lastIndexOf(um)
            if (idx >= 0) {
                t = t.substring(idx + um.length).trim()
                if (t.startsWith(um)) t = t.removePrefix(um).trim()
            }
        }
        return strip(t)
    }
}

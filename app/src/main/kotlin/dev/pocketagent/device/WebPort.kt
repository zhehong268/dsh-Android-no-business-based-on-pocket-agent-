package dev.pocketagent.device

/**
 * The web reads the agent may do: search, and fetch one page as text. Read only, so no confirmation card is needed —
 * nothing here can post, buy or delete.
 */
interface WebPort {
    val available: Boolean

    /** Results for [query], already trimmed to a handful of items and plain text. */
    suspend fun search(query: String, max: Int): WebResult

    /** The readable text of one page, capped at [maxChars]. */
    suspend fun fetch(url: String, maxChars: Int): WebResult
}

sealed interface WebResult {
    data class Ok(val text: String) : WebResult
    data class Failed(val reason: String) : WebResult
}

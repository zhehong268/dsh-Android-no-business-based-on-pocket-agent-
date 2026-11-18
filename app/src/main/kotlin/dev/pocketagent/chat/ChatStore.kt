package dev.pocketagent.chat

import dev.pocketagent.setup.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class ChatMessage(val role:String,val text:String)

/** One private text conversation. Never stores a key, screenshot, tool result or reasoning block. */
class ChatStore(private val values:KeyValueStore) {
    private val json=Json { ignoreUnknownKeys=true }
    fun load():List<ChatMessage> = try { values.getString(KEY)?.let { bounded(json.decodeFromString<List<ChatMessage>>(it)) }.orEmpty() } catch(_:Exception) { emptyList() }
    fun save(messages:List<ChatMessage>) { values.putString(KEY,json.encodeToString(bounded(messages))) }
    fun clear() { values.putString(KEY,null) }
    companion object {
        const val KEY="chat.messages.v1"
        const val MAX_MESSAGES=40
        const val MAX_CHARS=128_000
        fun bounded(messages:List<ChatMessage>):List<ChatMessage> {
            val kept=messages.filter { it.role in setOf("user","assistant") && it.text.isNotBlank() }
                .map { it.copy(text=it.text.take(64_000)) }.takeLast(MAX_MESSAGES).toMutableList()
            while(kept.size>1 && (kept.first().role!="user" || kept.sumOf { it.text.length }>MAX_CHARS))kept.removeAt(0)
            if(kept.firstOrNull()?.role!="user")return emptyList()
            return kept
        }
    }
}

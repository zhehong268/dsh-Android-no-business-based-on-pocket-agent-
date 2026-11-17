package dev.pocketagent.agent

import dev.pocketagent.setup.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaskProgress(
    val task: String,
    val goal: String = "",
    val completed: String = "",
    val next: String = "",
    val expected: String = "",
    val steps: Int = 0,
    val trail: String = "",
    val packageName: String = "",
    val uncertainAction: Boolean = false,
)

/** Private app storage, independent of SAF. No screen dumps, screenshots, keys or reasoning are persisted. */
class ProgressStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): TaskProgress? = try { store.getString(KEY)?.let { json.decodeFromString<TaskProgress>(it) } } catch (_: Exception) { null }
    fun save(progress: TaskProgress) { store.putString(KEY, json.encodeToString(TaskProgress.serializer(), progress)) }
    fun clear() { store.putString(KEY, null) }
    companion object { const val KEY = "task.progress.v1" }
}

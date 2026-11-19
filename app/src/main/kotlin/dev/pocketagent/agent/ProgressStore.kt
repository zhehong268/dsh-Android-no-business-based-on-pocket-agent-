package dev.pocketagent.agent

import dev.pocketagent.setup.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    val pendingMessage: Boolean = false,
    val messageReceipt: PendingMessageReceipt? = null,
)

/** Private app storage, independent of SAF. No screen dumps, screenshots, keys or reasoning are persisted. */
class ProgressStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): TaskProgress? = try { store.getString(KEY)?.let { json.decodeFromString<TaskProgress>(it) } } catch (_: Exception) { null }
    private fun archived():List<TaskProgress> = try { store.getString(ARCHIVE)?.let { json.decodeFromString<List<TaskProgress>>(it) }.orEmpty() } catch(_:Exception) { emptyList() }
    fun requiredTask(): String? = store.getString(REQUIRED)?.takeIf(String::isNotBlank)
    fun requireContinuation(task: String) = store.putString(REQUIRED, task)
    fun releaseContinuation(task: String) {
        if (requiredTask() == task) store.putString(REQUIRED, null)
    }
    fun load(task:String):TaskProgress? = load()?.takeIf { it.task==task } ?: archived().firstOrNull { it.task==task }
    fun save(progress: TaskProgress) {
        val entries=(listOfNotNull(load())+archived()).filter { it.task!=progress.task }.distinctBy { it.task }.take(11)
        store.putString(ARCHIVE,json.encodeToString(listOf(progress)+entries))
        store.putString(KEY, json.encodeToString(TaskProgress.serializer(), progress))
    }
    fun clear(task:String?=null) {
        val target=task ?: load()?.task
        val remaining=archived().filter { it.task!=target }
        store.putString(ARCHIVE,json.encodeToString(remaining))
        if(task==null || load()?.task==target)store.putString(KEY,remaining.firstOrNull()?.let { json.encodeToString(it) })
    }
    companion object {
        private const val REQUIRED = "task.progress.required.v1"
        const val KEY = "task.progress.v1"
        private const val ARCHIVE = "task.progress.recent.v1"
        fun isContinuation(text:String)=text.trim().trimEnd('。','！','!') in setOf("继续","继续任务","继续执行","接着做","继续上个任务")
    }
}

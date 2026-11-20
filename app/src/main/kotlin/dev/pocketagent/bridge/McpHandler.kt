package dev.pocketagent.bridge

import dev.pocketagent.agent.Tools
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How a request from the computer ended. [Refused] means nothing ran. */
sealed interface TaskReply {
    data class Done(val success: Boolean, val summary: String,val metrics:Map<String,Long> = emptyMap()) : TaskReply
    data class Refused(val reason: String) : TaskReply
}

/** What the bridge needs from the phone: ask its owner, run the task, say how it went. */
fun interface PhoneTasks {
    suspend fun run(task: String): TaskReply
}

/**
 * The Model Context Protocol, the small part of it a phone needs: one tool, `run_task`. It speaks JSON-RPC and
 * validates every field, because the sender is another machine on the network. It never reads the screen and has no
 * other tool, so a paired computer can ask for a task but can never see or tap anything on its own.
 */
fun interface PhoneControl {
    suspend fun call(name: String, arguments: JsonObject): JsonObject
}

class McpHandler(private val control: PhoneControl? = null, private val tasks: PhoneTasks) {
    constructor(tasks: PhoneTasks) : this(null,tasks)
    private val json = Json { ignoreUnknownKeys = true }

    /** The JSON-RPC response for [body], or null for a notification, which gets no reply. */
    suspend fun handle(body: String): String? {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return failure(null, PARSE_ERROR, "Parse error")
        }
        // Batches are not part of the protocol revisions this supports.
        val request = element as? JsonObject ?: return failure(null, INVALID_REQUEST, "Invalid request")
        val id = request["id"]
        if (id != null && !(id is JsonPrimitive && id !is JsonNull)) return failure(null, INVALID_REQUEST, "Invalid id")
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure(id, INVALID_REQUEST, "Invalid request")
        // A request without an id is a notification: nothing is sent back, whatever it asked.
        if (id == null) return null
        return when (method) {
            "initialize" -> success(id, initializeResult(request["params"]))
            "ping" -> success(id, buildJsonObject {})
            "tools/list" -> success(id, toolList())
            "tools/call" -> call(id, request["params"])
            else -> failure(id, METHOD_NOT_FOUND, "Method not found")
        }
    }

    private fun initializeResult(params: JsonElement?): JsonObject {
        val asked = ((params as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", if (asked in SUPPORTED) asked else LATEST)
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", "pocket-agent")
                put("version", "0.1.0")
            }
            put(
                "instructions",
                "This is a phone. run_task asks its owner to approve a task, then does it in the phone's apps. " +
                    "It can take a few minutes, and the phone may refuse if it is locked or busy." +
                    (if (control != null) " For direct control call control_start, then use the returned current screen; stop with control_stop." else ""),
            )
        }
    }

    private fun toolList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            if (control != null) controlTools().forEach { add(it) }
            add(
                buildJsonObject {
                    put("name", TOOL)
                    put(
                        "description",
                        "Ask the phone to do something in its apps, for example 'play lofi on YouTube'. The phone's owner " +
                            "is asked to approve every request on the phone first, and anything that sends, calls or pays is " +
                            "confirmed again. Returns what happened.",
                    )
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("task") {
                                put("type", "string")
                                put("description", "What to do, in plain words ($MAX_TASK characters at most).")
                            }
                        }
                        putJsonArray("required") { add("task") }
                        put("additionalProperties", false)
                    }
                },
            )
        }
    }

    private suspend fun call(id: JsonElement, params: JsonElement?): String {
        val p = params as? JsonObject ?: return failure(id, INVALID_PARAMS, "Invalid params")
        val name = (p["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name != TOOL) {
            val port = control ?: return failure(id, INVALID_PARAMS, "Unknown tool")
            val args = p["arguments"] as? JsonObject ?: return failure(id, INVALID_PARAMS, "Missing arguments")
            val definition = controlTools().firstOrNull { it["name"] == JsonPrimitive(name) }
                ?: return failure(id, INVALID_PARAMS, "Unknown tool")
            if (name in CONTROL_ACTIONS && Tools.parse(name!!,args).isFailure) return failure(id, INVALID_PARAMS, "Invalid tool arguments")
            if ("finish_if_verified" in args) return failure(id, INVALID_PARAMS, "直接控制请用 control_stop 结束，不使用 finish_if_verified。")
            if (name !in CONTROL_ACTIONS && (args.keys.any { it != "task" } || (name != "control_start" && args.isNotEmpty()))) return failure(id,INVALID_PARAMS,"Unexpected argument")
            if (name == "control_start") {
                val task = (args["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (task.isNullOrBlank() || task.length > MAX_TASK || task.any { it.isISOControl() && it != '\n' }) return failure(id,INVALID_PARAMS,"Invalid task")
            }
            return success(id,port.call(name!!,args))
        }
        val args = p["arguments"] as? JsonObject ?: return failure(id, INVALID_PARAMS, "Missing arguments")
        if (args.keys.any { it != "task" }) return failure(id, INVALID_PARAMS, "Unexpected argument")
        val task = (args["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (task.isNullOrEmpty() || task.length > MAX_TASK || task.any { it.isISOControl() && it != '\n' }) {
            return failure(id, INVALID_PARAMS, "task must be 1 to $MAX_TASK characters of plain text")
        }
        return when (val reply = tasks.run(task)) {
            is TaskReply.Done -> success(id, if(reply.metrics.isEmpty())textResult(reply.summary, isError = !reply.success) else JsonObject(textResult(reply.summary,isError=!reply.success).toMutableMap().apply {
                put("_meta",buildJsonObject { putJsonObject("dsh"){reply.metrics.forEach { (name,value)->put(name,value) }} })
            }))
            is TaskReply.Refused -> success(id, textResult(reply.reason, isError = true))
        }
    }

    private fun controlTools(): List<JsonObject> {
        val controls = listOf("control_start" to "申请直接操作手机，须手机主人批准，空闲两分钟自动结束。", "control_stop" to "结束电脑控制。", "control_status" to "查看当前控制状态。")
            .map { (name,description) -> buildJsonObject {
                put("name",name); put("description",description)
                putJsonObject("inputSchema") {
                    put("type","object"); put("additionalProperties",false)
                    putJsonObject("properties") { if (name == "control_start") putJsonObject("task") { put("type","string"); put("description","本次控制的任务目标，最多 2000 字。") } }
                    putJsonArray("required") { if(name == "control_start") add("task") }
                }
            } }
        return controls + Tools.definitions.filter { it.name in CONTROL_ACTIONS }.map {
            val schema=JsonObject(it.inputSchema.toMutableMap().apply {
                val properties=get("properties") as? JsonObject
                if(properties!=null)put("properties",JsonObject(properties.filterKeys { name->name!="finish_if_verified" }))
            })
            buildJsonObject { put("name",it.name); put("description",it.description); put("inputSchema",schema) }
        }
    }

    private fun textResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        put("isError", isError)
    }

    private fun success(id: JsonElement, result: JsonObject): String =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()

    private fun failure(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    companion object {
        val CONTROL_ACTIONS = setOf("read_ui_source","find_chat_input","font_reference","check_appearance","open_contact","fill_chat","perform_steps","zoom_screen","read_screen","screenshot","tap","tap_text","tap_mark","type","scroll","back","home","open_app","wait_for","find_elements","read_element","long_press","double_tap","drag")
        const val TOOL = "run_task"
        const val MAX_TASK = 2_000
        private const val LATEST = "2025-06-18"
        private val SUPPORTED = setOf("2024-11-05", "2025-03-26", "2025-06-18")
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
    }
}

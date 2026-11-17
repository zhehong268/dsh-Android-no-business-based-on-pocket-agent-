package dev.pocketagent.agent

import dev.pocketagent.device.Direction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolParserTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun parse(tool: String, text: String = "{}") = Tools.parse(tool, json(text))
    private fun message(result: Result<ToolCall>) = result.exceptionOrNull()?.message.orEmpty()

    private fun assertRejected(result: Result<ToolCall>, expected: String) {
        assertTrue(result.isFailure, "expected a failure but got ${result.getOrNull()}")
        assertTrue(message(result).contains(expected), "message \"${message(result)}\" should mention \"$expected\"")
    }

    // ---- tap ----

    @Test
    fun parsesTapByNode() {
        assertEquals(ToolCall.Tap("a3", null, null, false), parse("tap", """{"node_id":"a3"}""").getOrThrow())
    }

    @Test
    fun parsesTapByCoordinatesWithIrreversible() {
        assertEquals(ToolCall.Tap(null, 10, 20, true), parse("tap", """{"x":10,"y":20,"irreversible":true}""").getOrThrow())
    }

    @Test
    fun rejectsTapWithBothNodeAndCoordinates() {
        assertRejected(parse("tap", """{"node_id":"a3","x":10,"y":20}"""), "只能给出 node_id，或给出 x 和 y")
    }

    @Test
    fun rejectsTapWithNeither() {
        assertRejected(parse("tap", """{"irreversible":false}"""), "只能给出 node_id，或给出 x 和 y")
    }

    @Test
    fun rejectsTapWithOnlyOneCoordinate() {
        assertRejected(parse("tap", """{"x":10}"""), "只能给出 node_id，或给出 x 和 y")
        assertRejected(parse("tap", """{"node_id":"a3","x":10}"""), "只能给出 node_id，或给出 x 和 y")
        assertRejected(parse("tap", """{"node_id":"a3","y":10}"""), "只能给出 node_id，或给出 x 和 y")
    }

    @Test
    fun rejectsNegativeAndNonIntegerCoordinates() {
        assertRejected(parse("tap", """{"x":-1,"y":5}"""), "x 必须是")
        assertRejected(parse("tap", """{"x":10.5,"y":5}"""), "x 必须是")
        assertRejected(parse("tap", """{"x":"10","y":5}"""), "x 必须是")
    }

    @Test
    fun rejectsBadNodeIds() {
        assertRejected(parse("tap", """{"node_id":""}"""), "node_id 的长度必须为 1 到 20 个字符")
        assertRejected(parse("tap", """{"node_id":"${"a".repeat(21)}"}"""), "node_id 的长度必须为 1 到 20 个字符")
        assertRejected(parse("tap", """{"node_id":12}"""), "node_id 必须是字符串")
        assertRejected(parse("tap", """{"node_id":null}"""), "node_id 必须是字符串")
        assertRejected(parse("tap", """{"node_id":{"id":"a1"}}"""), "node_id 必须是字符串")
    }

    @Test
    fun irreversibleMustBeABoolean() {
        assertRejected(parse("tap", """{"node_id":"a1","irreversible":"true"}"""), "irreversible 必须为 true 或 false")
    }

    // ---- type, scroll, open_app, wait, ask_user, done ----

    @Test
    fun parsesTypeAndEnforcesLengths() {
        assertEquals(ToolCall.TypeText("a1", "hello"), parse("type", """{"node_id":"a1","text":"hello"}""").getOrThrow())
        assertTrue(parse("type", """{"node_id":"a1","text":"${"x".repeat(1000)}"}""").isSuccess)
        assertRejected(parse("type", """{"node_id":"a1","text":""}"""), "text")
        assertRejected(parse("type", """{"node_id":"a1","text":"${"x".repeat(4001)}"}"""), "text")
        assertRejected(parse("type", """{"text":"hello"}"""), "缺少字段：node_id")
        assertEquals(ToolCall.TypeText("a1", "milk", submit = true), parse("type", """{"node_id":"a1","text":"milk","submit":true}""").getOrThrow())
        assertRejected(parse("type", """{"node_id":"a1","text":"milk","submit":"yes"}"""), "submit 必须为 true 或 false")
    }

    @Test
    fun parsesScrollInEveryDirection() {
        mapOf("up" to Direction.UP, "down" to Direction.DOWN, "left" to Direction.LEFT, "right" to Direction.RIGHT).forEach { (word, direction) ->
            assertEquals(ToolCall.Scroll(direction, null), parse("scroll", """{"direction":"$word"}""").getOrThrow())
        }
        assertEquals(ToolCall.Scroll(Direction.DOWN, "a7"), parse("scroll", """{"direction":"down","node_id":"a7"}""").getOrThrow())
    }

    @Test
    fun rejectsBadScrollDirection() {
        assertRejected(parse("scroll", """{"direction":"diagonal"}"""), "direction 只能是以下之一：up、down、left、right")
        assertRejected(parse("scroll", """{"direction":"UP"}"""), "direction 只能是以下之一")
        assertRejected(parse("scroll", "{}"), "缺少字段：direction")
    }

    @Test
    fun parsesOpenAppAndEnforcesLength() {
        assertEquals(ToolCall.OpenApp("WhatsApp"), parse("open_app", """{"name":"WhatsApp"}""").getOrThrow())
        assertRejected(parse("open_app", """{"name":""}"""), "name 的长度必须为 1 到 100 个字符")
        assertRejected(parse("open_app", """{"name":"${"x".repeat(101)}"}"""), "name 的长度必须为 1 到 100 个字符")
    }

    @Test
    fun openAppRejectsPackageUrlAndIntentFields() {
        listOf("package", "url", "intent").forEach { field ->
            assertRejected(parse("open_app", """{"name":"WhatsApp","$field":"x"}"""), "意外字段：$field")
        }
    }

    @Test
    fun rejectsWaitOutOfRange() {
        assertRejected(parse("wait", """{"seconds":0}"""), "seconds 必须是 1 到 5 之间的整数")
        assertRejected(parse("wait", """{"seconds":6}"""), "seconds 必须是 1 到 5 之间的整数")
        assertRejected(parse("wait", """{"seconds":"3"}"""), "seconds 必须是 1 到 5 之间的整数")
        assertEquals(ToolCall.Wait(1), parse("wait", """{"seconds":1}""").getOrThrow())
        assertEquals(ToolCall.Wait(5), parse("wait", """{"seconds":5}""").getOrThrow())
    }

    @Test
    fun parsesAskUserAndDone() {
        assertEquals(ToolCall.AskUser("Which Mom?"), parse("ask_user", """{"question":"Which Mom?"}""").getOrThrow())
        assertRejected(parse("ask_user", """{"question":"${"q".repeat(501)}"}"""), "question 的长度必须为 1 到 500 个字符")
        assertEquals(ToolCall.Done("Sent it", true), parse("done", """{"summary":"Sent it","success":true}""").getOrThrow())
        assertRejected(parse("done", """{"summary":"Sent it"}"""), "缺少字段：success")
        assertRejected(parse("done", """{"summary":"","success":true}"""), "summary 的长度必须为 1 到 500 个字符")
    }

    @Test
    fun toolsWithoutInputsTakeNoFields() {
        assertEquals(ToolCall.Back, parse("back").getOrThrow())
        assertEquals(ToolCall.Home, parse("home").getOrThrow())
        assertEquals(ToolCall.Screenshot, parse("screenshot").getOrThrow())
        assertRejected(parse("back", """{"x":1}"""), "意外字段：x")
    }

    @Test
    fun rejectsUnknownTool() {
        assertRejected(parse("swipe"), "未知工具：swipe")
    }

    // ---- definitions ----

    @Test
    fun definitionNamesMatchSpec() {
        val names = Tools.definitions.map { it.name }
        assertEquals(43, names.size)
        assertEquals(
            setOf(
                "read_ui_source", "find_chat_input", "font_reference", "open_contact", "fill_chat",
                "perform_steps", "zoom_screen", "read_screen", "read_element", "find_elements", "tap_mark", "wait_for", "checkpoint", "long_press", "double_tap", "drag",
                "tap", "type", "scroll", "back", "home", "open_app", "tap_text",
                "screenshot", "wait", "ask_user", "done",
                "directions", "dial", "text_message", "web_search", "read_notifications", "reply_notification",
                "set_alarm", "set_timer", "open_url", "add_calendar_event",
                "list_files", "read_file", "write_file", "fetch_url", "second_opinion", "quiz_record",
            ),
            names.toSet(),
        )
    }

    @Test
    fun everyDefinitionIsStrictCompatible() {
        val unsupported = setOf("minLength", "maxLength", "minimum", "maximum", "pattern", "format", "oneOf", "anyOf", "allOf")
        Tools.definitions.forEach { def ->
            val schema = def.inputSchema
            assertEquals("object", schema.getValue("type").jsonPrimitive.content, def.name)
            assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean, "${def.name} must forbid extra fields")
            val properties = schema.getValue("properties").jsonObject
            schema.getValue("required").jsonArray.forEach {
                assertTrue(it.jsonPrimitive.content in properties, "${def.name}: required ${it.jsonPrimitive.content} is not a property")
            }
            assertTrue(def.description.isNotBlank(), "${def.name} needs a description")
            assertTrue(unsupported.none { "\"$it\":" in schema.toString() }, "${def.name} uses a keyword strict mode does not support")
        }
    }

    @Test
    fun everySchemaPropertyIsAcceptedByTheParser() {
        Tools.definitions.forEach { def ->
            val properties = def.inputSchema.getValue("properties").jsonObject
            properties.forEach { (field, spec) ->
                val sample: JsonPrimitive = when (spec.jsonObject.getValue("type").jsonPrimitive.content) {
                    "integer" -> JsonPrimitive(1)
                    "boolean" -> JsonPrimitive(true)
                    else -> JsonPrimitive(spec.jsonObject["enum"]?.jsonArray?.first()?.jsonPrimitive?.content ?: "a1")
                }
                val result = Tools.parse(def.name, JsonObject(mapOf(field to sample)))
                assertFalse(message(result).contains("意外字段"), "${def.name}.$field is in the schema but the parser rejects it")
            }
        }
    }

    @Test
    fun requiredListMatchesWhatTheParserRequires() {
        val tap = Tools.definitions.single { it.name == "tap" }
        assertEquals(JsonArray(emptyList()), tap.inputSchema.getValue("required"), "tap fields are all optional individually")
        val done = Tools.definitions.single { it.name == "done" }
        assertEquals(setOf("summary", "success"), done.inputSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    // ---- one-step shortcuts ----

    @Test
    fun parsesTheShortcuts() {
        assertEquals(ToolCall.Directions("221B Baker St"), parse("directions", """{"destination":"221B Baker St"}""").getOrThrow())
        assertEquals(ToolCall.Dial("+44 20 7946 0958"), parse("dial", """{"number":"+44 20 7946 0958"}""").getOrThrow())
        assertEquals(ToolCall.TextMessage("5551234", "on my way"), parse("text_message", """{"number":"5551234","body":"on my way"}""").getOrThrow())
        assertEquals(ToolCall.WebSearch("weather today"), parse("web_search", """{"query":"weather today"}""").getOrThrow())
    }

    @Test
    fun parsesOpenUrlOnlyForWebLinks() {
        assertEquals(ToolCall.OpenUrl("https://example.com/a?b=1"), parse("open_url", """{"url":"https://example.com/a?b=1"}""").getOrThrow())
        listOf("file:///sdcard/x.txt", "intent://x#Intent;end", "javascript:alert(1)", "https://a.com/x y", "example.com/page").forEach {
            assertTrue(parse("open_url", """{"url":"$it"}""").isFailure, it)
        }
    }

    @Test
    fun parsesCalendarEvent() {
        val ok = parse("add_calendar_event", """{"title":"Dentist","date":"2026-11-20","time":"15:30","duration_minutes":45}""").getOrThrow()
        assertEquals(ToolCall.AddEvent("Dentist", java.time.LocalDateTime.of(2026, 11, 20, 15, 30), 45), ok)
        assertTrue(parse("add_calendar_event", """{"title":"x","date":"2026-13-40","time":"15:30","duration_minutes":45}""").isFailure)
        assertTrue(parse("add_calendar_event", """{"title":"x","date":"2026-11-20","time":"25:61","duration_minutes":45}""").isFailure)
        assertTrue(parse("add_calendar_event", """{"title":"x","date":"2026-11-20","time":"15:30","duration_minutes":0}""").isFailure)
    }

    @Test
    fun parsesAlarmAndTimer() {
        assertEquals(ToolCall.SetAlarm(6, 30), parse("set_alarm", """{"hour":6,"minute":30}""").getOrThrow())
        assertEquals(ToolCall.SetAlarm(0, 0), parse("set_alarm", """{"hour":0,"minute":0}""").getOrThrow())
        assertEquals(ToolCall.SetTimer(600), parse("set_timer", """{"seconds":600}""").getOrThrow())
        assertEquals(ToolCall.SetTimer(86_400), parse("set_timer", """{"seconds":86400}""").getOrThrow())
    }

    @Test
    fun alarmAndTimerRejectOutOfRangeValues() {
        assertRejected(parse("set_alarm", """{"hour":24,"minute":0}"""), "hour 必须是 0 到 23 之间的整数")
        assertRejected(parse("set_alarm", """{"hour":6,"minute":60}"""), "minute 必须是 0 到 59 之间的整数")
        assertRejected(parse("set_alarm", """{"hour":6}"""), "缺少字段：minute")
        assertRejected(parse("set_timer", """{"seconds":0}"""), "seconds 必须是 1 到 86400 之间的整数")
        assertRejected(parse("set_timer", """{"seconds":86401}"""), "seconds 必须是 1 到 86400 之间的整数")
        assertRejected(parse("set_timer", """{"seconds":60,"label":"x"}"""), "意外字段：label")
    }

    @Test
    fun shortcutsRejectBadInput() {
        assertRejected(parse("dial", """{"number":"call mom"}"""), "number 必须是由数字组成的电话号码")
        assertRejected(parse("dial", """{"number":"+-()"}"""), "number 必须是由数字组成的电话号码")
        assertRejected(parse("dial", """{"number":"tel:555*1234"}"""), "number 必须是由数字组成的电话号码")
        assertRejected(parse("text_message", """{"number":"5551234"}"""), "缺少字段：body")
        assertRejected(parse("directions", """{"destination":""}"""), "destination 的长度必须为 1 到 300 个字符")
        assertRejected(parse("web_search", """{"query":"x","extra":1}"""), "意外字段：extra")
    }

    // ---- notifications ----

    @Test
    fun parsesReadNotifications() {
        assertEquals(ToolCall.ReadNotifications, parse("read_notifications").getOrThrow())
    }

    @Test
    fun readNotificationsRejectsAnyField() {
        assertRejected(parse("read_notifications", """{"limit":5}"""), "意外字段：limit")
        assertRejected(parse("read_notifications", """{"id":"n1"}"""), "意外字段：id")
    }

    @Test
    fun parsesReplyNotification() {
        assertEquals(ToolCall.ReplyNotification("n2", "on my way"), parse("reply_notification", """{"id":"n2","text":"on my way"}""").getOrThrow())
        assertEquals(ToolCall.ReplyNotification("n123", "x"), parse("reply_notification", """{"id":"n123","text":"x"}""").getOrThrow())
        val longest = "a".repeat(200)
        assertEquals(longest, (parse("reply_notification", """{"id":"n1","text":"$longest"}""").getOrThrow() as ToolCall.ReplyNotification).text)
    }

    @Test
    fun rejectsBadNotificationIds() {
        for (bad in listOf("x1", "n", "n1234", "N1", " n1", "n1 ", "n-1", "1")) {
            val result = parse("reply_notification", """{"id":"$bad","text":"hi"}""")
            assertTrue(result.isFailure, bad)
            val reason = message(result)
            // Too short or too long ids hit the length check first; the rest hit the n<digits> pattern.
            assertTrue(reason.contains("id") && (reason.contains("必须形如") || reason.contains("长度必须为")), "$bad: $reason")
        }
    }

    @Test
    fun notificationIdRegexSemantics() {
        assertEquals(ToolCall.ReplyNotification("n01", "hi"), parse("reply_notification", """{"id":"n01","text":"hi"}""").getOrThrow())
        assertRejected(parse("reply_notification", """{"id":"n1\n","text":"hi"}"""), "id 必须形如 n1")
        assertRejected(parse("reply_notification", "{\"id\":\"n१\",\"text\":\"hi\"}"), "id 必须形如 n1")
    }

    @Test
    fun replyToolDescriptionStatesTheLimit() {
        val reply = Tools.definitions.single { it.name == "reply_notification" }
        assertTrue(reply.description.contains("200 个字符"), reply.description)
    }

    @Test
    fun rejectsBadReplyText() {
        assertRejected(parse("reply_notification", """{"id":"n1","text":""}"""), "text 的长度必须为 1 到 200 个字符")
        // The card shows at most 200 characters, so a reply may not be longer than what the user sees.
        val tooLong = "a".repeat(201)
        assertRejected(parse("reply_notification", """{"id":"n1","text":"$tooLong"}"""), "text 的长度必须为 1 到 200 个字符")
        assertRejected(parse("reply_notification", """{"id":"n1","text":5}"""), "text 必须是字符串")
    }

    @Test
    fun rejectsReplyWithMissingOrExtraFields() {
        assertRejected(parse("reply_notification", """{"text":"hi"}"""), "缺少字段：id")
        assertRejected(parse("reply_notification", """{"id":"n1"}"""), "缺少字段：text")
        assertRejected(parse("reply_notification", """{"id":"n1","text":"hi","send":true}"""), "意外字段：send")
    }

    @Test
    fun notificationToolSetNamesExactlyTheTwoTools() {
        assertEquals(setOf("read_notifications", "reply_notification"), Tools.NOTIFICATION_TOOLS)
        assertTrue(Tools.NOTIFICATION_TOOLS.all { name -> Tools.definitions.any { it.name == name } })
    }
}

package dev.pocketagent.llm

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ContextBudgetTest {
    private fun message(role:String,content:String)=buildJsonObject { put("role",role);put("content",content) }
    @Test fun trimmingPreservesSignedThinkingToolsTaskAndLatestObservation() {
        val task=message("user","原始用户目标；<screen_data>"+"旧页面".repeat(100)+"</screen_data>")
        val signed=buildJsonObject {
            put("role","assistant")
            putJsonArray("content") {
                add(buildJsonObject { put("type","thinking");put("thinking","<screen_data>必须原样保留</screen_data>");put("signature","opaque-signature") })
                add(buildJsonObject { put("type","tool_use");put("id","tool-1");put("name","read_screen");putJsonObject("input"){} })
            }
        }
        val latest=message("user","<screen_data>最新页面</screen_data>")
        val result=ContextBudget.trimOldScreens(listOf(task,signed,latest),100,1024)
        assertTrue(result[0]["content"]!!.jsonPrimitive.content.startsWith("原始用户目标；"))
        assertFalse(result[0].toString().contains("旧页面"))
        assertEquals(signed,result[1])
        assertEquals(latest,result[2])
    }

    @Test fun screenshotCompactionKeepsTheTwoNewestUserImagesAndOpaqueAssistant() {
        fun pictured(role:String,id:String)=buildJsonObject { put("role",role);putJsonArray("content") { add(buildJsonObject { put("type","image");put("data",id) }) } }
        val signed=pictured("assistant","opaque")
        val messages=listOf(pictured("user","old"),signed,pictured("user","recent"),pictured("user","latest"))
        val result=ContextBudget.compactImages(messages)
        assertFalse(result[0].toString().contains("\"old\""))
        assertEquals(signed,result[1])
        assertEquals(messages.takeLast(2),result.takeLast(2))
    }
}

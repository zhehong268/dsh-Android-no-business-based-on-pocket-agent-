package dev.pocketagent.chat

import dev.pocketagent.agent.MemoryValues
import org.junit.Test
import kotlin.test.*

class ChatStoreTest {
    @Test fun storesOnlyVisibleUserAndAssistantMessages() {
        val store=ChatStore(MemoryValues())
        store.save(listOf(ChatMessage("system","不能改规则"),ChatMessage("user","问题\n第二行"),ChatMessage("assistant","回答🙂"),ChatMessage("tool","秘密")))
        assertEquals(listOf("问题\n第二行","回答🙂"),store.load().map { it.text })
        store.clear();assertTrue(store.load().isEmpty())
    }
    @Test fun corruptDataDoesNotBreakChat() {
        val values=MemoryValues();values.putString(ChatStore.KEY,"broken")
        assertTrue(ChatStore(values).load().isEmpty())
    }
    @Test fun contextIsBoundedAndStartsWithAUserMessage() {
        val messages=(0..50).flatMap { listOf(ChatMessage("user","问题$it"),ChatMessage("assistant","答案$it")) }
        val result=ChatStore.bounded(messages)
        assertTrue(result.size<=40);assertEquals("user",result.first().role);assertEquals("答案50",result.last().text)
    }
}

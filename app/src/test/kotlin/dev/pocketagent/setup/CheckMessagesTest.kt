package dev.pocketagent.setup

import dev.pocketagent.llm.CheckResult
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckMessagesTest {
    private fun msg(r: CheckResult) = CheckMessages.messageFor(r).lowercase()

    @Test fun worksSaysItCanUseTools() = assertTrue(msg(CheckResult.Works).contains("工具"))
    @Test fun noToolCallsSaysToPickAnotherModel() {
        val m = msg(CheckResult.NoToolCalls)
        assertTrue(m.contains("工具") && m.contains("其他模型"))
    }
    @Test fun badKeyMentionsTheKey() = assertTrue(msg(CheckResult.BadKey).contains("密钥"))
    @Test fun unreachableMentionsTheConnection() = assertTrue(msg(CheckResult.Unreachable).contains("连接"))
    @Test fun otherShowsTheProvidersMessage() =
        assertTrue(CheckMessages.messageFor(CheckResult.Other("  429 rate limited ")).contains("429 rate limited"))
    @Test fun otherWithABlankMessageFallsBack() = assertTrue(CheckMessages.messageFor(CheckResult.Other("  ")).isNotBlank())
    @Test fun theFixedMessagesNeverMentionAKeyValue() {
        listOf(CheckResult.Works, CheckResult.NoToolCalls, CheckResult.BadKey, CheckResult.Unreachable).forEach {
            val m = CheckMessages.messageFor(it)
            assertFalse(m.contains("sk-") || m.contains("Bearer"))
        }
    }
}

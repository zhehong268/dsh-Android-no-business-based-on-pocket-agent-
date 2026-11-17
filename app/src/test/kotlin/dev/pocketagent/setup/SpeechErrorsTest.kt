package dev.pocketagent.setup

import org.junit.Test
import kotlin.test.assertTrue

class SpeechErrorsTest {
    private fun msg(code: Int) = SpeechErrors.messageFor(code).lowercase()

    @Test
    fun everyKnownCodeHasAMessage() {
        (1..13).forEach { assertTrue(SpeechErrors.messageFor(it).isNotBlank(), "code $it") }
    }

    @Test
    fun unknownCodesFallBack() {
        listOf(0, 99, -1).forEach { assertTrue(msg(it).contains("输入"), "code $it") }
    }

    @Test
    fun nothingHeardMentionsHearing() {
        listOf(6, 7).forEach { assertTrue(msg(it).contains("听到"), "code $it") }
    }

    @Test
    fun connectionErrorsMentionAConnection() {
        listOf(1, 2, 4, 11).forEach { assertTrue(msg(it).contains("连接"), "code $it") }
    }

    @Test
    fun busyMentionsBusy() = assertTrue(msg(8).contains("正忙"))

    @Test
    fun permissionMentionsTheMicrophone() = assertTrue(msg(9).contains("麦克风"))

    @Test
    fun languageErrorsMentionLanguage() {
        listOf(12, 13).forEach { assertTrue(msg(it).contains("语言"), "code $it") }
    }
}

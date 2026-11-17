package dev.pocketagent.agent

import dev.pocketagent.device.*
import org.junit.Test
import kotlin.test.*

class MessageEffectTest {
    private val body = "明天十点见"
    private val input = node("a1", role="input", text=body, editable=true).copy(viewId="message")
    private val send = node("a2", role="button", text="发送", clickable=true)
    private fun before(vararg extra: ScreenNode) = screenOf(input,send,*extra)
    private fun after(vararg extra: ScreenNode) = screenOf(input.copy(id="b1",text=null,hint="消息输入框"),send.copy(id="b2"),*extra)
    private fun effect(screen: Screen=before()) = MessageEffect.begin(screen,send,body)!!

    @Test fun aNewBubbleAndClearedComposerVerifyTheSend() {
        assertTrue(effect().verified(after(node("b3",text=body))))
    }
    @Test fun confirmationPreviewsTheWholeDraftInsteadOfOnlyTheLastAppend() {
        assertEquals(body,MessageEffect.preview(before(),send,"十点见"))
    }
    @Test fun anEmptyVisibleComposerDoesNotPreviewStaleTypedText() {
        assertNull(MessageEffect.preview(after(),send,"旧正文"))
    }
    @Test fun aDuplicatedOrLongerBubbleDoesNotVerifyTheRequestedBody() {
        assertFalse(effect().verified(after(node("b3",text=body+body))))
        assertFalse(effect().verified(after(node("b3",text=body+"，另加一句"))))
    }
    @Test fun aPendingBubbleDoesNotVerifyTheSend() {
        assertFalse(effect().verified(after(node("b3",text=body+" 发送中"))))
    }
    @Test fun changedCaseInTheMessageBodyDoesNotVerifyTheSend() {
        val initial=screenOf(input.copy(text="Aa"),send)
        val pending=MessageEffect.begin(initial,send,"Aa")!!
        assertFalse(pending.verified(after(node("b3",text="aA 已发送"))))
    }
    @Test fun anExistingBubbleCannotVerifyANewSend() {
        assertFalse(effect(before(node("a3",text=body))).verified(after(node("b3",text=body))))
    }
    @Test fun hidingTheKeyboardDoesNotVerifyTheSend() {
        assertFalse(effect().verified(before().copy(keyboardVisible=false)))
    }
    @Test fun aNewBubbleWithAnUnclearedDraftRemainsUncertain() {
        assertFalse(effect().verified(before(node("b3",text=body))))
    }
    @Test fun aNewFailureMarkerPreventsSuccess() {
        assertFalse(effect().verified(after(node("b3",text=body),node("b4",text="发送失败"))))
    }
    @Test fun anOldFailureDoesNotInvalidateANewVerifiedMessage() {
        assertTrue(effect(before(node("a4",text="发送失败"))).verified(after(node("b3",text=body),node("b4",text="发送失败"))))
    }
    @Test fun theSameBodyCanBeSentTwiceWithTwoDistinctBubbles() {
        assertTrue(effect(before(node("a3",text=body))).verified(after(node("b3",text=body),node("b4",text=body))))
    }
    @Test fun aContainerAndItsChildCountAsOneBubble() {
        val old=node("a3",text=body,children=listOf(node("a4",text=body)))
        assertFalse(effect(before(old)).verified(after(old.copy(id="b3"))))
    }
    @Test fun changingAppsCannotVerifyTheSend() {
        assertFalse(effect().verified(after(node("b3",text=body)).copy(packageName="other")))
    }
    @Test fun editableTextSayingSendIsNotASendButton() {
        assertFalse(MessageEffect.isSend(input.copy(text="发送")))
    }
}

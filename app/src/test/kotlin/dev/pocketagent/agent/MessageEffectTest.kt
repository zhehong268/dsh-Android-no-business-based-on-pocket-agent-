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

    @Test fun aRebuiltComposerWithNewIdAndBoundsStillVerifies() {
        val rebuilt=after(node("bubble",text=body)).copy(root=after(node("bubble",text=body)).root.let { root->
            root.copy(children=root.children.map { if(it.editable)it.copy(viewId="new_editor",bounds=Bounds(100,1800,700,1900)) else it }) })
        assertTrue(effect().verified(rebuilt))
    }
    @Test fun aNewBubbleCanVerifyWhenTheEditorIsRemovedInTheSameConversation() {
        val title=node("title",text="张小明",bounds=Bounds(20,60,600,140))
        val initial=before(title)
        val receipt=MessageEffect.begin(initial,send,body)!!
        val sent=screenOf(title,node("bubble",text=body,bounds=Bounds(400,500,1000,650)),node("plus",role="button",description="更多功能"))
        assertTrue(receipt.verified(sent))
        assertFalse(receipt.verified(sent.copy(root=sent.root.copy(children=sent.root.children.map { if(it.id=="title")it.copy(text="李四") else it }))))
    }
    @Test fun anExplicitSelfSenderDescriptionIsAcceptedButExtraBodyIsNot() {
        assertTrue(effect().verified(after(node("bubble",description="我：$body 已发送"))))
        assertFalse(effect().verified(after(node("bubble",description="我：$body，另加一句 已发送"))))
        assertFalse(effect().verified(after(node("bubble",description="李四：$body 已发送"))))
    }
    @Test fun separatePendingStatusPreventsAnExactBubbleFromVerifying() {
        assertFalse(effect().verified(after(node("bubble",text=body),node("status",text="发送中"))))
    }
    @Test fun aMultilineBubbleMayExposeItsParagraphsAsSeparateChildren() {
        val draft=input.copy(text="第一行\n第二行")
        val receipt=MessageEffect.begin(screenOf(draft,send),send,draft.text)!!
        val bubble=node("bubble",children=listOf(node("p1",text="第一行"),node("p2",text="第二行"))).copy(viewId="chat_message_content")
        assertTrue(receipt.verified(after(bubble)))
    }
    @Test fun aPersistedReceiptRecoversEvidenceWithoutSavingTheBody() {
        val original=effect()
        val store=ProgressStore(MemoryValues())
        store.save(TaskProgress("发送任务",pendingMessage=true,messageReceipt=original.receipt))
        assertTrue(MessageEffect.restore(store.load()!!.messageReceipt!!).verified(after(node("bubble",text=body))))
        assertFalse(original.receipt.toString().contains(body))
    }

    @Test fun qqAcceptsNewOwnDescribedBubbleAndClearedComposer() {
        val title = node("title", text = "张小明", bounds = Bounds(20, 60, 600, 140))
        val initial = before(title).copy(packageName = "com.tencent.mobileqq")
        val receipt = effect(initial)
        val bubble = node("qq", description = "我发送的消息，$body，12:30，已发送", bounds = Bounds(300, 550, 950, 650))
        assertTrue(receipt.verified(after(title, bubble).copy(packageName = initial.packageName)))
        assertFalse(receipt.verified(after(title, bubble.copy(description = "张小明，$body，12:30")).copy(packageName = initial.packageName)))
        assertFalse(receipt.verified(after(title, bubble, node("pending", description = "发送中")).copy(packageName = initial.packageName)))
        assertFalse(effect(before(title, bubble).copy(packageName = initial.packageName))
            .verified(after(title, bubble.copy(id = "old")).copy(packageName = initial.packageName)))
    }
}

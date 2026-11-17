package dev.pocketagent.device

import org.junit.Test
import kotlin.test.*

class ChatTargetsTest {
    private fun field(id:String,y:Int,hint:String?=null,focused:Boolean=false)=node(id,role="input",editable=true,bounds=Bounds(100,y,800,y+100)).copy(hint=hint,focused=focused)
    private val send=node("s",text="发送",clickable=true,bounds=Bounds(820,1900,1060,2000))
    @Test fun anUnlabelledBottomComposerWinsOverTheFocusedSearchBar() {
        val screen=screenOf(field("search",100,"搜索聊天记录",true),field("body",1900),send)
        assertEquals("body",ChatTargets.composer(screen)?.id)
    }
    @Test fun anInputNearSendIsRecognisedWhenTheKeyboardMovesItUp() {
        val screen=screenOf(field("body",800),send.copy(bounds=Bounds(820,800,1060,900)))
        assertEquals("body",ChatTargets.composer(screen)?.id)
    }
    @Test fun twoSimilarComposersRemainAmbiguous() {
        assertNull(ChatTargets.composer(screenOf(field("one",1900),field("two",1900),send)))
    }
    @Test fun aSearchBoxIsNeverAComposer() {
        assertNull(ChatTargets.composer(screenOf(field("search",1900,"搜索联系人"),send)))
    }
    @Test fun aPasswordOrDisabledFieldIsNotAComposer() {
        assertNull(ChatTargets.composer(screenOf(field("body",1900).copy(isPassword=true),send)))
        assertNull(ChatTargets.composer(screenOf(field("body",1900).copy(enabled=false),send)))
    }
    @Test fun aMessageBubbleCannotVerifyTheRecipient() {
        assertNull(ChatTargets.title(screenOf(node("bubble",text="张小明",bounds=Bounds(10,800,700,900))),"张小明"))
    }
    @Test fun anOldBubbleNearTheTopCannotReplaceADifferentHeader() {
        val screen=screenOf(node("title",text="李四",bounds=Bounds(10,80,800,180)),node("bubble",text="张小明",bounds=Bounds(10,350,800,440)))
        assertNull(ChatTargets.title(screen,"张小明"))
    }
    @Test fun aHeaderUsesOriginalUnicodeRegardlessOfDecorativeGlyphs() {
        val header=node("title",text="𝓐𝓵𝓲𝓬𝓮",bounds=Bounds(100,70,800,160))
        assertEquals("title",ChatTargets.title(screenOf(header),"Alice")?.id)
    }
    private fun row(id:String,name:String,note:String,top:Int)=node(id,clickable=true,bounds=Bounds(0,top,1080,top+180),children=listOf(
        node(id+"name",text=name,bounds=Bounds(20,top,800,top+80)),node(id+"note",text=note,bounds=Bounds(20,top+80,800,top+160)),
    ))
    @Test fun namesMustMatchCompletelyAndTheClickableRowIsReturned() {
        val screen=screenOf(row("one","张小明","研发",400),row("two","张小明二","销售",600))
        assertEquals(listOf("one"),ChatTargets.contacts(screen,"张小明").map { it.target.id })
    }
    @Test fun duplicateNamesNeedAnActualRemark() {
        val screen=screenOf(row("one","张小明","研发",400),row("two","张小明","销售",600))
        assertEquals(2,ChatTargets.contacts(screen,"张小明").size)
        assertEquals("one",ChatTargets.contacts(screen,"张小明","研发").single().target.id)
        assertTrue(ChatTargets.contacts(screen,"张小明","不存在的备注").isEmpty())
    }
    @Test fun chatBubblesAreNotContactRows() {
        val screen=screenOf(row("bubble","张小明","旧消息",400),field("body",1900),send)
        assertTrue(ChatTargets.contacts(screen,"张小明").isEmpty())
    }
    @Test fun voiceSwitchIsLocatedWithoutGuessingACoordinate() {
        assertEquals("voice",ChatTargets.keyboardSwitch(screenOf(node("voice",description="切换到键盘",clickable=true)))?.id)
    }
    @Test fun aMessageMentioningKeyboardIsNotASwitchControl() {
        val screen=screenOf(node("message",role="text",text="键盘",description="键盘"),node("voice",role="button",text="切换到键盘",clickable=true))
        assertEquals("voice",ChatTargets.keyboardSwitch(screen)?.id)
    }
    @Test fun sourceKeepsOriginalTextAndMasksPasswords() {
        val original="花字体的原始姓名\n"+"正文".repeat(400)
        val screen=screenOf(node("text",text=original).copy(className="android.widget.TextView"),node("password",text="private-secret",isPassword=true))
        val source=ScreenSerializer("dev.pocketagent").source(screen)
        assertTrue(source.contains("android.widget.TextView"));assertTrue(source.contains("花字体的原始姓名"));assertTrue(source.contains("正文".repeat(300)))
        assertFalse(source.contains("private-secret"))
    }
}

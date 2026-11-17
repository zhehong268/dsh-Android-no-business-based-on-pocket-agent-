package dev.pocketagent.device

import org.junit.Test
import kotlin.test.*

class PhoneObservationTest {
    @Test fun controlsAtTheBottomSurviveAFullClickableHistory() {
        val history=(0..200).map { node("a$it",text="历史消息".repeat(100),clickable=true) }
        val screen=screenOf(*(history+listOf(node("a300",role="input",editable=true),node("a301",role="button",text="发送",clickable=true))).toTypedArray())
        val text=ScreenSerializer("dev.pocketagent",maxChars=2000,geometry=true).serialize(screen)
        assertTrue(text.contains("[a300] input"));assertTrue(text.contains("[a301] button"))
        assertTrue(text.length<=2000);assertTrue(text.endsWith("</screen_data>"))
    }
    @Test fun anUnlabelledFieldCanBeFoundByTypeAndResourceId() {
        val screen=screenOf(node("a1",role="input",editable=true).copy(viewId="com.tencent.mm:id/chat"),node("a2",role="input",editable=true).copy(viewId="search"))
        assertEquals("a1",TextTargets.find(screen,editable=true,viewId="chat").single().id)
        assertEquals(2,TextTargets.find(screen,role="input").size)
    }
    @Test fun fullwidthAndWhitespaceAreNormalizedWithoutChoosingAHomonym() {
        val screen=screenOf(node("a1",text="ＡＢＣ　联系人"),node("a2",text="ABC 联系人"))
        assertEquals(2,TextTargets.find(screen,"abc 联系人").size)
    }
    @Test fun disabledControlsAreExcludedWithoutHidingEnabledChildren() {
        val parent=node("a1",text="发送",children=listOf(node("a2",text="发送",clickable=true))).copy(enabled=false)
        assertEquals("a2",TextTargets.find(screenOf(parent),"发送").single().id)
    }
    @Test fun hintTextDoesNotBecomeTheDraftValue() {
        val field=node("a1",role="input",editable=true).copy(hint="消息输入框")
        assertNull(field.text);assertEquals("消息输入框",field.label())
        assertTrue(ScreenSerializer("dev.pocketagent").serialize(screenOf(field)).contains("value_empty=true"))
    }
    @Test fun blankControlsCannotBeSwappedUnderAStaleCoordinate() {
        val node=node("a1",clickable=true,bounds=Bounds(0,0,100,100)).copy(viewId="safe")
        val screen=screenOf(node).copy(windowId=10)
        val gate=Revalidator("dev.pocketagent")
        assertTrue(gate.pointStillMatches(screen,50,50,screen))
        assertFalse(gate.pointStillMatches(screen,50,50,screenOf(node.copy(viewId="send")).copy(windowId=10)))
        assertFalse(gate.pointStillMatches(screen,50,50,screen.copy(windowId=11)))
        assertFalse(gate.pointStillMatches(screen,50,50,screenOf(node.copy(bounds=Bounds(0,0,80,80))).copy(windowId=10)))
    }
}

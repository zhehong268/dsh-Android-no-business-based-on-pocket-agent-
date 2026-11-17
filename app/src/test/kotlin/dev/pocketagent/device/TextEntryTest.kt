package dev.pocketagent.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class TextEntryTest {
    @Test fun passwordVariationsAreProtectedEvenWithoutAnEditableFlag() {
        for(type in listOf(0x81,0x91,0xE1,0x12))assertTrue(TextEntry.isPasswordType(type))
    }
    @Test fun normalTextNumbersAndUrlsAreNotPasswordFields() {
        for(type in listOf(0,1,2,0x11,0x21,0x20001))assertFalse(TextEntry.isPasswordType(type))
    }
    private class Editor(var text:String="",var valid:Boolean=true):TextEditor {
        var pastes=0;var sets=0;var commits=0;var selection=true;var applyPaste=true;var pasteAvailable=true;var setAvailable=true;var applySet=true;var applyCommit=false;var wrongValue:String?=null
        override fun value()=text
        override fun sameTarget()=valid
        override fun focus()=true
        override fun selectAll(length:Int)=selection
        override fun paste(text:String):Boolean { if(!pasteAvailable)return false;pastes++;if(applyPaste)this.text=wrongValue?:text;return true }
        override fun setText(text:String):Boolean { if(!setAvailable)return false;sets++;if(applySet)this.text=text;return true }
        override fun commit(text:String,previousLength:Int):Boolean { commits++;if(applyCommit)this.text=text;return applyCommit }
    }
    @Test fun pastesExactChineseEmojiCaseSpacesAndLineBreaks()=runTest {
        val editor=Editor("旧内容");val requested="  Aa 中文🙂\n第二行  "
        assertEquals(ActionResult.Ok,TextEntry.enter(editor,requested,"replace"){})
        assertEquals(requested,editor.text);assertEquals(1,editor.pastes);assertEquals(0,editor.sets)
    }
    @Test fun appendKeepsTheWholeExistingDraft()=runTest {
        val editor=Editor("原稿\n")
        TextEntry.enter(editor,"追加","append"){}
        assertEquals("原稿\n追加",editor.text);assertEquals(1,editor.pastes)
    }
    @Test fun clearSelectsTheDraftAndPastesAnEmptyValue()=runTest {
        val editor=Editor("旧草稿");assertEquals(ActionResult.Ok,TextEntry.enter(editor,"","clear"){})
        assertEquals("",editor.text);assertEquals(1,editor.pastes)
    }
    @Test fun anAlreadyCorrectFieldIsNotPastedAgain()=runTest {
        val editor=Editor("草稿");TextEntry.enter(editor,"草稿","replace"){}
        assertEquals(0,editor.pastes);assertEquals(0,editor.sets)
    }
    @Test fun targetChangesPreventAnyWrite()=runTest {
        val editor=Editor(valid=false);assertIs<ActionResult.Failed>(TextEntry.enter(editor,"草稿","replace"){})
        assertEquals(0,editor.pastes);assertEquals(0,editor.sets)
    }
    @Test fun anUnsafeSelectionFallsBackToDirectTargetedSetting()=runTest {
        val editor=Editor("旧草稿").apply { selection=false }
        assertEquals(ActionResult.Ok,TextEntry.enter(editor,"新草稿","replace"){})
        assertEquals(0,editor.pastes);assertEquals(1,editor.sets)
    }
    @Test fun aDispatchedPasteWithCorrectTextDoesNotRepeatInput()=runTest {
        val editor=Editor()
        assertEquals(ActionResult.Ok,TextEntry.enter(editor,"草稿","replace"){})
        assertEquals(1,editor.pastes);assertEquals(0,editor.sets)
    }
    @Test fun partialOrIncorrectTextStopsWithoutAnotherWrite()=runTest {
        val editor=Editor().apply { wrongValue="部分" }
        assertTrue(assertIs<ActionResult.Failed>(TextEntry.enter(editor,"完整正文","replace"){}).mayHaveEffect)
        assertEquals(1,editor.pastes);assertEquals(0,editor.sets)
    }
    @Test fun aMissingPasteImplementationCanUseTheDirectEditor()=runTest {
        val editor=Editor().apply { pasteAvailable=false }
        assertEquals(ActionResult.Ok,TextEntry.enter(editor,"草稿","replace"){})
        assertEquals(0,editor.pastes);assertEquals(1,editor.sets)
    }
    @Test fun directInputConnectionIsTheLastFallback()=runTest {
        val editor=Editor().apply { pasteAvailable=false;setAvailable=false;applyCommit=true }
        assertEquals(ActionResult.Ok,TextEntry.enter(editor,"草稿","replace"){})
        assertEquals(1,editor.commits)
    }
    @Test fun inputIsNeverSuccessfulWhenEveryRouteIgnoredIt()=runTest {
        val editor=Editor().apply { applyPaste=false;applySet=false }
        assertIs<ActionResult.Failed>(TextEntry.enter(editor,"草稿","replace"){})
        assertEquals(1,editor.pastes)
        assertEquals(0,editor.sets);assertEquals(0,editor.commits)
    }
    @Test fun cancellationInterruptsVerificationWithoutRepeatingInput()=runTest {
        val editor=Editor().apply { applyPaste=false }
        assertFailsWith<CancellationException> { TextEntry.enter(editor,"草稿","replace"){throw CancellationException()} }
        assertEquals(1,editor.pastes);assertEquals(0,editor.sets)
    }
}

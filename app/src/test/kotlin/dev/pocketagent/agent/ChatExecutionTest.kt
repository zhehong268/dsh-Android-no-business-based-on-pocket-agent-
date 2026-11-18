package dev.pocketagent.agent

import dev.pocketagent.device.*
import dev.pocketagent.fakes.*
import dev.pocketagent.llm.*
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ChatExecutionTest {
    private fun call(name:String,args:String="{}")=LlmResult.Call(name,name,Json.parseToJsonElement(args).jsonObject)
    private fun done(success:Boolean)=call("done","""{"summary":"结果","success":$success}""")
    private fun loop(llm:LlmClient,device:DevicePort,ui:FakeUi=FakeUi(),limit:Int=25)=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,maxSteps=limit,autoVision=false)
    private fun chat(person:String="张小明",body:String?=null)=screenOf(
        node("title",text=person,bounds=Bounds(20,80,800,180)),
        node("search",editable=true,bounds=Bounds(20,300,800,400)).copy(hint="搜索聊天记录"),
        node("body",editable=true,bounds=Bounds(100,1900,800,2000)).copy(text=body,viewId="message"),
        node("send",text="发送",clickable=true,bounds=Bounds(820,1900,1060,2000)),
    )
    @Test fun fillChatChoosesTheComposerAndFinishesAVerifiedDraft()=runTest {
        val llm=FakeLlm(listOf(call("fill_chat","""{"recipient":"张小明","text":"Aa 中文🙂","finish_if_verified":true}""")))
        val device=FakeDevice(listOf(chat(),chat(body="Aa 中文🙂")))
        assertTrue(loop(llm,device).run("填写草稿",chat()).success)
        assertEquals("body",(device.actions.single() as DeviceAction.TypeText).node.id)
    }
    @Test fun theWrongRecipientPreventsAllWrites()=runTest {
        val llm=FakeLlm(listOf(call("fill_chat","""{"recipient":"李四","text":"不能填"}"""),done(false)))
        val device=FakeDevice(listOf(chat()))
        loop(llm,device).run("给李四写草稿",chat());assertTrue(device.actions.isEmpty())
    }
    @Test fun voiceModeSwitchesOnceBeforeTyping()=runTest {
        val voice=screenOf(node("title",text="张小明",bounds=Bounds(20,80,800,180)),node("voice",description="切换到键盘",clickable=true,bounds=Bounds(20,1900,100,2000)))
        val llm=FakeLlm(listOf(call("fill_chat","""{"recipient":"张小明","text":"草稿","finish_if_verified":true}""")))
        val device=FakeDevice(listOf(voice,voice,chat(),chat(body="草稿")))
        assertTrue(loop(llm,device).run("填写草稿",voice).success)
        assertEquals(2,device.actions.size);assertIs<DeviceAction.Tap>(device.actions[0]);assertIs<DeviceAction.TypeText>(device.actions[1])
    }
    @Test fun twoEquivalentComposersStopWithoutTouchingEither()=runTest {
        val base=chat();val duplicate=base.copy(root=base.root.copy(children=base.root.children+base.find("body")!!.copy(id="other")))
        val llm=FakeLlm(listOf(call("fill_chat","""{"recipient":"张小明","text":"草稿"}"""),done(false)))
        val device=FakeDevice(listOf(duplicate));loop(llm,device).run("填写草稿",duplicate)
        assertTrue(device.actions.isEmpty())
    }
    private class ContactDevice:DevicePort {
        var page="home";var query="";var draft="";var reads=0;val actions=mutableListOf<DeviceAction>()
        override suspend fun screenshot():ByteArray?=null
        override suspend fun readScreen():Screen {
            reads++
            return when(page) {
                "home"->screenOf(node("contacts",text="通讯录",clickable=true,bounds=Bounds(10,2100,500,2200)))
                "directory"->screenOf(node("directoryTitle",text="通讯录",bounds=Bounds(30,80,800,180)).copy(viewId="directory_title"),node("searchButton",text="搜索",clickable=true,bounds=Bounds(900,100,1080,200)))
                "search"->screenOf(node("query",editable=true,bounds=Bounds(10,200,900,300)).copy(text=query,hint="搜索联系人"),*if(query.isBlank())emptyArray() else arrayOf(node("contact",text="张小明",clickable=true,bounds=Bounds(10,400,1080,550))))
                "profile"->screenOf(node("name",text="张小明",bounds=Bounds(10,400,800,500)),node("message",text="发消息",clickable=true,bounds=Bounds(10,600,1000,750)))
                else->screenOf(node("title",text="张小明",bounds=Bounds(10,80,800,180)),node("body",editable=true,bounds=Bounds(10,1900,800,2000)).copy(text=draft),node("send",text="发送",clickable=true,bounds=Bounds(820,1900,1080,2000)))
            }
        }
        override suspend fun perform(action:DeviceAction):ActionResult {
            actions+=action
            when(action) {
                is DeviceAction.Tap->page=when(action.node.id){"contacts"->"directory";"searchButton"->"search";"contact"->"profile";"message"->"chat";else->page}
                is DeviceAction.TypeText->if(action.node.id=="query")query=action.text else draft=action.text
                else->Unit
            }
            return ActionResult.Ok
        }
    }
    @Test fun oneContactCallNavigatesDirectorySearchProfileAndConversation()=runTest {
        val device=ContactDevice();val initial=device.readScreen()
        val llm=FakeLlm(listOf(call("open_contact","""{"name":"张小明"}"""),done(true)))
        val ui=FakeUi();assertTrue(loop(llm,device,ui).run("打开张小明会话",initial).success)
        assertEquals("chat",device.page);assertEquals(5,device.actions.size);assertTrue(ui.confirmRequests.isEmpty())
        assertEquals(2,llm.turns.size)
        assertEquals(7,device.reads) // One initial read, one at tool entry and one after each real action.
    }
    @Test fun contactNavigationRespectsTheActionBudget()=runTest {
        val device=ContactDevice();val initial=device.readScreen()
        val llm=FakeLlm(listOf(call("open_contact","""{"name":"张小明"}""")))
        assertEquals(EndReason.STEP_LIMIT,loop(llm,device,limit=2).run("打开张小明",initial).reason)
        assertEquals(2,device.actions.size)
    }
    @Test fun fillChatCanNavigateAndFinishAVerifiedDraftInOneModelCall() = runTest {
        val device = ContactDevice()
        val initial = device.readScreen()
        val llm = FakeLlm(listOf(call("fill_chat", """{"recipient":"张小明","text":"新草稿","open_if_needed":true,"finish_if_verified":true}""")))
        val ui = FakeUi()
        val outcome = loop(llm, device, ui).run("给张小明写草稿", initial)
        assertTrue(outcome.success)
        assertEquals("新草稿", device.draft)
        assertEquals(1, llm.turns.size)
        assertEquals(6, device.actions.size)
        assertTrue(ui.confirmRequests.isEmpty())
    }

    @Test fun aChangedConversationCannotBeSentUsingTheOldRecipient()=runTest {
        val llm=FakeLlm(listOf(call("fill_chat","""{"recipient":"张小明","text":"草稿"}"""),call("tap_text","""{"text":"发送","scrolls":0}"""),done(false)))
        val device=FakeDevice(listOf(chat(),chat(person="李四",body="草稿")))
        val ui=FakeUi();loop(llm,device,ui).run("填好后发送",chat())
        assertEquals(1,device.actions.size);assertTrue(ui.confirmRequests.isEmpty())
    }
    @Test fun fontReferenceInvalidatesOldScreenshotMarks()=runTest {
        val device=object:DevicePort {
            override suspend fun readScreen()=chat()
            override suspend fun perform(action:DeviceAction)=error("must not touch")
            override suspend fun screenshot()=byteArrayOf(1)
            override suspend fun fontReference(text:String)=dev.pocketagent.device.FontReference("字体参照",byteArrayOf(2))
        }
        val llm=FakeLlm(listOf(call("font_reference","""{"text":"张小明"}"""),call("tap_mark","""{"index":1,"observation":0}"""),done(false)))
        loop(llm,device).run("辨认字体",chat());assertTrue(llm.turns[2].toolResult!!.isError)
    }
    @Test fun fontReferenceCoordinatesCannotBeUsedAsScreenCoordinates()=runTest {
        val device=object:DevicePort {
            override suspend fun readScreen()=chat()
            override suspend fun perform(action:DeviceAction)=error("must not touch a font reference")
            override suspend fun screenshot()=byteArrayOf(1)
            override suspend fun fontReference(text:String)=dev.pocketagent.device.FontReference("字体参照",byteArrayOf(2))
        }
        val llm=FakeLlm(listOf(call("font_reference","""{"text":"张小明"}"""),call("tap","""{"x":100,"y":100}"""),done(false)))
        loop(llm,device).run("辨认字体",chat());assertTrue(llm.turns[2].toolResult!!.isError)
    }
}

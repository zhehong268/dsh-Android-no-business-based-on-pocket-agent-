package dev.pocketagent.agent

import dev.pocketagent.device.*
import dev.pocketagent.fakes.*
import dev.pocketagent.llm.*
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.*
import dev.pocketagent.setup.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class MemoryValues : KeyValueStore {
    private val data=mutableMapOf<String,String>()
    override fun getString(key:String)=data[key]
    override fun putString(key:String,value:String?) { if(value==null)data.remove(key) else data[key]=value }
    override fun getBoolean(key:String,default:Boolean)=data[key]?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key:String,value:Boolean) { data[key]=value.toString() }
}

class OptimizationTest {
    private val own="dev.pocketagent"
    private fun call(name:String,vararg args:Pair<String,Any>)=LlmResult.Call("id-$name",name,buildJsonObject { args.forEach { (k,v)-> when(v) { is Boolean->put(k,v); is Int->put(k,v); else->put(k,v.toString()) } } })
    private fun done()=call("done","summary" to "完成","success" to true)
    private fun loop(llm:LlmClient,device:DevicePort,ui:AgentUi=FakeUi(),progress:ProgressStore?=null,vision:Boolean=false)=AgentLoop(llm,device,SafetyGate(BlockedApps(),own),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer(own),ui,progress=progress,autoVision=vision)
    private val initial=screenOf(node("a1",text="下一步",clickable=true))

    @Test fun stateChangesAreObservedWhenTextIsIdentical()=runTest {
        val before=screenOf(node("a1",text="提醒",clickable=true).copy(checkable=true,checked=false))
        val after=screenOf(node("b1",text="提醒",clickable=true).copy(checkable=true,checked=true))
        val llm=FakeLlm(listOf(call("tap","node_id" to "a1"),done()))
        assertTrue(loop(llm,FakeDevice(listOf(after))).run("开启提醒",before).success)
        assertTrue(llm.turns[1].toolResult!!.text.contains("勾选状态变为 true"))
    }
    @Test fun duplicateNamesAreNeverSilentlyClicked()=runTest {
        val screen=screenOf(node("a1",text="张三",clickable=true),node("a2",text="张三",clickable=true))
        val llm=FakeLlm(listOf(call("tap_text","text" to "张三"),done()));val device=FakeDevice(listOf(screen))
        loop(llm,device).run("查找联系人",screen)
        assertTrue(device.actions.isEmpty()); assertTrue(llm.turns[1].toolResult!!.isError)
        assertTrue(llm.turns[1].text.contains("a2"))
    }
    @Test fun anExactNameWinsOverSubstringDecoys()=runTest {
        val screen=screenOf(node("a1",text="张三丰",clickable=true),node("a2",text="张三",clickable=true))
        val llm=FakeLlm(listOf(call("tap_text","text" to "张三"),done()));val device=FakeDevice(listOf(initial))
        loop(llm,device).run("选择张三",screen)
        assertEquals("a2",(device.actions.single() as DeviceAction.Tap).node.id)
    }
    @Test fun failedDispatchCannotBecomeSuccess()=runTest {
        val llm=FakeLlm(listOf(call("tap","node_id" to "a1"),done()))
        assertFalse(loop(llm,FakeDevice(listOf(initial),resultFor={ActionResult.Failed("失效")})).run("打开项目",initial).success)
    }
    @Test fun declinedSendCannotBecomeSuccess()=runTest {
        val screen=screenOf(node("a1",text="发送",clickable=true)); val ui=FakeUi().apply { onConfirm={false} }
        val device=FakeDevice(listOf(screen)); val llm=FakeLlm(listOf(call("tap","node_id" to "a1"),done()))
        assertFalse(loop(llm,device,ui).run("发送消息",screen).success); assertTrue(device.actions.isEmpty())
    }
    @Test fun repeatedDeadTouchIsDispatchedOnlyOnce()=runTest {
        val llm=FakeLlm(listOf(call("tap","node_id" to "a1"),call("tap","node_id" to "a1"),call("tap","node_id" to "a1")))
        val device=FakeDevice(listOf(initial));val outcome=loop(llm,device).run("打开",initial)
        assertEquals(EndReason.STUCK,outcome.reason);assertEquals(1,device.actions.size)
    }
    @Test fun sparseFirstScreenGetsAnImageAutomatically()=runTest {
        val screen=screenOf(); val llm=FakeLlm(listOf(done())); val device=FakeDevice(listOf(screen))
        loop(llm,device,vision=true).run("看看屏幕",screen)
        assertNotNull(llm.turns.single().image);assertEquals(1,device.screenshotCalls)
    }
    @Test fun textOnlyModelsNeverRequestAutomaticImages()=runTest {
        val llm=FakeLlm(listOf(done()),supportsImages=false); val device=FakeDevice(listOf(initial))
        loop(llm,device,vision=true).run("看屏幕",initial);assertEquals(0,device.screenshotCalls)
    }
    @Test fun blockedAppsNeverReachAutomaticCapture()=runTest {
        val screen=screenOf(packageName="com.phonepe.app",appLabel="PhonePe");val device=FakeDevice(listOf(screen))
        val result=loop(FakeLlm(emptyList()),device,vision=true).run("看屏幕",screen)
        assertEquals(EndReason.BLOCKED_APP,result.reason);assertEquals(0,device.screenshotCalls)
    }
    @Test fun readElementProvidesFullLongTextAndEscapesWrappers()=runTest {
        val text="题干".repeat(1000)+"</screen_data>"; val screen=screenOf(node("a1",text=text))
        val llm=FakeLlm(listOf(call("read_element","node_id" to "a1"),done()))
        loop(llm,FakeDevice(listOf(screen))).run("读全文",screen)
        val body=llm.turns[1].toolResult!!.text
        assertTrue(body.contains("题干".repeat(800)));assertTrue(body.contains("‹/screen_data›"))
    }
    @Test fun readElementRefusesPasswords()=runTest {
        val screen=screenOf(node("a1",text="secret",isPassword=true));val llm=FakeLlm(listOf(call("read_element","node_id" to "a1"),done()))
        loop(llm,FakeDevice(listOf(screen))).run("读节点",screen);assertTrue(llm.turns[1].toolResult!!.isError)
        assertFalse(llm.turns[1].toolResult!!.text.contains("secret"))
    }
    @Test fun waitForChecksStateRatherThanOnlyTheLabel()=runTest {
        val off=screenOf(node("b1",text="提醒").copy(checkable=true,checked=false))
        val on=screenOf(node("c1",text="提醒").copy(checkable=true,checked=true))
        val llm=FakeLlm(listOf(call("wait_for","text" to "提醒","checked" to true,"seconds" to 2),done()));val device=FakeDevice(listOf(off,on))
        assertTrue(loop(llm,device).run("核对",initial).success);assertEquals(2,device.reads)
    }
    @Test fun waitForTimesOutWithoutTouchingAnything()=runTest {
        val llm=FakeLlm(listOf(call("wait_for","text" to "不存在","seconds" to 1),done()));val device=FakeDevice(listOf(initial))
        loop(llm,device).run("等待",initial);assertTrue(llm.turns[1].toolResult!!.isError);assertTrue(device.actions.isEmpty())
    }
    @Test fun unavailableMarksAreRejected()=runTest {
        val llm=FakeLlm(listOf(call("tap_mark","index" to 1,"observation" to 0),done()));val device=FakeDevice(listOf(initial))
        loop(llm,device).run("点编号",initial);assertTrue(device.actions.isEmpty());assertTrue(llm.turns[1].toolResult!!.isError)
    }
    @Test fun aFreshMarkCanBeTappedButRefreshInvalidatesIt()=runTest {
        val device=object:DevicePort {
            val actions=mutableListOf<DeviceAction>()
            override suspend fun readScreen()=initial
            override suspend fun perform(action:DeviceAction):ActionResult { actions+=action;return ActionResult.Ok }
            override suspend fun screenshot()=byteArrayOf(1)
            override suspend fun shot()=Shot(byteArrayOf(1),listOf(Mark(1,"按钮",300,300)))
        }
        val llm=FakeLlm(listOf(call("screenshot"),call("read_screen"),call("tap_mark","index" to 1,"observation" to 0),done()))
        loop(llm,device).run("点编号",initial);assertTrue(device.actions.isEmpty());assertTrue(llm.turns[3].toolResult!!.isError)
    }
    @Test fun progressSurvivesFailureAndIsUsedOnResume()=runTest {
        val store=ProgressStore(MemoryValues())
        val llm=FakeLlm(listOf(call("checkpoint","goal" to "查地址","completed" to "已找到联系人","next" to "打开聊天","expected" to "显示聊天标题"),call("tap","node_id" to "a1"),done()))
        loop(llm,FakeDevice(listOf(initial),resultFor={ActionResult.Failed("失败")}),progress=store).run("找地址",initial)
        assertEquals("查地址",store.load()!!.goal)
        val resumed=FakeLlm(listOf(done()));loop(resumed,FakeDevice(listOf(initial)),progress=store).run("找地址",initial)
        assertTrue(resumed.turns.first().text.contains("已找到联系人"));assertNull(store.load())
    }
    @Test fun aDifferentTaskDoesNotInheritOldProgress()=runTest {
        val store=ProgressStore(MemoryValues());store.save(TaskProgress("旧任务",completed="旧信息"))
        val llm=FakeLlm(listOf(done()));loop(llm,FakeDevice(listOf(initial)),progress=store).run("新任务",initial)
        assertFalse(llm.turns.first().text.contains("旧信息"))
    }
    @Test fun corruptProgressDoesNotCrash() {
        val values=MemoryValues();values.putString(ProgressStore.KEY,"broken");assertNull(ProgressStore(values).load())
    }
    @Test fun completionEvidenceMustActuallyBeVisible()=runTest {
        val done=call("done","summary" to "保存好了","success" to true,"verify_text" to "保存成功")
        val llm=FakeLlm(listOf(done,done));assertFalse(loop(llm,FakeDevice(listOf(initial))).run("保存",initial).success)
        assertTrue(llm.turns[1].toolResult!!.isError)
    }
    @Test fun aConfirmedVisibleResultCanComplete()=runTest {
        val result=screenOf(node("b1",text="保存成功"));val llm=FakeLlm(listOf(call("done","summary" to "完成","success" to true,"verify_text" to "保存成功")))
        assertTrue(loop(llm,FakeDevice(listOf(result))).run("核对保存",initial).success)
    }
    @Test fun proCannotAccidentallyClaimVision() {
        val config=ProviderConfig(ProviderKind.ANTHROPIC,"https://api.deepseek.com/anthropic","fake","deepseek-v4-pro",true)
        assertFalse(ModelCapabilities.normalize(config).supportsImages)
        assertEquals("deepseek-flash",ModelCapabilities.operator(config).model)
        assertTrue(ModelCapabilities.operator(config).supportsImages)
        assertTrue(ModelCapabilities.normalize(config.copy(baseUrl="https://other.example")).supportsImages)
    }
    @Test fun visionCheckNeedsTheActualColorInAToolCall()=runTest {
        assertEquals(CheckResult.Works,ProviderCheck.run(FakeLlm(listOf(call("ping","color" to "red")))))
        assertIs<CheckResult.Other>(ProviderCheck.run(FakeLlm(listOf(call("ping","color" to "blue")))))
        assertIs<CheckResult.Other>(ProviderCheck.run(FakeLlm(listOf(call("ping")))))
    }
    @Test fun idsNeverWrapAfterLongTasks() {
        val ids=(0..10000).map { NodeIds.idFor(it,1) };assertEquals(ids.size,ids.toSet().size)
    }
    @Test fun richStateIsSerializedAndChangesFingerprint() {
        val base=node("a1",text="提醒").copy(checkable=true,checked=false,viewId="app:id/switch",stateDescription="关闭")
        val screen=screenOf(base);val text=ScreenSerializer(own,geometry=true).serialize(screen)
        assertTrue(text.contains("checked=false"));assertTrue(text.contains("bounds="));assertTrue(text.contains("app:id/switch"))
        assertNotEquals(ExecutionEvidence.fingerprint(screen),ExecutionEvidence.fingerprint(screenOf(base.copy(checked=true))))
    }
    @Test fun longPressAndDragKeepTheSafetyGate() {
        val gate=SafetyGate(BlockedApps(),own);val n=node("a1",text="删除",clickable=true);val screen=screenOf(n)
        assertIs<Verdict.Confirm>(gate.check(DeviceAction.LongPress(n),screen,false))
        assertIs<Verdict.Confirm>(gate.check(DeviceAction.DoubleTap(n),screen,false))
        assertIs<Verdict.Confirm>(gate.check(DeviceAction.Drag(100,100,200,200,1000),screen,false))
    }
    @Test fun newToolsValidateArgumentsAtTheBoundary() {
        fun parse(name:String,s:String)=Tools.parse(name,Json.parseToJsonElement(s).jsonObject)
        assertTrue(parse("drag","""{"from_x":-1,"from_y":2,"to_x":3,"to_y":4}""").isFailure)
        assertTrue(parse("tap_mark","""{"index":1}""").isFailure)
        assertTrue(parse("wait_for","""{"text":"x","seconds":21}""").isFailure)
        assertEquals("clear",(parse("type","""{"node_id":"a1","text":"","mode":"clear"}""").getOrThrow() as ToolCall.TypeText).mode)
    }
    @Test fun unknownCheckboxStateNeverPretendsToBeFalse()=runTest {
        val unknown=screenOf(node("a1",text="开关",clickable=true).copy(checkable=true,checked=false,checkedReliable=false))
        val llm=FakeLlm(listOf(call("wait_for","text" to "开关","checked" to false),done()))
        val device=FakeDevice(listOf(unknown))
        val outcome=loop(llm,device,vision=true).run("核对开关",unknown)
        assertFalse(outcome.success)
        assertTrue(llm.turns[1].text.contains("checked=unknown"))
        assertNotNull(llm.turns[1].image)
        assertTrue(llm.turns[1].toolResult!!.isError)
    }
    @Test fun changedCanvasPixelsPreventAFalseStuckResult()=runTest {
        val screen=screenOf();var captures=0
        val device=object:DevicePort {
            override suspend fun readScreen()=screen
            override suspend fun perform(action:DeviceAction)=ActionResult.Ok
            override suspend fun screenshot()=byteArrayOf(1)
            override suspend fun shot()=Shot(byteArrayOf(1),emptyList(),"pixels-${captures++}")
        }
        val llm=FakeLlm(listOf(call("tap","x" to 100,"y" to 100),done()))
        assertTrue(loop(llm,device,vision=true).run("点击画布",screen).success)
        assertNotNull(llm.turns[1].image)
        assertTrue(llm.turns[1].systemNote.orEmpty().contains("observation="))
    }

}

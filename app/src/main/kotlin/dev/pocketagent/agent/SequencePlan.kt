package dev.pocketagent.agent

import dev.pocketagent.device.Direction
import kotlinx.serialization.json.*

data class ElementQuery(val text:String="",val role:String?=null,val editable:Boolean?=null,val viewId:String?=null)
data class SequenceStep(val action:String,val target:ElementQuery?=null,val text:String="",val mode:String="replace",
                        val direction:Direction=Direction.DOWN,val app:String="",val expected:String?=null)

/** Small semantic plans only. No old node ids, coordinates, submit flags, file writes or irreversible overrides. */
internal object SequencePlan {
    private fun bad(message:String):Nothing = throw IllegalArgumentException(message)
    private val roles=listOf("input","button","text","image","list","group","checkbox")
    fun parse(value:JsonElement):List<SequenceStep> {
        val array=value as? JsonArray ?: bad("steps 必须是数组")
        require(array.size in 1..4) { "连续操作需要 1 到 4 步" }
        return array.map { raw ->
            val step=raw as? JsonObject ?: bad("每步必须是对象")
            val action=string(step,"action",30,true)!!
            val allowed=when(action) {
                "tap"->setOf("action","target","expect_text")
                "type"->setOf("action","target","text","mode","expect_text")
                "scroll"->setOf("action","target","direction","expect_text")
                "back"->setOf("action","expect_text")
                "open_app"->setOf("action","app","expect_text")
                else->bad("不支持的连续动作")
            }
            require(step.keys.all { it in allowed }) { "连续操作含有不支持的字段" }
            val query=step["target"]?.let(::query)
            if(action in setOf("tap","type")) require(query!=null) { "点击和输入必须提供目标条件" }
            val text=string(step,"text",4000,action=="type",empty=true).orEmpty()
            val mode=string(step,"mode",20)?:"replace"
            require(mode in listOf("replace","append","clear")) { "不支持的输入模式" }
            if(action=="type" && mode!="clear")require(text.isNotEmpty()) { "输入内容不能为空" }
            val direction=string(step,"direction",10,action=="scroll")?:"down"
            require(direction in listOf("down","up","left","right")) { "不支持的滚动方向" }
            SequenceStep(action,query,text,mode,Direction.valueOf(direction.uppercase()),
                string(step,"app",100,action=="open_app").orEmpty(),string(step,"expect_text",200))
        }
    }
    private fun query(value:JsonElement):ElementQuery {
        val q=value as? JsonObject ?: bad("target 必须是对象")
        require(q.isNotEmpty() && q.keys.all { it in listOf("text","role","editable","view_id") }) { "目标条件不能为空，也不能使用坐标或旧节点" }
        val role=string(q,"role",20)
        require(role==null || role in roles) { "不支持的控件类型" }
        val editable=q["editable"]?.let { val p=it as? JsonPrimitive;require(p!=null && !p.isString);p.booleanOrNull?:bad("editable 必须是布尔值") }
        return ElementQuery(string(q,"text",100).orEmpty(),role,editable,string(q,"view_id",300))
    }
    private fun string(o:JsonObject,key:String,max:Int,required:Boolean=false,empty:Boolean=false):String? {
        val value=o[key] ?: run { require(!required) { "缺少 $key" };return null }
        val p=value as? JsonPrimitive
        require(p?.isString==true && p.content.length in (if(empty)0 else 1)..max) { "$key 格式或长度不符" }
        return p.content
    }
    val itemSchema:JsonObject=buildJsonObject {
        put("type","object");put("additionalProperties",false)
        putJsonObject("properties") {
            putJsonObject("action") { put("type","string");putJsonArray("enum") { listOf("tap","type","scroll","back","open_app").forEach { add(it) } } }
            putJsonObject("target") {
                put("type","object");put("additionalProperties",false)
                putJsonObject("properties") {
                    putJsonObject("text"){put("type","string")}
                    putJsonObject("role"){put("type","string");putJsonArray("enum"){roles.forEach{add(it)}}}
                    putJsonObject("editable"){put("type","boolean")}
                    putJsonObject("view_id"){put("type","string")}
                }
            }
            for(name in listOf("text","app","expect_text"))putJsonObject(name){put("type","string")}
            putJsonObject("mode"){put("type","string");putJsonArray("enum"){listOf("replace","append","clear").forEach{add(it)}}}
            putJsonObject("direction"){put("type","string");putJsonArray("enum"){listOf("up","down","left","right").forEach{add(it)}}}
        }
        putJsonArray("required"){add("action")}
    }
}

package dev.pocketagent.agent

import dev.pocketagent.device.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Only hashes and input identity are saved, never a screen dump or the message body. */
@Serializable
data class PendingMessageReceipt(
    val packageName:String,
    val bodyHash:String,
    val inputId:String?=null,
    val inputBounds:List<Int> = emptyList(),
    val beforeMessages:Int=0,
    val beforeErrors:Int=0,
    val beforePending:Int=0,
    val conversationHash:String?=null,
    val bodyLength:Int=0,
)

/** A new exact message plus a cleared/rebuilt composer; a Send button need not survive the send. */
internal class MessageEffect private constructor(val receipt:PendingMessageReceipt) {
    fun verified(screen:Screen):Boolean {
        val r=receipt
        if(screen.packageName!=r.packageName || errors(screen)>r.beforeErrors || pending(screen)>r.beforePending)return false
        if(isQq(screen.packageName) && r.conversationHash==null)return false
        if(r.conversationHash!=null && titleHash(screen)!=r.conversationHash)return false
        if(messages(screen,r.bodyHash,r.bodyLength)<=r.beforeMessages)return false
        val fields=screen.all().filter { it.editable && !it.isPassword && !isSearch(it) }.toList()
        val byId=r.inputId?.let { id->fields.filter { it.viewId==id }.singleOrNull() }
        val sameBounds=fields.filter { bounds(it)==r.inputBounds }.singleOrNull()
        val input=byId ?: sameBounds ?: ChatTargets.composer(screen) ?: fields.singleOrNull()
        if(input!=null)return input.text.isNullOrBlank() || (isQq(screen.packageName) && input.text==input.hint)
        // Some chat apps replace/hide the editor after sending. Require the same readable conversation.
        return fields.isEmpty() && r.conversationHash!=null && titleHash(screen)==r.conversationHash
    }

    companion object {
        fun isQq(packageName: String) = packageName in setOf("com.tencent.mobileqq", "com.tencent.tim")

        private val QQ_SELF = Regex("^(?:我|本人|我发送的消息|我发送的文本消息|你发送了|You sent)[：:,，]\\s*", RegexOption.IGNORE_CASE)
        private val QQ_TIME = Regex("(?:[，,。]\\s*|\\s+)(?:(?:今天|昨天|上午|下午)\\s*)?\\d{1,2}:\\d{2}$")
        private val QQ_STATUS = Regex("(?:[，,。]\\s*|\\s+)(?:已发送|已送达|已读)$")
        private val FAILURE=Regex("发送失败|未发送|重新发送|not sent|failed to send",RegexOption.IGNORE_CASE)
        private val PENDING=Regex("发送中|正在发送|待发送|sending|pending",RegexOption.IGNORE_CASE)
        private val STATUS=Regex("\\s+(已发送|已送达|已读|sent|delivered|read)$",RegexOption.IGNORE_CASE)
        private fun canonical(text:String)=text.replace("\r\n","\n").replace('\r','\n')
        private fun hash(text:String):String {
            val digest=MessageDigest.getInstance("SHA-256").digest(canonical(text).toByteArray(Charsets.UTF_8))
            val hex="0123456789abcdef"
            return buildString(64) { for(byte in digest){val value=byte.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])} }
        }
        private fun bounds(n:ScreenNode)=listOf(n.bounds.left,n.bounds.top,n.bounds.right,n.bounds.bottom)
        private fun isSearch(n:ScreenNode)=Regex("搜索|查找|search|find",RegexOption.IGNORE_CASE).containsMatchIn(listOfNotNull(n.hint,n.description,n.viewId).joinToString(" "))
        private fun titleHash(screen:Screen):String? {
            val top=screen.root.bounds.top;val height=screen.root.bounds.bottom-top
            val headers=screen.all().filter { !it.isPassword && !it.editable && it.children.isEmpty() && !it.text.isNullOrBlank() &&
                it.role !in setOf("button","image") && it.bounds.top<top+height*0.22 && it.bounds.bottom<=top+height*0.30 }.toList()
            val first=headers.minOfOrNull { it.bounds.top } ?: return null
            return headers.filter { it.bounds.top==first }.singleOrNull()?.text?.let(::hash)
        }
        fun restore(receipt:PendingMessageReceipt)=MessageEffect(receipt)
        fun isSend(target:ScreenNode?)=target?.editable==false && TextTargets.normalized(target.label().orEmpty()) in setOf("发送","发送消息","send","send message")
        private fun draft(screen:Screen,lastTyped:String?):ScreenNode? {
            val fields=screen.all().filter { it.editable && !it.isPassword && !isSearch(it) && !it.text.isNullOrEmpty() }.toList()
            return ChatTargets.composer(screen)?.takeIf { !it.text.isNullOrEmpty() }
                ?: fields.filter { it.focused }.singleOrNull()
                ?: fields.filter { lastTyped!=null && it.text==lastTyped }.singleOrNull()
                ?: fields.singleOrNull()
        }
        private fun create(screen:Screen,input:ScreenNode?,body:String):MessageEffect? {
            if(body.isBlank())return null
            val digest=hash(body)
            val length=canonical(body).length
            return MessageEffect(PendingMessageReceipt(screen.packageName,digest,input?.viewId,input?.let(::bounds).orEmpty(),messages(screen,digest,length),errors(screen),pending(screen),titleHash(screen),length))
        }
        fun begin(screen:Screen,target:ScreenNode?,lastTyped:String?):MessageEffect? {
            if(!isSend(target))return null
            val input=draft(screen,lastTyped)
            return create(screen,input,input?.text ?: lastTyped.orEmpty())
        }
        fun preview(screen:Screen,target:ScreenNode?,lastTyped:String?):String? {
            if(!isSend(target))return null
            return draft(screen,lastTyped)?.text ?: lastTyped?.takeIf { it.isNotBlank() && screen.all().none { n->n.editable && !n.isPassword } }
        }
        fun submission(screen:Screen,field:ScreenNode,body:String):MessageEffect? {
            val nearby=screen.all().any { isSend(it) && kotlin.math.abs(it.bounds.top-field.bounds.top)<=3*maxOf(1,field.bounds.bottom-field.bounds.top,it.bounds.bottom-it.bounds.top) }
            return if(!nearby || field.isPassword)null else create(screen,field,body)
        }
        private fun matches(value:String?,digest:String,length:Int,description:Boolean=false):Boolean {
            if(value==null)return false
            val text=canonical(value)
            fun same(candidate:String)=(length==0 || candidate.length==length) && hash(candidate)==digest
            if(same(text))return true
            val bare=STATUS.replace(text,"")
            if(bare!=text && same(bare))return true
            if(description) {
                val content=bare.replace(Regex("^(我|本人|me|you)[:：]\\s*",RegexOption.IGNORE_CASE),"")
                if(content!=bare && same(content))return true
            }
            return false
        }
        private fun messages(screen:Screen,digest:String,length:Int):Int {
            fun hasEditor(n: ScreenNode): Boolean = n.editable || n.isPassword || n.children.any(::hasEditor)
            val qq = isQq(screen.packageName)
            val root = screen.root.bounds
            val midpoint = root.left + (root.right - root.left) / 2
            val matched=screen.all().filter { n->!n.editable && !n.isPassword && (!qq || !hasEditor(n)) }.filter { n ->
                val plain = matches(n.text,digest,length) || matches(n.description,digest,length,!qq) || splitMessage(n,digest,length)
                if (!qq) return@filter plain
                val desc = n.description.orEmpty()
                val own = QQ_SELF.containsMatchIn(desc)
                val bare = QQ_SELF.replace(desc, "")
                val withoutStatus = QQ_STATUS.replace(bare, "")
                val withoutTime = QQ_TIME.replace(withoutStatus, "")
                val ownMatches = own && listOf(bare, withoutStatus, withoutTime, QQ_STATUS.replace(withoutTime, ""))
                    .any { matches(it,digest,length) }
                val rightBubble = n.bounds.left >= midpoint && n.bounds.top >= root.top + (root.bottom - root.top) / 6 &&
                    desc.indexOfAny(charArrayOf('：', ':')) < 0
                ownMatches || (rightBubble && plain)
            }.toList()
            fun contains(parent:ScreenNode,id:String):Boolean=parent.children.any { it.id==id || contains(it,id) }
            return matched.count { n->matched.none { it.id!=n.id && contains(n,it.id) } }
        }
        private fun splitMessage(node:ScreenNode,digest:String,length:Int):Boolean {
            if(node.children.size<2 || !Regex("message|msg_content|chat_content",RegexOption.IGNORE_CASE).containsMatchIn(node.viewId.orEmpty()))return false
            val parts=mutableListOf<String>()
            fun collect(n:ScreenNode) { if(n.editable || n.isPassword)return;if(n.children.isEmpty())n.text?.let { parts+=it } else n.children.forEach(::collect) }
            node.children.forEach(::collect)
            return parts.size>1 && matches(parts.joinToString("\n"),digest,length)
        }
        private fun errors(screen:Screen)=screen.all().count { !it.editable && !it.isPassword && FAILURE.containsMatchIn(it.label().orEmpty()) }
        private fun pending(screen:Screen)=screen.all().count { !it.editable && !it.isPassword && PENDING.matches(it.label().orEmpty()) }
    }
}

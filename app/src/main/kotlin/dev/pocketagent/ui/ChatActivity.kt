package dev.pocketagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.pocketagent.R
import dev.pocketagent.android.PrefsKeyValueStore
import dev.pocketagent.android.SetupStores
import dev.pocketagent.chat.*
import dev.pocketagent.llm.*
import dev.pocketagent.ui.kit.Kit
import kotlinx.coroutines.*

/** Q&A is an ordinary app screen. It never starts the phone agent or asks for accessibility. */
class ChatActivity:ThemedActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var controller:ChatController
    private lateinit var list:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var input:EditText
    private lateinit var send:TextView
    private lateinit var stop:TextView
    private lateinit var retry:TextView
    private lateinit var status:TextView
    private lateinit var model:TextView
    private var lastMessages:List<ChatMessage>?=null
    private var configuration:ProviderConfig?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val store=ChatStore(PrefsKeyValueStore(getSharedPreferences("dsh_chat",MODE_PRIVATE)))
        controller=ChatController(scope,{
            val config=SetupStores.repository(this).load() ?: throw IllegalArgumentException()
            Providers.create(config,HttpCaller(OkHttpTransport()))
        },store,contextTokens={SetupStores.repository(this).load()?.contextTokens ?: 128_000})
        window.setDecorFitsSystemWindows(false)
        val root=Kit.vstack(this).apply {
            setBackgroundColor(Kit.color(this@ChatActivity,R.color.pa_bg))
            setPadding(Kit.dp(this@ChatActivity,16),0,Kit.dp(this@ChatActivity,16),0)
        }
        root.setOnApplyWindowInsetsListener { view,insets->
            val bars=insets.getInsets(WindowInsets.Type.systemBars());val ime=insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(Kit.dp(this,16)+bars.left,bars.top,Kit.dp(this,16)+bars.right,maxOf(bars.bottom,ime.bottom));insets
        }
        val header=Kit.hstack(this,10)
        header.addView(Kit.secondary(this,"返回",44){finish()},Kit.lp(Kit.dp(this,64)))
        header.addView(Kit.text(this,"聊天答疑",22f,600),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Kit.secondary(this,"清空",44){
            android.app.AlertDialog.Builder(this).setTitle("清空聊天记录？").setMessage("这会删除本机的这段会话，并停止当前回复。")
                .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->controller.clear()}.show()
        },Kit.lp(Kit.dp(this,64)))
        root.addView(header,Kit.lp().apply { topMargin=Kit.dp(this@ChatActivity,10) })
        model=Kit.text(this,"",12f,400,R.color.pa_muted).apply { setPadding(0,Kit.dp(this@ChatActivity,8),0,Kit.dp(this@ChatActivity,10)) }
        root.addView(model)
        list=Kit.vstack(this,12)
        scroll=ScrollView(this).apply { isFillViewport=true;addView(list,FrameLayout.LayoutParams(-1,-2)) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        status=Kit.text(this,"",14f,400,R.color.pa_muted).apply { setPadding(0,Kit.dp(this@ChatActivity,8),0,Kit.dp(this@ChatActivity,8)) }
        root.addView(status)
        val editor=Kit.vstack(this).apply {
            background=Kit.rounded(Kit.color(this@ChatActivity,R.color.pa_card),Kit.dpf(this@ChatActivity,20))
            val p=Kit.dp(this@ChatActivity,12);setPadding(p,p,p,p)
        }
        input=Kit.input(this,"输入问题，支持连续追问",17f).apply {
            contentDescription="聊天问题";filters=arrayOf(InputFilter.LengthFilter(ChatController.MAX_QUESTION));minLines=2;maxLines=5
        }
        input.setText(savedInstanceState?.getString("draft").orEmpty())
        editor.addView(input,Kit.lp())
        val buttons=Kit.hstack(this,8)
        buttons.addView(Kit.secondary(this,"模型设置",44){startActivity(Intent(this,SetupActivity::class.java))},Kit.lp(Kit.dp(this,90)))
        buttons.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        retry=Kit.secondary(this,"重试",44){controller.retry()}.apply { contentDescription="重试回答" }
        buttons.addView(retry,Kit.lp(Kit.dp(this,68)))
        stop=Kit.secondary(this,"停止",44){controller.stop()}.apply { contentDescription="停止生成" }
        buttons.addView(stop,Kit.lp(Kit.dp(this,68)))
        send=Kit.primary(this,"发送",44){
            if(controller.send(input.text.toString()))input.setText("")
        }.apply { contentDescription="发送问题" }
        buttons.addView(send,Kit.lp(Kit.dp(this,68)))
        editor.addView(buttons,Kit.lp().apply { topMargin=Kit.dp(this@ChatActivity,8) })
        root.addView(editor,Kit.lp().apply { topMargin=Kit.dp(this@ChatActivity,6);bottomMargin=Kit.dp(this@ChatActivity,8) })
        setContentView(root)
        scope.launch { controller.state.collect(::render) }
    }

    override fun onResume() {
        super.onResume()
        visible=true
        val config=SetupStores.repository(this).load()
        if(configuration!=config) { controller.resetConnection();configuration=config }
        model.text=config?.let { "模型：${it.model}" } ?: "尚未配置模型，可点下方模型设置。"
    }
    override fun onPause() { visible=false;super.onPause() }
    private fun render(state:ChatState) {
        send.isEnabled=!state.busy;send.alpha=if(state.busy).45f else 1f
        input.isEnabled=!state.busy
        stop.visibility=if(state.busy)View.VISIBLE else View.GONE
        retry.visibility=if(!state.busy && state.messages.lastOrNull()?.role=="user")View.VISIBLE else View.GONE
        status.text=if(state.busy)"正在回答…" else state.error.orEmpty()
        status.visibility=if(status.text.isBlank())View.GONE else View.VISIBLE
        if(lastMessages==state.messages)return
        lastMessages=state.messages
        list.removeAllViews()
        if(state.messages.isEmpty()) {
            list.addView(Kit.text(this,"有什么想问的？",24f,600).apply { setPadding(0,Kit.dp(this@ChatActivity,36),0,Kit.dp(this@ChatActivity,12)) })
            list.addView(Kit.text(this,"可以解释概念、分析问题、修改文字或检查代码。连续追问会参考当前会话。",16f,400,R.color.pa_muted))
            list.addView(Kit.text(this,"问题会发给设置中的模型服务。聊天记录仅保存在本机，可随时清空。",13f,400,R.color.pa_muted).apply { setPadding(0,Kit.dp(this@ChatActivity,16),0,0) })
        }
        state.messages.forEach { message->
            val card=Kit.vstack(this).apply {
                val p=Kit.dp(this@ChatActivity,14);setPadding(p,p,p,p)
                background=Kit.rounded(Kit.color(this@ChatActivity,if(message.role=="user")R.color.pa_fill else R.color.pa_card),Kit.dpf(this@ChatActivity,18))
            }
            val head=Kit.hstack(this)
            head.addView(Kit.text(this,if(message.role=="user")"你" else "dsh",12f,600,R.color.pa_muted),LinearLayout.LayoutParams(0,-2,1f))
            head.addView(Kit.text(this,"复制",12f,500,R.color.pa_blue).apply {
                setPadding(Kit.dp(this@ChatActivity,12),0,0,0)
                setOnClickListener {
                    val clip=ClipData.newPlainText("dsh 聊天",message.text)
                    clip.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(this@ChatActivity,"已复制",Toast.LENGTH_SHORT).show()
                }
            })
            card.addView(head)
            card.addView(Kit.text(this,message.text,17f).apply { setTextIsSelectable(true);setPadding(0,Kit.dp(this@ChatActivity,8),0,0) })
            list.addView(card,Kit.lp())
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("draft",input.text.toString());super.onSaveInstanceState(outState) }
    override fun onStop() { controller.stop();super.onStop() }
    override fun onDestroy() { controller.stop();scope.cancel();super.onDestroy() }
    companion object { @Volatile internal var visible=false }
}

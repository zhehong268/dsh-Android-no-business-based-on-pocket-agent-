package dev.dsh.phonefixture

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/** Synthetic local contacts only. No account, network permission, real contacts or message transport. */
class FixtureActivity:Activity() {
    private lateinit var root:LinearLayout
    private lateinit var state:TextView
    private var dark=false
    private var contacts=0;private var searches=0;private var opened=0;private var pasted=0;private var setTextCalls=0;private var keys=0;private var sends=0;private var toggles=0
    private var person="张小明"
    private var scene="contacts"
    private val history=mutableListOf<String>()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        dark=intent.getStringExtra("theme")=="dark"
        scene=intent.getStringExtra("scene")?:"contacts"
        when(scene){"chat","voice","ambiguous","hidden","delayed","animated"->chat(scene=="voice",scene=="ambiguous");else->home()}
        if(scene=="animated") {
            var tick=0
            val animate=object:Runnable { override fun run(){state.text="fixture_state tick=${++tick}";handler.postDelayed(this,20)} }
            handler.post(animate)
        }
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun base(title:String) {
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=PatternBackground(dark)
        }
        setContentView(root)
        root.addView(label(title,24f))
        state=label("",11f).apply { id=R.id.fixture_state }
        root.addView(state);updateState()
    }
    private fun updateState() {
        state.text="fixture_state contacts=$contacts search=$searches opened=$opened paste=$pasted set_text=$setTextCalls keys=$keys sent=$sends toggle=$toggles recipient=$person"
    }
    private fun label(text:String,size:Float=20f)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(if(dark)Color.WHITE else Color.BLACK)
        typeface=Typeface.create("cursive",Typeface.NORMAL);paint.textSkewX=-0.18f
        setPadding(dp(4),dp(4),dp(4),dp(4))
    }
    private fun button(text:String,click:()->Unit)=Button(this).apply { this.text=text;isAllCaps=false;setOnClickListener { click() } }
    private fun home() {
        base("本地原生聊天联调")
        root.addView(label("当前最近会话没有目标联系人"))
        root.addView(Space(this),LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("通讯录") { contacts++;directory(false) })
    }
    private fun directory(search:Boolean) {
        base("通讯录")
        root.addView(button("搜索") { searches++;directory(true) })
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(search) {
            val field=EditText(this).apply { id=R.id.contact_search;hint="搜索联系人";setSingleLine();showSoftInputOnFocus=false }
            field.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { populate(results,s?.toString().orEmpty()) }
                override fun afterTextChanged(s:Editable?){}
            })
            root.addView(field)
        }
        root.addView(results)
        populate(results,if(search)"" else "只显示搜索入口")
    }
    private fun populate(list:LinearLayout,query:String) {
        list.removeAllViews()
        val people=if(scene=="duplicates")listOf("同名联系人" to "研发","同名联系人" to "销售") else listOf("张小明" to "测试联系人","张小明二" to "另一个人")
        if(query.isBlank())return
        for((name,note) in people.filter { it.first.contains(query) }) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;isClickable=true;setPadding(dp(8),dp(8),dp(8),dp(8));setOnClickListener { person=name;opened++;profile(note) } }
            row.addView(label(name));row.addView(label(note,16f));list.addView(row)
        }
    }
    private fun profile(note:String) {
        base("详细资料");root.addView(label(person));root.addView(label(note));root.addView(button("发消息") { chat() })
    }
    private fun chat(voice:Boolean=false,ambiguous:Boolean=false) {
        base(person)
        val search=EditText(this).apply { hint="搜索聊天记录";setSingleLine();showSoftInputOnFocus=false }
        root.addView(search)
        val messages=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        history.forEach { messages.addView(label(it+" 已发送")) }
        root.addView(messages,LinearLayout.LayoutParams(-1,0,1f))
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.BOTTOM }
        root.addView(row)
        if(voice) {
            row.addView(button("切换到键盘") { toggles++;chat() })
            row.addView(label("按住说话"));return
        }
        val body=PasteOnlyEditor().apply { id=R.id.message_body;if(scene=="hidden")importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        row.addView(body,LinearLayout.LayoutParams(0,dp(76),1f))
        if(ambiguous)row.addView(PasteOnlyEditor().apply { id=R.id.message_extra },LinearLayout.LayoutParams(0,dp(76),1f))
        row.addView(button("发送") {
            val text=body.text?.toString().orEmpty()
            if(text.isNotEmpty()) {
                history+=text;sends++;body.setText("");updateState()
                if(scene=="delayed") {
                    row.removeAllViews();row.addView(button("更多功能"){})
                    handler.postDelayed({messages.addView(label("").apply { contentDescription="我：$text";minimumHeight=dp(60) })},180)
                } else messages.addView(label(text+" 已发送"))
            }
        })
    }
    private inner class PasteOnlyEditor:EditText(this@FixtureActivity) {
        init { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;showSoftInputOnFocus=false;setTextColor(if(dark)Color.WHITE else Color.BLACK);setBackgroundColor(if(dark)0xFF303030.toInt() else 0xFFFAFAFA.toInt());textSize=20f }
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className="android.view.View";info.isEditable=false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        }
        override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
            if(action==AccessibilityNodeInfo.ACTION_SET_TEXT){setTextCalls++;updateState();return false}
            if(action==AccessibilityNodeInfo.ACTION_PASTE){val ok=super.performAccessibilityAction(action,args);if(ok)pasted++;updateState();return ok}
            return super.performAccessibilityAction(action,args)
        }
        override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean { if(event.unicodeChar!=0){keys++;updateState()};return super.onKeyDown(keyCode,event) }
    }
    private class PatternBackground(private val dark:Boolean):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas) {
            canvas.drawColor(if(dark)0xFF152124.toInt() else 0xFFEAF4EF.toInt())
            paint.color=if(dark)0xFF244047.toInt() else 0xFFCFE3DA.toInt()
            for(x in 10..bounds.right step 75)for(y in 15..bounds.bottom step 95)canvas.drawCircle(x.toFloat(),y.toFloat(),18f,paint)
        }
        override fun setAlpha(alpha:Int){};override fun setColorFilter(colorFilter:ColorFilter?){}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
    }
}

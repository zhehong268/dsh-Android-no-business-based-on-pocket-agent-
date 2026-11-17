package dev.pocketagent.android

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Build
import android.os.SystemClock
import dev.pocketagent.device.FontReference
import dev.pocketagent.device.Screen
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Public configuration and readable font assets only; no storage permission or private app directory. */
internal class VisualEnvironment(private val context:Context) {
    private var colorsAt=Long.MIN_VALUE
    private var colors="尚未读取"
    private var fontFiles:List<String>?=null

    fun describe():String {
        val config=context.resources.configuration
        val dm=context.resources.displayMetrics
        if(colorsAt==Long.MIN_VALUE || SystemClock.uptimeMillis()-colorsAt>60_000) {
            colorsAt=SystemClock.uptimeMillis()
            colors=try {
                val palette=WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                listOfNotNull(palette?.primaryColor,palette?.secondaryColor,palette?.tertiaryColor).joinToString(",") { hex(it.toArgb()) }.ifEmpty { "系统未提供" }
            } catch(_:SecurityException) { "系统限制读取" } catch(_:RuntimeException) { "系统未提供" }
        }
        if(fontFiles==null)fontFiles=try { SystemFonts.getAvailableFonts().mapNotNull { it.file?.name }.distinct().sorted() } catch(_:RuntimeException) { emptyList() }
        return "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}；${dm.widthPixels}×${dm.heightPixels}px；density=${dm.density}；fontScale=${config.fontScale}；"+
            "字重调整=${if(Build.VERSION.SDK_INT>=31)config.fontWeightAdjustment else 0}；"+
            "主题=${if(config.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)"深色" else "浅色"}；"+
            "系统壁纸主色=$colors；可读取的系统字体=${fontFiles.orEmpty().take(18).joinToString(",")}；"+
            "当前实际字形以控件原文和截图为准，应用私有字体或聊天背景原文件未读取。"
    }

    fun fontReference(packageName:String,text:String):FontReference {
        val samples=mutableListOf("系统当前默认字体" to Typeface.DEFAULT)
        try {
            val assets=context.packageManager.getResourcesForApplication(packageName).assets
            val names=(assets.list("fonts").orEmpty().map { "fonts/$it" }+assets.list("").orEmpty().toList())
                .filter { it.endsWith(".ttf",true)||it.endsWith(".otf",true)||it.endsWith(".ttc",true) }.take(2)
            for(name in names)try { samples+=name to Typeface.createFromAsset(assets,name) } catch(_:RuntimeException) { }
        } catch(_:Exception) { }
        val bitmap=Bitmap.createBitmap(1080,160+samples.size*150,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK }
        paint.textSize=28f;canvas.drawText("FONT REFERENCE - NOT SCREEN COORDINATES",24f,40f,paint)
        val shown=text.take(26)
        samples.forEachIndexed { index,(name,typeface)->
            paint.typeface=Typeface.DEFAULT;paint.textSize=25f
            canvas.drawText(name.take(50),24f,100f+index*150,paint)
            paint.typeface=typeface;paint.textSize=48f
            canvas.drawText(shown,24f,165f+index*150,paint)
        }
        val bytes=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() };bitmap.recycle()
        return FontReference(describe()+"\n已加载的字体参照："+samples.joinToString { it.first }+"。参照图不属于屏幕，不能按这张图点击。优先读取运行控件原始 Unicode 文本。",bytes)
    }

    fun background(bitmap:Bitmap,screen:Screen):String {
        val excluded=screen.all().filter { it.isPassword || it.editable || (it.children.isEmpty()&&!it.label().isNullOrBlank()) }.map { it.bounds }.toList()
        val b=screen.root.bounds;val histogram=mutableMapOf<Int,Int>();var count=0
        val step=maxOf(16,(b.right-b.left)/30)
        for(y in b.top.coerceAtLeast(0) until b.bottom.coerceAtMost(bitmap.height) step step)
            for(x in b.left.coerceAtLeast(0) until b.right.coerceAtMost(bitmap.width) step step) {
                if(excluded.any { it.contains(x,y) })continue
                val pixel=bitmap.getPixel(x,y) and 0x00F0F0F0
                histogram[pixel]=(histogram[pixel]?:0)+1;count++
            }
        return "当前应用可见背景采样（含可见聊天背景）："+histogram.entries.sortedByDescending { it.value }.take(4).joinToString { hex(it.key) }+
            "；采样点=$count；颜色分组=${histogram.size}。这不是控件，不能凭背景颜色定位联系人。"
    }
    private fun hex(value:Int)=String.format(Locale.ROOT,"#%06X",value and 0xFFFFFF)
}

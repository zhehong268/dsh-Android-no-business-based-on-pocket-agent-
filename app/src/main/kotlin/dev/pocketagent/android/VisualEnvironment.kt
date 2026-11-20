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
import dev.pocketagent.device.ScreenNode
import dev.pocketagent.device.ScreenPalette
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Public configuration and readable font assets only; no storage permission or private app directory. */
internal class VisualEnvironment(private val context:Context) {
    private var colorsAt = -1L
    private var colors = ""
    private var fontFiles: List<String>? = null

    @Synchronized
    fun describe(refreshWallpaper: Boolean = false): String {
        val config = context.resources.configuration
        val dm = context.resources.displayMetrics
        val now = SystemClock.uptimeMillis()
        if (refreshWallpaper || colorsAt < 0 || now - colorsAt > 60_000) {
            colorsAt = now
            colors = "桌面壁纸主色=" + wallpaper(WallpaperManager.FLAG_SYSTEM) +
                "；锁屏壁纸主色=" + wallpaper(WallpaperManager.FLAG_LOCK)
        }
        if (fontFiles == null) fontFiles = try {
            SystemFonts.getAvailableFonts().mapNotNull { it.file?.name }.distinct().sorted()
        } catch (_: RuntimeException) { emptyList() }
        return "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}；${dm.widthPixels}×${dm.heightPixels}px；density=${dm.density}；fontScale=${config.fontScale}；" +
            "字重调整=${if(Build.VERSION.SDK_INT>=31)config.fontWeightAdjustment else 0}；" +
            "主题=${if(config.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)"深色" else "浅色"}；" +
            "$colors；系统字体候选=${fontFiles.orEmpty().take(12).joinToString(",")}。" +
            "壁纸主色由系统提供，字体候选不代表应用当前使用字形。"
    }

    private fun wallpaper(which: Int): String = try {
        val palette = WallpaperManager.getInstance(context).getWallpaperColors(which)
        listOfNotNull(palette?.primaryColor, palette?.secondaryColor, palette?.tertiaryColor)
            .joinToString(",") { hex(it.toArgb()) }.ifEmpty { "系统未提供" }
    } catch (_: RuntimeException) { "系统未提供" }

    fun textProfile(screen: Screen, refreshWallpaper: Boolean): String {
        val families = screen.all().filterNot { it.isPassword || it.editable }
            .flatMap { it.fontFamilies.asSequence() }.distinct().take(5).toList()
        return describe(refreshWallpaper) + "\n当前控件公开的字族：" +
            families.joinToString(", ").ifEmpty { "未上报；使用控件原文，必要时对照实际截图" } + "。" +
            "\n键盘：" + if (screen.keyboardVisible) "已展开；区域以系统窗口为准。"
            else "未显示；不打开键盘检查皮肤。"
    }

    fun fontReference(screen: Screen, text: String, node: ScreenNode?): FontReference {
        val samples=mutableListOf("系统当前默认字体" to Typeface.DEFAULT)
        node?.fontFamilies?.take(2)?.forEach { family ->
            val face = Typeface.create(family, Typeface.NORMAL)
            if (face != Typeface.DEFAULT) samples += "控件上报字体：$family" to face
        }
        try {
            val assets=context.packageManager.getResourcesForApplication(screen.packageName).assets
            val names=(assets.list("fonts").orEmpty().map { "fonts/$it" }+assets.list("").orEmpty().toList())
                .filter { it.endsWith(".ttf",true)||it.endsWith(".otf",true)||it.endsWith(".ttc",true) }.take(2)
            for(name in names)try { samples+=name to Typeface.createFromAsset(assets,name) } catch(_:RuntimeException) { }
        } catch(_:Exception) { }
        val bitmap=Bitmap.createBitmap(1080,160+samples.size*150,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK }
        paint.textSize=28f;canvas.drawText("FONT REFERENCE - NOT SCREEN COORDINATES",24f,40f,paint)
        val shown=text.codePoints().limit(26).toArray().let { String(it, 0, it.size) }
        samples.forEachIndexed { index,(name,typeface)->
            paint.typeface=Typeface.DEFAULT;paint.textSize=25f
            canvas.drawText(name.take(50),24f,100f+index*150,paint)
            paint.typeface=typeface;paint.textSize=48f
            val measured = paint.measureText(shown)
            if (measured > bitmap.width - 48) paint.textSize *= (bitmap.width - 48) / measured
            canvas.drawText(shown,24f,165f+index*150,paint)
        }
        val bytes=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() };bitmap.recycle()
        return FontReference(textProfile(screen, false) + "\n所选控件上报字族：" +
            node?.fontFamilies.orEmpty().joinToString(", ").ifEmpty { "未上报" } +
            "\n已加载的字体参照：" + samples.joinToString { it.first } +
            "。字体参照不是实际屏幕；以节点原文和截图为准。", bytes)
    }

    fun visibleProfile(bitmap: Bitmap, screen: Screen): String {
        val excluded = screen.all().filter { it.isPassword || it.editable ||
            (it.children.isEmpty() && !it.label().isNullOrBlank()) }.map { it.bounds }.toList()
        val background = ScreenPalette.sample(bitmap.width, bitmap.height, listOf(screen.root.bounds),
            excluded + screen.keyboardBounds, bitmap::getPixel)
        val keyboard = if (screen.keyboardVisible) ScreenPalette.sample(bitmap.width, bitmap.height,
            screen.keyboardBounds, pixel = bitmap::getPixel) else null
        val contrast = if (background == null) "可见样本不足" else paletteText(background)
        val keyboardHint = if (!screen.keyboardVisible) "未显示" else if (keyboard == null) "已显示，配色样本不足"
            else paletteText(keyboard)
        return "当前应用可见背景采样（含可见聊天背景）：$contrast。仅为像素摘要，不是壁纸原图。\n" +
            "可见键盘外观：$keyboardHint。键盘配色仅用作遮挡提示，不从里面读取文字、候选词或按键。"
    }

    private fun paletteText(palette: ScreenPalette): String =
        (if (palette.light) "偏亮" else "偏暗") + "；" +
            (if (palette.patterned) "颜色变化较多" else "主色集中") + "；近似主色=" + palette.colors.joinToString(", ", transform = ::hex)

    private fun hex(value:Int)=String.format(Locale.ROOT,"#%06X",value and 0xFFFFFF)
}

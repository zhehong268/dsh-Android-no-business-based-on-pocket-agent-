package dev.pocketagent.android

import dev.pocketagent.device.WebResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the model reads when it searches or fetches a page. These are the parsers, not the network: a change in Bing's
 * RSS or DuckDuckGo's markup should break a test here instead of quietly returning nothing at task time.
 */
class WebTextTest {
    @Test
    fun entitiesAreDecodedIncludingNumericOnes() {
        assertEquals("A & B <c> “d”", WebText.decodeEntities("A &amp; B &lt;c&gt; &ldquo;d&rdquo;"))
        assertEquals("中文", WebText.decodeEntities("&#20013;&#25991;"))
        assertEquals("中文", WebText.decodeEntities("&#x4e2d;&#x6587;"))
        // An unknown or truncated entity stays as written, so a stray & never eats the rest of the line.
        assertEquals("100 & 200 &nope;", WebText.decodeEntities("100 & 200 &nope;"))
    }

    @Test
    fun stripHtmlKeepsTextAndDropsScriptsAndStyles() {
        val html = """
            <html><head><style>body{color:red}</style><script>alert('x')</script></head>
            <body><h1>标题</h1><p>第一段</p><p>第二段 &amp; 尾巴</p><!-- comment --></body></html>
        """.trimIndent()
        val text = WebText.stripHtml(html)
        assertTrue(text.contains("标题"), text)
        assertTrue(text.contains("第一段"), text)
        assertTrue(text.contains("第二段 & 尾巴"), text)
        assertFalse(text.contains("alert"), text)
        assertFalse(text.contains("color:red"), text)
        assertFalse(text.contains("<!--"), text)
    }

    @Test
    fun bingRssReadsTitleLinkAndSnippet() {
        val xml = """
            <rss><channel>
            <item><title>上海天气</title><link>https://weather.example/1</link>
              <description>今天多云，18 到 24 度。</description></item>
            <item><title>明天的天气</title><link>https://weather.example/2</link>
              <description>小雨转阴。</description></item>
            </channel></rss>
        """.trimIndent()
        val entries = WebText.bingRss(xml, 5)
        assertEquals(2, entries.size)
        assertEquals("上海天气", entries[0].title)
        assertEquals("https://weather.example/1", entries[0].link)
        assertEquals("今天多云，18 到 24 度。", entries[0].snippet)
    }

    @Test
    fun bingRssStopsAtTheLimitAndSkipsEmptyTitles() {
        val items = (1..9).joinToString("") { i ->
            if (i == 2) "<item><description>没有标题</description></item>"
            else "<item><title>第 $i 条</title><link>https://e/$i</link></item>"
        }
        val entries = WebText.bingRss("<rss><channel>$items</channel></rss>", 3)
        assertEquals(3, entries.size)
        assertEquals("第 1 条", entries[0].title)
        assertEquals("第 3 条", entries[1].title)
    }

    @Test
    fun duckDuckGoResultsUnwrapTheRedirectAndTakeTheSnippet() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&amp;rut=1">标题一</a>
            <a class="result__snippet">摘要一</a>
            <a class="result__a" href="https://example.com/b">标题二</a>
            <a class="result__snippet">摘要二</a>
        """.trimIndent()
        val entries = WebText.duckDuckGo(html, 5)
        assertEquals(2, entries.size)
        assertEquals("标题一", entries[0].title)
        assertEquals("https://example.com/a", entries[0].link)
        assertEquals("摘要一", entries[0].snippet)
        assertEquals("https://example.com/b", entries[1].link)
    }

    @Test
    fun renderNumbersTheHitsAndMarksThemUntrusted() {
        val text = WebText.render(
            "上海天气",
            listOf(WebText.Entry("标题", "https://e/1", "摘要")),
        )
        assertTrue(text.contains("关于「上海天气」的搜索结果"), text)
        assertTrue(text.contains("1. 标题"), text)
        assertTrue(text.contains("https://e/1"), text)
        assertTrue(text.contains("不可信"), text)
    }

    @Test
    fun searchFallsBackToDuckDuckGoWhenBingHasNoRss() = runBlocking {
        val seen = mutableListOf<String>()
        val web = HttpWeb(reader = { url ->
            seen += url
            when {
                url.startsWith("https://www.bing.com") -> Fetched("", "text/html")
                url.startsWith("https://cn.bing.com") -> Fetched("<rss></rss>", "application/rss+xml")
                else -> Fetched(
                    """<a class="result__a" href="https://example.com/x">结果</a><a class="result__snippet">说明</a>""",
                    "text/html; charset=utf-8",
                )
            }
        })
        val result = web.search("测试", 3)
        assertTrue(result is WebResult.Ok, result.toString())
        assertTrue((result as WebResult.Ok).text.contains("结果"), result.text)
        assertEquals(3, seen.size)
    }

    @Test
    fun searchSaysSoWhenNothingCanBeRead() = runBlocking {
        val web = HttpWeb(reader = { Fetched("", "text/html") })
        val result = web.search("测试", 3)
        assertTrue(result is WebResult.Failed, result.toString())
        assertTrue((result as WebResult.Failed).reason.contains("没解析出结果"), result.reason)
    }

    @Test
    fun fetchReturnsPlainTextCappedWithANote() = runBlocking {
        val web = HttpWeb(reader = { Fetched("<p>${"文".repeat(80)}</p>", "text/html; charset=utf-8") })
        val result = web.fetch("https://example.com/a", 20)
        assertTrue(result is WebResult.Ok, result.toString())
        val text = (result as WebResult.Ok).text
        assertTrue(text.startsWith("文"), text)
        assertTrue(text.contains("已截断"), text)
    }

    @Test
    fun fetchRefusesSomethingThatIsNotAPage() = runBlocking {
        val web = HttpWeb(reader = { Fetched("%PDF-1.7", "application/pdf") })
        val result = web.fetch("https://example.com/a.pdf", 20)
        assertTrue(result is WebResult.Failed, result.toString())
        assertTrue((result as WebResult.Failed).reason.contains("不是网页"), result.reason)
    }
}

package tw.worldbrief.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsStoreTest {
    @Test
    fun keepsLatestVersionOfAnArticleAcrossEditions() {
        val edition = NewsStore.parse(
            """
            {
              "editions": {
                "2026-11-21": {"stories": [
                  {"title":"舊版標題","url":"https://example.com/news#older"}
                ]},
                "2026-11-22": {"updated":"2026-11-22 09:00","stories": [
                  {"title":"新版標題","url":"https://example.com/news","published":"2026-11-22 08:30"},
                  {"title":"另一篇報導","url":"https://example.com/another","published":"2026-11-22 07:00"}
                ]}
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("新版標題", "另一篇報導"), edition.news.map { it.title })
        assertEquals("2026-11-22 09:00", edition.updated)
    }

    @Test
    fun ignoresBlankHeadlinesAndKeepsDistinctArticlesWithoutUrls() {
        val edition = NewsStore.parse(
            """
            {"editions": {"2026-11-22": {"stories": [
              {"url":"https://example.com/incomplete","title":null},
              {"title":"第一篇"},
              {"title":"第二篇"}
            ]}}}
            """.trimIndent()
        )

        assertEquals(listOf("2026-11-22:第一篇", "2026-11-22:第二篇"), edition.news.map { it.id })
    }
}

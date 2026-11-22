package tw.worldbrief.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedNewsTest {
    @Test
    fun aStoryCanMatchSeveralKeywordsWithoutDuplicatingTheEntry() {
        val story = News("1", "2026-11-22", "07:50", "科技", "AI 晶片進入試產", "半導體新製程", "來源", "")
        assertEquals(mapOf("1" to listOf("AI", "半導體")), followMatches(listOf(story), setOf("半導體", "AI")))
    }

    @Test
    fun asciiKeywordDoesNotMatchInsideAnUnrelatedWord() {
        val story = News("1", "2026-11-22", "08:20", "台灣", "臺北交通更新", "", "Taipei News", "", original = "Taipei transit")
        assertEquals(emptyMap<String, List<String>>(), followMatches(listOf(story), setOf("AI", " ")))
    }
}

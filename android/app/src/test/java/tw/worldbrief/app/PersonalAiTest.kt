package tw.worldbrief.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PersonalAiTest {
    @Test
    fun usesHydrogenForNewAndLegacyDefaultSettings() {
        assertEquals("gemini-4-hydrogen", PersonalAi.resolveModel(null))
        assertEquals("gemini-4-hydrogen", PersonalAi.resolveModel("gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", PersonalAi.resolveModel("gemini-3.8-flash"))
    }

    @Test
    fun usesHydrogenGenerationSettings() {
        val config = PersonalAi.generationConfig("gemini-4-hydrogen")

        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertFalse(config.has("temperature"))
        assertFalse(config.has("maxOutputTokens"))
    }
}

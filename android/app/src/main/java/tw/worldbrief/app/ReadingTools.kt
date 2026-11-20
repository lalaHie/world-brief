package tw.worldbrief.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.ibm.icu.text.Transliterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LocalTranslation {
    private val mutex = Mutex()
    suspend fun translate(context: Context, text: String, language: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || language == "zh") return@withContext text
        val key = MessageDigest.getInstance("SHA-256").digest((language + "|" + text).toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("translations", Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { return@withContext it }
        mutex.withLock {
            prefs.getString(key, null)?.let { return@withLock it }
            val source = TranslateLanguage.fromLanguageTag(language) ?: error("不支援這個語言")
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.CHINESE).build())
            try {
                Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()), 120, TimeUnit.SECONDS)
                val translated = Tasks.await(translator.translate(text), 45, TimeUnit.SECONDS)
                val traditional = Transliterator.getInstance("Simplified-Traditional").transliterate(translated)
                prefs.edit().putString(key, traditional).apply()
                traditional
            } finally { translator.close() }
        }
    }
}

object EventLinks {
    // Only suggest potentially related coverage. Similar wording never proves a claim.
    private fun tokens(text: String): Set<String> {
        val lower = text.lowercase()
        val words = Regex("[a-z0-9]{4,}").findAll(lower).map { it.value }.toSet()
        val han = Regex("[\\p{IsHan}]+").findAll(lower).flatMap { it.value.windowed(3).asSequence() }.toSet()
        return words + han
    }
    fun related(story: News, all: List<News>): List<News> {
        val terms = tokens(story.title)
        if (terms.isEmpty()) return emptyList()
        return all.asSequence().filter { it.id != story.id && it.url != story.url }
            .map { item ->
                val other = tokens(item.title)
                item to (terms.intersect(other).size.toDouble() / maxOf(1, minOf(terms.size, other.size)))
            }.filter { it.second >= 0.45 && terms.intersect(tokens(it.first.title)).size >= 2 }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.url }.take(8).toList()
            .sortedByDescending { it.date + it.time }
    }
}

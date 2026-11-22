package tw.worldbrief.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.ibm.icu.text.Transliterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LocalTranslation {
    private val mutex = Mutex()
    suspend fun translate(context: Context, text: String, language: String): String =
        translateAll(context, listOf(text), language).single()

    suspend fun translateAll(context: Context, texts: List<String>, language: String): List<String> =
        withContext(Dispatchers.IO) {
            if (language == "zh" || texts.all { it.isBlank() }) return@withContext texts
            val prefs = context.getSharedPreferences("translations", Context.MODE_PRIVATE)
            val keys = texts.map { text ->
                MessageDigest.getInstance("SHA-256").digest((language + "|" + text).toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            fun cached(index: Int) = if (texts[index].isBlank()) texts[index] else prefs.getString(keys[index], null)
            if (texts.indices.all { cached(it) != null }) return@withContext texts.indices.map { cached(it)!! }
            mutex.withLock {
                if (texts.indices.all { cached(it) != null }) return@withLock texts.indices.map { cached(it)!! }
                val source = TranslateLanguage.fromLanguageTag(language) ?: error("不支援這個語言")
                val options = TranslatorOptions.Builder().setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.CHINESE).build()
                val translator = Translation.getClient(options)
                try {
                    Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()), 120, TimeUnit.SECONDS)
                    val toTraditional = Transliterator.getInstance("Simplified-Traditional")
                    texts.indices.map { index ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        cached(index) ?: run {
                            val result = Tasks.await(translator.translate(texts[index]), 45, TimeUnit.SECONDS)
                            toTraditional.transliterate(result).also { prefs.edit().putString(keys[index], it).apply() }
                        }
                    }
                } finally {
                    translator.close()
                }
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

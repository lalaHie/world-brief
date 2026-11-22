package tw.worldbrief.app

internal const val FOLLOWED_NEWS = "我的追蹤"

internal fun followMatches(news: List<News>, topics: Set<String>): Map<String, List<String>> {
    val terms = topics.sorted().mapNotNull { keyword ->
        val term = keyword.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        // Match a short ASCII keyword such as AI without matching it inside Taipei.
        val word = if (term.all { it.code < 128 }) {
            Regex("(?<![A-Za-z0-9])${Regex.escape(term)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        } else null
        Triple(keyword, term, word)
    }
    if (terms.isEmpty()) return emptyMap()
    return buildMap {
        news.forEach { article ->
            val fields = listOf(article.title, article.original, article.summary, article.category)
            val matches = terms.mapNotNull { (keyword, term, word) ->
                keyword.takeIf { fields.any { text -> word?.containsMatchIn(text) ?: text.contains(term, ignoreCase = true) } }
            }
            if (matches.isNotEmpty()) put(article.id, matches)
        }
    }
}

package nl.beandesk.core.filter

import nl.beandesk.core.model.Place
import java.text.Normalizer

/**
 * Typing in the search field narrows the list at once: every word has to occur in the name or
 * the address. Case and accents do not matter, so "cafe" finds "Café de Zaak".
 */
fun Place.matchesText(query: String): Boolean {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = fold(name) + " " + fold(address.orEmpty())
    return words.all { it in haystack }
}

private val combiningMarks = Regex("\\p{Mn}+")

private fun fold(text: String): String =
    combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

package com.scriptacus.riderunrealangelscript.status

import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk

/** Longest line of details, in characters: a comfortable line to read. */
internal const val STATUS_TOOLTIP_LINE_CHARS = 64

/**
 * Renders a status bar tooltip as a bold headline over details broken into short lines.
 *
 * Status bar tooltips only wrap at 80% of the window, so a few sentences of plain text become one line
 * spanning most of the screen. Constraining the width instead - a `width` on a div, the one constraint
 * the tooltip pane honours - leaves blank space down the right, because the box is always that wide
 * while the text breaks short of it at word boundaries. Breaking the lines here lets the pane size
 * itself to the longest of them.
 */
internal fun statusTooltip(headline: String, details: String): String =
    HtmlBuilder()
        .append(HtmlChunk.text(headline).bold())
        .br()
        .appendWithSeparators(
            HtmlChunk.br(),
            wrapEvenly(details, STATUS_TOOLTIP_LINE_CHARS, minChars = headline.length).map { HtmlChunk.text(it) }
        )
        .wrapWithHtmlBody()
        .toString()

/**
 * Wraps [text] into as few lines as fit in [maxChars], of as even a length as those allow, so the last
 * one is not a stub of a word or two. The wrap width is never taken below [minChars], so details under
 * a headline come out about as wide as it rather than leaving blank space beside them.
 */
internal fun wrapEvenly(text: String, maxChars: Int, minChars: Int = 0): List<String> {
    val lineCount = wrap(text, maxChars).size
    var width = maxChars
    while (width - 1 >= minChars && wrap(text, width - 1).size == lineCount) {
        width--
    }
    return wrap(text, width)
}

/** Greedy word wrap. A word longer than [maxChars] gets a line of its own rather than being split. */
private fun wrap(text: String, maxChars: Int): List<String> {
    val lines = mutableListOf<String>()
    val line = StringBuilder()
    for (word in text.split(' ').filter { it.isNotEmpty() }) {
        if (line.isNotEmpty() && line.length + 1 + word.length > maxChars) {
            lines += line.toString()
            line.clear()
        }
        if (line.isNotEmpty()) {
            line.append(' ')
        }
        line.append(word)
    }
    if (line.isNotEmpty()) {
        lines += line.toString()
    }
    return lines
}

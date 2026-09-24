package com.scriptacus.riderunrealangelscript.status

import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.HintHint
import java.awt.Dimension

/**
 * Status bar tooltips only wrap at 80% of the window, so how a tooltip is laid out is up to the HTML it
 * is given. This renders through the same pane the status bar uses.
 */
class StatusTooltipTest : UsefulTestCase() {

    // A bare application is enough to render a tooltip; Rider's project fixture fails without a solution.
    private val fixture = IdeaTestFixtureFactory.getFixtureFactory().createBareFixture()

    override fun setUp() {
        super.setUp()
        fixture.setUp()
    }

    override fun tearDown() {
        try {
            fixture.tearDown()
        } finally {
            super.tearDown()
        }
    }

    /** A box wider than its longest line is blank space down the right of every tooltip. */
    fun testTooltipIsAsWideAsItsLongestLine() = runInEdtAndWait {
        val headline = "Using the type database saved 4 hours ago"
        val tooltip = render(statusTooltip(headline, CACHED_TYPES))

        val lines = listOf(render(html(HtmlChunk.text(headline).bold()))) +
            wrapEvenly(CACHED_TYPES, STATUS_TOOLTIP_LINE_CHARS, headline.length).map { render(html(HtmlChunk.text(it))) }
        val longestLine = lines.maxOf { it.width }

        assertTrue("rendered ${tooltip.width} wide, longest line ${longestLine}", tooltip.width <= longestLine + 2)
        assertTrue("the details should be on several lines", tooltip.height > 3 * lines.first().height)
        assertTrue("rendered ${tooltip.width} wide", tooltip.width < render(CACHED_TYPES).width / 2)
    }

    /** The last line should not be a stub: shortening the lines as far as their number allows evens them out. */
    fun testLinesAreEvenWithoutBeingMoreOrLonger() {
        for (text in listOf(CACHED_TYPES, NOT_ATTACHED)) {
            val lines = wrapEvenly(text, STATUS_TOOLTIP_LINE_CHARS)
            val greedy = wrapEvenly(text, STATUS_TOOLTIP_LINE_CHARS, minChars = STATUS_TOOLTIP_LINE_CHARS)

            assertEquals(text, lines.joinToString(" "))
            assertEquals(greedy.size, lines.size)
            assertTrue(lines.toString(), lines.all { it.length <= STATUS_TOOLTIP_LINE_CHARS })
            assertTrue(
                "$lines is less even than $greedy",
                lines.maxOf { it.length } - lines.minOf { it.length } <= greedy.maxOf { it.length } - greedy.minOf { it.length }
            )
        }
    }

    /** Details under a headline fill at least its width, rather than leaving blank space beside them. */
    fun testLinesAreNoNarrowerThanTheHeadline() {
        val text = "Semantic highlighting and diagnostics need a running Unreal editor to work."
        assertTrue("evening out alone must go below the headline", wrapEvenly(text, STATUS_TOOLTIP_LINE_CHARS).first().length < 45)

        // Words are not split, so a line can still end short of the headline - but only by the word that did not fit.
        val lines = wrapEvenly(text, STATUS_TOOLTIP_LINE_CHARS, minChars = 45)
        val nextWord = lines[1].substringBefore(' ')
        assertTrue(lines.toString(), lines.first().length + 1 + nextWord.length > 45)
    }

    private fun html(chunk: HtmlChunk): String = HtmlBuilder().append(chunk).wrapWithHtmlBody().toString()

    private fun render(html: String): Dimension = IdeTooltipManager.initPane(html, HintHint(), null).preferredSize

    private companion object {
        const val CACHED_TYPES = "No editor has been reachable this session. Highlighting and diagnostics work, but " +
            "will not reflect engine C++ changed since then. Start the Unreal editor on port 27099 to replace it " +
            "with a live database."
        const val NOT_ATTACHED = "Script files have breakpoints, but they are only hit while 'AngelScript' is " +
            "running under Debug. Click to start it."
    }
}

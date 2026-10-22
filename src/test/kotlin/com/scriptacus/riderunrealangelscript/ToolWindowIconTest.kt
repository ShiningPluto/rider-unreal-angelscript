package com.scriptacus.riderunrealangelscript

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.icons.toStrokeIcon
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.Icon

/**
 * The New UI draws an active tool window's stripe button blue and recolours its icon through
 * toStrokeIcon, which rewrites the stroke and fill colours it finds on each SVG element. A colour set
 * once on the root `<svg>` element is never rewritten, so the icon stayed grey on blue and all but
 * disappeared. This renders the icon through the same call.
 */
class ToolWindowIconTest : UsefulTestCase() {

    // A bare application is enough to load and render icons.
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

    fun testTheActiveToolWindowIconIsDrawnInTheSelectedColour() = runInEdtAndWait {
        val light = IconLoader.getIcon("/icons/expui/toolWindowApi.svg", AngelScriptIcons::class.java.classLoader)
        for ((theme, icon) in listOf("light" to light, "dark" to IconLoader.getDarkIcon(light, true))) {
            assertFalse("The $theme icon draws nothing", opaqueColours(icon).isEmpty())
            assertEquals(
                "The $theme icon keeps its own colours where the stripe recolours it",
                setOf(0xffffff),
                opaqueColours(toStrokeIcon(icon, Color.WHITE))
            )
        }
    }

    private fun opaqueColours(icon: Icon): Set<Int> {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        val colours = hashSetOf<Int>()
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                // Antialiased edges blend with the background; only solid pixels carry the colour.
                if (argb ushr 24 > 200) colours += argb and 0xffffff
            }
        }
        return colours
    }
}

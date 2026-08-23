package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule the widget's three layouts stand on: every step up in size adds a line and
 * never takes one away. A single layout is what let a four-cell tile show less than a
 * two-cell one, so the mapping is worth pinning down — and each orientation of the same
 * placed tile reads its own pair of the host's bounds, or landscape inherits portrait's face.
 */
class WidgetFaceTest {

    @Test
    fun `two by one is the stacked tile`() {
        assertEquals(Face.COMPACT, Face.of(110, 40))
        assertEquals(Face.COMPACT, Face.of(160, 100))   // the same two by one on a Pixel
    }

    @Test
    fun `a wide single row gets the second column`() {
        assertEquals(Face.WIDE, Face.of(250, 40))
        assertEquals(Face.WIDE, Face.of(320, 100))
    }

    @Test
    fun `height, not width, is what buys the chart`() {
        assertEquals(Face.TALL, Face.of(250, 140))
        assertEquals(Face.TALL, Face.of(110, 215))      // narrow and tall still charts
    }

    @Test
    fun `an unmeasured tile falls back to the middle, never the largest`() {
        assertEquals(Face.WIDE, Face.of(0, 0))
        assertEquals(Face.WIDE, Face.of(250, 0))
    }

    @Test
    fun `a landscape cell is judged by its own pair, never portrait's`() {
        // One four-by-two tablet tile; the host bounds it 250–330dp wide and 100–215dp tall.
        // Portrait (minWidth × maxHeight) has the height for the chart; landscape
        // (maxWidth × minHeight) is wide and short and gets the second column — reading the
        // portrait pair for both is what put a TALL chart in a cell 100dp high.
        val faces = Face.of(minWidthDp = 250, maxWidthDp = 330, minHeightDp = 100, maxHeightDp = 215)
        assertEquals(Face.TALL, faces.portrait)
        assertEquals(Face.WIDE, faces.landscape)
    }

    @Test
    fun `when both orientations agree the pair is one face, the single-views path`() {
        // A two-by-one stays stacked whichever way the screen turns…
        assertEquals(FacePair(Face.COMPACT, Face.COMPACT), Face.of(110, 160, 40, 100))
        // …and an unmeasured host is unmeasured in both orientations: the middle, never TALL.
        assertEquals(FacePair(Face.WIDE, Face.WIDE), Face.of(0, 0, 0, 0))
    }

    @Test
    fun `every step up adds a line`() {
        val ordered = listOf(Face.COMPACT, Face.WIDE, Face.TALL)
        // The type never shrinks as the tile grows, and each layout is a distinct one.
        assertEquals(ordered.map { it.total }.sorted(), ordered.map { it.total })
        assertEquals(ordered.size, ordered.map { it.layout }.distinct().size)
    }
}

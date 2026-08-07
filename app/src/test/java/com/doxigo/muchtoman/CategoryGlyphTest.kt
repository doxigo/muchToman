package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The glyph table is keyed on the Persian display name, which is the one kind of key that can be
 * mistyped without anything looking wrong: a category whose name drifts by one ZWNJ just quietly
 * loses its mark and shows three dots instead. This is the check that says so out loud.
 */
class CategoryGlyphTest {

    @Test
    fun `every category the app ships with has its own mark`() {
        val missing = BUILTIN_CATEGORIES
            .filter { it.id != CAT_UNCATEGORISED && categoryGlyph(it.nameFa) == CategoryGlyph.DOTS }
            .map { it.nameFa }
        assertEquals("categories falling back to the unknown glyph", emptyList<String>(), missing)
    }

    @Test
    fun `no two categories share a mark`() {
        val glyphs = BUILTIN_CATEGORIES.map { categoryGlyph(it.nameFa) }
        assertEquals("one mark per category", glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun `a category from a device that renamed it still draws something`() {
        assertEquals(CategoryGlyph.DOTS, categoryGlyph("چیزی که بلد نیستم"))
    }

    /**
     * The mark she picks is stored by name and read back by name, on this phone and on every other
     * phone in the household. A rename in the enum breaks that silently — the category keeps its
     * row, loses its mark, and shows the three dots that mean «nothing known».
     */
    @Test
    fun `a stored mark survives the round trip, and a made-up one does not`() {
        for (glyph in CategoryGlyph.entries) {
            assertEquals(glyph, glyphNamed(glyph.name))
        }
        assertEquals(null, glyphNamed("SPACESHIP"))
        assertEquals(null, glyphNamed(""))
    }

    @Test
    fun `the picker offers every mark except the one that means unknown`() {
        assertEquals(CategoryGlyph.entries.size - 1, PICKABLE_GLYPHS.size)
        assertEquals(false, CategoryGlyph.DOTS in PICKABLE_GLYPHS)
    }

    @Test
    fun `a category she made is drawn by its own mark, not by its name`() {
        val hers = customCategory("باشگاه", CategoryKind.EXPENSE, CategoryGlyph.STAR, now = 1)
        assertEquals(mapOf("باشگاه" to CategoryGlyph.STAR), customGlyphs(listOf(hers)))
        // Shipped categories carry no mark of their own: the name is the key, so storing one
        // would leave a build that renames a category drawing the mark it used to have.
        assertEquals(emptyMap<String, CategoryGlyph>(), customGlyphs(BUILTIN_CATEGORIES))
    }
}

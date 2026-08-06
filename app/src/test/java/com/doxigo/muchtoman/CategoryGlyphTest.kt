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
}

package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotListLayoutTest {
    @Test fun fitKeepsShapeWithoutBars() {
        // Wide crops fill the width; tall ones the height; the shape is always kept exactly.
        for (a in listOf(2.39, 1.85, 16.0 / 9, 4.0 / 3, 1.0, 0.75)) {
            val s = ShotListLayout.fit(a, 250.0, 170.0)
            assertEquals(a, s.width / s.height, 1e-9)
            assertTrue(s.width <= 250.0 + 1e-9 && s.height <= 170.0 + 1e-9)
            assertTrue(s.width >= 250.0 - 1e-9 || s.height >= 170.0 - 1e-9)   // touches one side: no wasted space
        }
        assertEquals(250.0, ShotListLayout.fit(2.39, 250.0, 170.0).width, 1e-9)
        assertEquals(170.0, ShotListLayout.fit(0.75, 250.0, 170.0).height, 1e-9)
    }

    @Test fun cardsNeverSplit() {
        val heights = listOf(150.0, 190.0, 120.0, 200.0, 180.0, 90.0, 210.0)
        val pages = ShotListLayout.paginate(heights, 500.0, 760.0)
        // Every card once, in order.
        assertEquals(heights.indices.toList(), pages.flatten())
        // Each page's cards fit its space.
        pages.forEachIndexed { p, idx ->
            val space = if (p == 0) 500.0 else 760.0
            assertTrue("page $p", idx.sumOf { heights[it] } <= space + 1e-9)
        }
        // A page only ends when the next card wouldn't fit.
        for (p in 0 until pages.size - 1) {
            val space = if (p == 0) 500.0 else 760.0
            assertTrue(pages[p].sumOf { heights[it] } + heights[pages[p + 1].first()] > space)
        }
    }

    @Test fun oversizedAndEmpty() {
        // A card taller than a page gets its own page instead of looping or vanishing.
        val pages = ShotListLayout.paginate(listOf(100.0, 900.0, 100.0), 500.0, 760.0)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), pages)
        // No cards: one (empty) page for the header.
        assertEquals(listOf(emptyList<Int>()), ShotListLayout.paginate(emptyList(), 500.0, 760.0))
        // First card too tall for the short first page (under the header) but fine on a full page: moves on.
        assertEquals(listOf(emptyList<Int>(), listOf(0)), ShotListLayout.paginate(listOf(600.0), 500.0, 760.0))
    }
}

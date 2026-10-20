package com.infraxcoders.bmpcc.core

import kotlin.math.max
import kotlin.math.min

/** Page layout for the PDF shot list: pictures fitted inside cards, and cards kept whole on a page. */
object ShotListLayout {
    data class Size(val width: Double, val height: Double)

    /** The largest size of [aspect] (width / height) that fits inside [maxWidth] × [maxHeight]. */
    fun fit(aspect: Double, maxWidth: Double, maxHeight: Double): Size {
        if (aspect <= 0 || aspect.isNaN() || maxWidth <= 0 || maxHeight <= 0) return Size(maxWidth, maxHeight)
        val w = min(maxWidth, maxHeight * aspect)
        return Size(w, w / aspect)
    }

    /**
     * Cards (of [heights]) into pages, in order: the first page has [firstPage] of space, the others [otherPages].
     * A card is never split: it moves to the next page when it doesn't fit; a card taller than a whole page gets a
     * page of its own. Returns the card indices of each page (at least one page, possibly empty).
     */
    fun paginate(heights: List<Double>, firstPage: Double, otherPages: Double): List<List<Int>> {
        val pages = mutableListOf(mutableListOf<Int>())
        var free = firstPage
        for ((i, h) in heights.withIndex()) {
            val page = pages.last()
            if (h > free && page.isNotEmpty() || h > free && pages.size == 1 && firstPage < otherPages && h <= otherPages) {
                pages += mutableListOf<Int>()
                free = otherPages
            }
            pages.last() += i
            free -= max(h, 0.0)
        }
        return pages
    }
}

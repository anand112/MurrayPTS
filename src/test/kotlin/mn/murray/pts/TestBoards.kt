package mn.murray.pts

import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The three phases, written straight onto a project's board.
 *
 * Stage 100 stopped `ProjectService.create` handing every new board Engineering / Procurement / Production, so a
 * test that inserts a project row directly (rather than going through the create route) and then renders or files
 * cards against it has to write those lanes itself. [DEFAULT_PROJECT_BUCKETS] stays as the legacy trio — the shape
 * every board had before Stage 100 — which is exactly why it is the right thing for these fixtures: the pages
 * under test were written against a board of three phases and still print one.
 */
internal fun seedOpeningThree(projectId: Int) {
    DEFAULT_PROJECT_BUCKETS.forEach { ProjectBucketRepository.insert(projectId, it) }
}

/**
 * The same three, written into a category's Settings list instead — the way a category is given the old board shape
 * now that the create route reads nothing else. A board created afterwards opens with them.
 */
internal fun seedOpeningThreeFor(type: String) {
    DEFAULT_PROJECT_BUCKETS.forEach { TypeDefaultBucketRepository.insert(type, it) }
}

/** The two panels `app.css` gives a sideways scroll, named exactly as the wrapper rule names them. */
private val SCROLLING_PANELS = setOf("card-panel", "board-results")

/**
 * The `class` of every `<div>` enclosing each occurrence of [needle] in [html], outermost first — one list of
 * ancestor classes per occurrence, in document order.
 *
 * Stage 140 needs this because the wide sheets keep a floor on their width (`table.fc-table` is 1280px,
 * `table.projects-table` 1040px) and depend on the *panel around them* to scroll sideways:
 * `.card-panel:has(table.grid), .board-results:has(table.grid), .card-panel:has(table.calendar-grid)`. Nothing
 * pinned that other half. `ShellRenderTest` asserts the text of the wrapper rule, so a table moved out of its
 * panel into a plain `div` would leave a phone with the document pushed past its own width and every test still
 * green — the same shape as Stage 134's fold, which named one of the two copies of a row.
 *
 * It walks the rendered HTML rather than reading the Kotlin, tracking `<div>` depth as it goes, so the answer is
 * the table's real ancestor and not the nearest class name that happens to appear earlier in the page.
 */
internal fun divAncestorsOf(html: String, needle: String): List<List<String>> {
    val open = Regex("<div\\b([^>]*)>")
    val classOf = Regex("class=\"([^\"]*)\"")
    val found = mutableListOf<List<String>>()
    var search = 0
    while (true) {
        val at = html.indexOf(needle, search)
        if (at < 0) break
        val stack = ArrayDeque<String>()
        var i = 0
        while (i < at) {
            val next = open.find(html, i)
            val close = html.indexOf("</div>", i).takeIf { it >= 0 }
            if (next != null && (close == null || next.range.first < close)) {
                val classes = classOf.find(next.groupValues[1])
                stack.addLast(if (classes != null) classes.groupValues[1] else "")
                i = next.range.last + 1
            } else if (close != null) {
                if (stack.isNotEmpty()) stack.removeLast()
                i = close + "</div>".length
            } else {
                break
            }
        }
        found.add(stack.toList())
        search = at + needle.length
    }
    return found
}

private fun assertPanelled(tableTag: String, chains: List<List<String>>) {
    chains.forEachIndexed { index, chain ->
        assertTrue(
            chain.any { classes -> classes.split(" ").any { it in SCROLLING_PANELS } },
            "$tableTag #$index has no panel that scrolls it sideways; its div ancestors are $chain",
        )
    }
}

/** Assert that all [expected] copies of [tableTag] sit in a panel the stylesheet scrolls. */
internal fun assertTablesScrollInTheirPanel(html: String, tableTag: String, expected: Int) {
    val chains = divAncestorsOf(html, tableTag)
    assertEquals(expected, chains.size, "expected $expected of $tableTag on the page")
    assertPanelled(tableTag, chains)
}

/**
 * The same, for a sheet whose copy count belongs to its own page's test rather than to this one. It still
 * refuses to pass on nothing: a page that stopped printing the table would have no ancestor to check.
 */
internal fun assertEveryTableScrollsInItsPanel(html: String, tableTag: String) {
    val chains = divAncestorsOf(html, tableTag)
    assertTrue(chains.isNotEmpty(), "the page printed no $tableTag at all")
    assertPanelled(tableTag, chains)
}

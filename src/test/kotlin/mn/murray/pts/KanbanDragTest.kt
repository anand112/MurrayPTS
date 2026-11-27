package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The drag/drop contract shared by the Assigned status boards and a project's lane board.
 *
 * These checks read the browser files because the suite has no JavaScript runtime. They pin the parts that can
 * otherwise drift independently: the visual move and the request, the drop indicator and the server's landing
 * rule, the native drag image and the original card's ghost, and the cleanup every way a drag can end. Render
 * tests cover the board marker; [MotionTest] covers the duration tokens and reduced-motion block.
 */
class KanbanDragTest {

    private val js = File("src/main/resources/static/js/kanban.js").readText()
    private val css = File("src/main/resources/static/css/app.css").readText()
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    private fun code(text: String): String = comments.replace(text, " ")

    private fun listener(name: String): String =
        js.substringAfter("addEventListener('$name'").substringBefore("});")

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    private fun body(selector: String): String {
        val rules = Regex("(?:^|\\})\\s*" + Regex.escape(selector) + "\\s*\\{([^}]*\\})")
            .findAll(code(css)).toList()
        assertEquals(1, rules.size, "\"$selector\" should be exactly one rule; found ${rules.size}")
        return rules[0].groupValues[1]
    }

    @Test
    fun `a status drop moves before its request and only failure reloads`() {
        val drop = listener("drop")
        val send = js.substringAfter("function sendMove").substringBefore("document.addEventListener('dragstart'")
        assertTrue("sendMove(card" in drop && drop.indexOf("file(card, col)") < drop.indexOf("sendMove(card"),
            "the status card waits for the reply before it moves")
        assertFalse("location.reload" in drop, "a status drop reloads on success")
        listOf("'/app/tasks/status', 'status', status", "'/app/tasks/bucket', 'bucket', bucket")
            .forEach { assertTrue(it in drop, "the existing move endpoint lost \"$it\"") }
        listOf(
            "fd.append('id', id)",
            "redirect: 'manual'",
            "response.type !== 'opaqueredirect' && !response.ok",
            "refreshBoard(current)",
            "document.querySelectorAll('[data-kanban-board]')[boardIndex]",
        ).forEach { assertTrue(it in send, "the existing move request lost \"$it\"") }
        assertEquals(1, occurrences(send, "location.reload()"), "failure recovery has more than one path")
        assertTrue(send.indexOf("throw new Error('move failed')") < send.indexOf("location.reload()"),
            "the reload is not attached to a rejected request")
    }

    @Test
    fun `the drop marker follows the end or before-Completed landing rule`() {
        val landing = js.substringAfter("function landingPlace").substringBefore("function showPlaceholder")
        assertTrue("directChild(col, 'kcol-done-toggle')" in landing,
            "a card would be inserted into the Completed fold instead of before its toggle")
        assertTrue("directChild(col, 'kcol-empty') || directChild(col, 'kcol-add')" in landing,
            "a card would land after the column's add control")
        assertTrue("node.className = 'kcard-drop'" in js,
            "the placeholder is being rendered as a real draggable card")
        assertTrue("Math.max(64, dragging.height || 80)" in js,
            "the placeholder no longer reserves the card's own height")
        val marker = body(".kcard-drop")
        listOf("border: 2px dashed var(--accent)", "pointer-events: none").forEach {
            assertTrue(it in marker, "the landing marker lost \"$it\"")
        }
        assertTrue(".kcol .kcard + .kcol-empty, .kcol .kcard ~ .kcol-empty { display: none; }" in code(css),
            "a receiving card leaves the old Nothing here line visible")
    }

    @Test
    fun `hover ignores descendants and every drag exit clears the indicator`() {
        val over = listener("dragover")
        val leave = listener("dragleave")
        assertTrue("targetColumn(e)" in over && "col.classList.add('drag-over')" in over,
            "status and lane columns no longer share one hover path")
        assertTrue("if (e.relatedTarget && col.contains(e.relatedTarget)) return;" in leave,
            "crossing a child takes the highlight off its column")
        val clear = js.substringAfter("function clearDrop").substringBefore("function edgeSpeed")
        listOf("placeholder.parentNode.removeChild(placeholder)", "if (hovered) hovered.classList.remove('drag-over')")
            .forEach { assertTrue(it in clear, "drag cleanup no longer removes \"$it\"") }
        assertTrue("endDrag();" in listener("drop"), "a drop leaves drag state behind")
        assertTrue(Regex("if \\(!col\\) \\{\\s*endDrag\\(\\);\\s*return;").containsMatchIn(listener("drop")),
            "a drop outside every column leaves its marker until a later dragend")
        assertTrue("endDrag();" in listener("dragend"), "a cancelled drag leaves its marker behind")
    }

    @Test
    fun `the hover outline cannot resize the column`() {
        val column = body(".kcol")
        val hover = body(".kcol.drag-over")
        assertTrue("outline: 2px dashed transparent" in column && "outline-offset: 3px" in column,
            "the column reserves no same-size drag outline")
        assertTrue("outline-color: var(--accent)" in hover, "the hover has no visible outline")
        listOf("border:", "border-", "padding", "margin", "transform", "width", "height").forEach {
            assertFalse(it in hover, "the hover changes layout with \"$it\"")
        }
        assertTrue("transition: outline-color var(--dur-1) var(--ease-std)" in column,
            "the outline appears without the short fade")
    }

    @Test
    fun `a drag cannot begin on a control and its release click cannot open the card`() {
        val start = listener("dragstart")
        assertTrue("e.target.closest('button, a, input, select, textarea, label, [contenteditable=\"true\"]')" in start,
            "a card control can start a drag instead of answering its own click")
        assertTrue("!control.hasAttribute('data-drag-handle')" in start,
            "the intentional Other-tasks handle is no longer draggable, or every control became an exception")
        assertTrue("e.preventDefault()" in start, "a refused control drag is not stopped")
        val press = listener("pointerdown")
        assertTrue("dragClickBlock = null" in press, "a fresh press can still be swallowed by the previous drag")
        val click = js.substringAfter("addEventListener('click'").substringBefore("}, true)")
        assertTrue("dragClickBlock" in click && "e.preventDefault()" in click &&
            ("e.stopPropagation()" in click || "e.stopImmediatePropagation()" in click),
            "the click released after a drag opens the editor")
        assertTrue("dragClickBlock = { id: id, at: Date.now() }" in listener("drop"),
            "the release-click guard cannot follow a card replaced by the quiet refresh")
        assertTrue("card.getAttribute('data-id') === dragClickBlock.id" in click,
            "the release-click guard still depends on the old card node")
        assertTrue("Date.now() - dragClickBlock.at > 400" in click &&
            click.indexOf("Date.now() - dragClickBlock.at > 400") < click.indexOf("if (card && card.getAttribute('data-id')"),
            "the ghost-click guard either never expires or still blocks an ordinary later click")
    }

    @Test
    fun `completed lane moves are folded locally and the server board remains authoritative`() {
        val file = js.substringAfter("function file").substringBefore("function cardsById")
        assertTrue("ensureDoneGroup(col)" in file && "directChild(col, 'kcol-done').appendChild(card)" in file,
            "a completed card enters a lane outside its Completed fold")
        assertTrue("syncCompleted(from)" in file && "syncCompleted(col)" in file && "count(from)" in file,
            "the fold labels or either column count are stale after a drop")
        assertTrue("from.getAttribute('data-lane') === '0'" in file,
            "the source-only Unsorted column stays after its last card leaves")
        assertTrue("dragging.focusId" in file && "focusedCard.focus({ preventScroll: true })" in file,
            "reparenting the card loses keyboard focus or scrolls the landing away")
        val refresh = js.substringAfter("function refreshBoard").substringBefore("function sendMove")
        listOf(
            "[data-kanban-board]", "DOMParser", "importNode", "replaceChild",
            "board.scrollLeft", "window.scrollX", "window.scrollY", "data-id",
        ).forEach { assertTrue(it in refresh, "the quiet board refresh lost \"$it\"") }
        assertTrue("refreshTokens[key] !== token" in refresh && "refreshTokens[key] === token" in refresh,
            "one board's slower reply can cancel or reload the other board's newer refresh")
        assertTrue(refresh.indexOf("var active = document.activeElement") > refresh.indexOf("var next = nextBoards[index]") &&
            refresh.indexOf("var active = document.activeElement") < refresh.indexOf("replaceChild(imported, board)"),
            "a slow refresh restores focus and scroll from before the reader moved them")
        assertFalse("location.reload" in refresh.substringBefore("catch"),
            "the successful board refresh navigates the page")
    }

    @Test
    fun `a card cannot cross server-rendered board boundaries`() {
        val start = listener("dragstart")
        val target = js.substringAfter("function targetColumn").substringBefore("function directChild")
        assertTrue("board: boardOf(card)" in start, "the drag does not remember its source board")
        assertTrue("col.closest('[data-kanban-board]') !== dragging.board" in target,
            "a drop may file a card into a board whose server response excludes it")
        listOf("dragover", "drop").forEach {
            assertTrue("targetColumn(e)" in listener(it), "$it bypasses the source-board boundary")
        }
    }

    @Test
    fun `edge scrolling and settle are delegated and reduced-motion aware`() {
        val over = listener("dragover")
        assertTrue("watchEdges(e, col)" in over, "the active pointer no longer drives edge scrolling")
        val watch = js.substringAfter("function watchEdges").substringBefore("function count")
        val scroll = js.substringAfter("function autoScroll").substringBefore("function watchEdges")
        listOf("col.closest('.kanban')", "board.scrollWidth > board.clientWidth")
            .forEach { assertTrue(it in watch, "edge scrolling lost \"$it\"") }
        listOf("scrollLeft", "scrollTop").forEach { assertTrue(it in scroll, "edge scrolling lost \"$it\"") }
        assertTrue("requestAnimationFrame(autoScroll)" in js, "edge scrolling is not frame-limited")
        val motion = js.substringAfter("function reducedMotion").substringBefore("function settle")
        val settle = js.substringAfter("function settle").substringBefore("function file")
        assertTrue("matchMedia('(prefers-reduced-motion: reduce)').matches" in motion,
            "the landing animation ignores the reader's motion setting")
        assertTrue("if (reducedMotion()) return;" in settle,
            "reduced motion still adds the settle animation")
        assertEquals(1, occurrences(js, "addEventListener('drop'"), "status and lane boards have separate drop paths")
        assertEquals(1, occurrences(js, "addEventListener('dragstart'"), "drag sources have separate handlers")
    }
}

package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TodoService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TodoPageRenderTest {

    private lateinit var ds: HikariDataSource
    private var listId = 0
    private var itemId = 0
    private val worker = UserSession("EMP0002")

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "todo-page.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member(worker.employeeNumber, "Bat-Erdene", "Workshop", "Engineer"))
        listId = checkNotNull(TodoService.createList(worker.employeeNumber, "This week", ""))
        itemId = checkNotNull(TodoService.createItem(worker.employeeNumber, "Prepare the handover", listId, ""))
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    @Test
    fun `checking a list or task updates only its list and can restore checkbox focus`() {
        val html = render { todoContent(worker) }

        assertTrue("id=\"todo-list-$listId\"" in html, html)
        assertTrue("action=\"/app/todo/lists/$listId/toggle\"" in html, html)
        assertTrue("data-live-target=\"#todo-list-$listId\"" in html, html)
        assertTrue("id=\"todo-list-toggle-$listId\"" in html, html)
        assertTrue("id=\"todo-item-toggle-$itemId\"" in html, html)
        assertTrue("data-autosubmit=\"\"" in html, html)
        assertTrue("action=\"/app/todo/$itemId/toggle\"" in html, html)
    }

    @Test
    fun `list and task deletion explain what will be removed`() {
        val html = render { todoContent(worker) }

        assertTrue("data-confirm=\"Delete list “This week” and its tasks?\"" in html, html)
        assertTrue("data-confirm=\"Delete task “Prepare the handover”?\"" in html, html)
    }

    @Test
    fun `the board and its lists wear the classes the breakpoint snaps`() {
        val html = render { todoContent(worker) }

        // The 900px block names `.todo-board` and `.todo-board .todo-list`. A rule against a class the page
        // does not print is a silent no-op, which is how Stage 134's fold came to miss the copy of the field
        // row that management actually edits. `todo.js` reads the same contract, so this pins both.
        assertTrue("class=\"todo-board\"" in html, html)
        val boardAt = html.indexOf("class=\"todo-board\"")
        val listAt = html.indexOf("class=\"card-panel todo-list")
        assertTrue(listAt > boardAt, "the list is not inside the board that scrolls it")
        assertTrue("data-list=\"$listId\"" in html, html)
    }

    private fun todoJs(): String = File("src/main/resources/static/js/todo.js").readText()

    @Test
    fun `every to-do write names the block it changes, and no wider one`() {
        val html = render { todoContent(worker) }

        fun count(needle: String) = html.split(needle).size - 1

        // The board is a swap target because three writes change *which columns there are*: a move changes
        // their order, a delete removes one, and the New list card adds one. A deleted list is not in the
        // response at all, so a form aimed at its own `#todo-list-N` would find nothing to replace and fall
        // back to the navigation its markup describes -- the reload this stage exists to remove.
        val boardAt = html.indexOf("class=\"todo-board\"")
        assertTrue(boardAt >= 0, html)
        assertTrue(html.indexOf("id=\"todoBoard\"", boardAt) - boardAt in 1..80, "the board's id is not on the board: $html")
        assertEquals(2, count("data-live-target=\"#todoBoard\""), "a move and a list delete are the two board-level writes")
        assertEquals(1, count("data-live-target=\"#newListCard\""), html)

        // Everything else changes one list and stays inside it: the two ticks Stage 136 already swapped, the
        // two end dates, the task delete and the add-task box.
        assertEquals(6, count("data-live-target=\"#todo-list-$listId\""), html)

        // The sidebar's To-do figure counts the reader's to-dos, so the three writes that change how many
        // there are carry it as a companion. A move, an end date and a new *list* do not -- a list holds no
        // to-dos until one is written -- and naming a number that did not move is how a real one gets missed.
        assertEquals(3, count("data-live-extra=\"#nav-pill-todo\""), html)
        assertEquals(1, count("data-live-extra=\"#todoBoard\""), "creating a list adds a column to the board")

        // Focus. A delete removes the control that was pressed and an add empties it, so both name where the
        // reader is going rather than leaving the focus on a node that is no longer in the document.
        assertEquals(2, count("data-live-focus=\"#todo-new-list-name\""), html)
        assertEquals(1, count("data-live-focus=\"#todo-add-title-$listId\""), html)
        assertEquals(1, count("data-live-focus=\"#todo-list-toggle-$listId\""), html)

        // The ids those attributes address, plus the two the arrows need so that a press comes back to the
        // same arrow after the board it just reordered has been replaced by the response.
        listOf(
            "id=\"todo-due-list-$listId\"",
            "id=\"todo-due-task-$itemId\"",
            "id=\"todo-add-title-$listId\"",
            "id=\"todo-new-list-name\"",
            "id=\"todo-move-up-$listId\"",
            "id=\"todo-move-down-$listId\"",
        ).forEach { assertTrue(it in html, "$it is not on the page: $html") }

        // Draggable from the markup rather than from a loop at load: a column that arrives in a response is
        // never visited by that loop, and a column that cannot be lifted is a board that has quietly lost its
        // reordering while every test still passed.
        val draggableAt = html.indexOf("draggable=\"true\"")
        assertTrue(draggableAt > html.indexOf("id=\"todo-list-$listId\""), "the column is not the draggable node: $html")
    }

    @Test
    fun `the drag that reorders survives a board the page has since replaced`() {
        val js = todoJs()

        // Bound on `document`, columns found with `closest()`, and no node kept from load: the three things
        // that make a drag still work on a column which arrived in a response. `kanban.js` was cured of the
        // same trap in Stage 94, and its own comment names the reason -- a listener belongs to the node it
        // was put on, not to the HTML it was made from.
        listOf("dragstart", "dragend", "dragover", "drop").forEach {
            assertTrue("document.addEventListener('$it'" in js, "the $it half is not on the document: $js")
        }
        assertTrue("closest('.todo-list')" in js, js)
        assertFalse(
            "var board = document.querySelector('.todo-board')" in js,
            "the board is still cached from load, so a swapped board has no reordering at all",
        )
        assertFalse("col.draggable = true;" in js, "draggability is still set by a loop that never sees a new column")
        // Stage 139 gave this board `x proximity` rather than `mandatory` *because* of the edge-scroll below:
        // a mandatory snap would pull a deliberate auto-scroll straight back and fight the reader's own drag.
        // The two halves of that one decision live in two files, so the half in this file is pinned here.
        assertTrue("board.scrollLeft += 14" in js, js)
        assertTrue("board.scrollLeft -= 14" in js, js)
        // A drag let go outside the board must not be allowed to reorder it: the columns belong to the board
        // the lifted column came from, and the pointer is not always over that one.
        assertTrue("board !== boardOf(dragging)" in js, "any board under the pointer can be reordered")
    }
}

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
}

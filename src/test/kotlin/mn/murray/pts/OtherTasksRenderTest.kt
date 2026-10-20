package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * What the Other tasks page renders: the backlog as a vertical run of bucket **groups**, each with its name on
 * top and its cards cut by the same five status columns a project board prints (Stage 57); the three writes
 * those buckets have — naming one, renaming one, emptying one — and the card writes a bucket hands to the same
 * popup an assigned card uses.
 *
 * The rules behind those writes live in `TaskService.addBucket`/`renameBucket`/`deleteBucket` and in
 * `TaskService.createBacklog`/`updateAssigned`, and every route re-checks the role. What is pinned here is the
 * other half of the same promise, the half a route test cannot see: the markup an ordinary account is served
 * offers them a bucket to name and a card to write, and nothing that renames or empties a bucket somebody
 * else created; a backlog card is opened by a click on the card rather than by a menu a script has to read;
 * every form names the blocks its own send re-renders,
 * which is what lets a bucket arrive, rename itself, empty, or a card's percentage move without the page
 * restarting under the reader; and a refused write comes back as an open popup holding what was typed and its
 * reason under that field, rather than as a board that quietly changed nothing.
 *
 * Since Stage 47 this is the whole of a page rather than a section at the foot of the assigned board, and the
 * [page] helper renders it the way the page does: the head's toggle, drawn off [otherToggleApplies], above the
 * board from [otherTasksContent]. Every control on it is a GET onto `/app/other-tasks` because every one of
 * them lands on this page — a board that opened somebody else's popup would close its own before the reader
 * could fill it. The card popup still *posts* to `/app/assigned-tasks/save`, which is one endpoint for both
 * boards by design (Stage 42) and is asserted as such below rather than being renamed with the page.
 */
class OtherTasksRenderTest {

    private lateinit var ds: HikariDataSource
    private val manager = UserSession("EMP0001")
    private val engineer = UserSession("EMP0002")

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "backlog.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    /**
     * The page as the reader gets it: the head's toggle, then the board. `appPage` renders `actions` into the
     * head and the block below it, and asks [otherToggleApplies] before it draws the control — so this is the
     * one place the two halves can be read together, which is exactly the promise the toggle tests make.
     */
    private fun page(
        canManage: Boolean,
        bucket: BucketForm = BucketForm(),
        showAll: Boolean = false,
        state: TaskForm? = null,
    ): String = render {
        // Stage 59: the head's two small controls — `+ Add bucket` and the show-all toggle — print for management
        // only, the way `respondOtherTasks` now gates them, so this helper mirrors that. The add button always
        // shows for management; the toggle only when the view it offers applies.
        if (canManage) addBucketButton(showAll)
        if (canManage && otherToggleApplies(showAll)) allBucketsToggle(showAll)
        otherTasksContent(
            if (canManage) manager else engineer, canManage, state, bucket = bucket, showAllBuckets = showAll,
        )
    }

    /** A backlog column, addressed the way the board's own forms address it. */
    private fun bucket(name: String): Int = BucketRepository.insert(name)

    /** An other-board card standing in [bucketId], owned by [assignee] and sitting in [status]. */
    private fun card(
        title: String,
        bucketId: Int,
        assignee: String? = null,
        status: String = "Not started",
    ): Int = TaskRepository.insert(
        kind = "OTHER",
        title = title,
        projectId = null,
        due = "2026-11-12",
        priority = "Medium",
        assigneeEmpNo = assignee,
        status = status,
        bucketId = bucketId,
        started = "2026-11-01",
    )

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    private fun formTags(html: String, classes: String): List<String> =
        Regex("<form[^>]*class=\"${Regex.escape(classes)}\"[^>]*>").findAll(html).map { it.value }.toList()

    /** The one element carrying this id, sliced out of the indented document so its attributes are readable. */
    private fun elementWithId(html: String, id: String): String? =
        Regex("<(\\w+)[^>]*id=\"${Regex.escape(id)}\"[^>]*>").find(html)?.value

    /**
     * One form's whole markup — its opening tag through its own `</form>`, since none of these forms nest.
     * A tag alone is not enough here: the fields that carry the column id and the board's view are *inside* it.
     */
    private fun formRegion(html: String, header: Regex): String {
        val start = header.find(html)?.range?.first ?: return ""
        val end = html.indexOf("</form>", start)
        return html.substring(start, if (end < 0) html.length else end + "</form>".length)
    }

    private fun formWithAction(html: String, action: String): String =
        formRegion(html, Regex("<form[^>]*action=\"${Regex.escape(action)}\""))

    /** The backlog's board: `#otherBoard` up to the popup that follows it, and nothing of the head above. */
    private fun board(html: String): String {
        val start = html.indexOf("id=\"otherBoard\"")
        if (start < 0) return ""
        val end = html.indexOf("id=\"bucketModal\"", start)
        return html.substring(start, if (end < 0) html.length else end)
    }

    /** The bucket popup alone, from its own div to the script that closes it. */
    private fun popup(html: String): String {
        val start = html.indexOf("id=\"bucketModal\"")
        if (start < 0) return ""
        val end = html.indexOf("bucket-modal.js", start)
        return html.substring(start, if (end < 0) html.length else end)
    }

    /** The card popup alone, the same way: its own div to the script at the end of it. */
    private fun taskPopup(html: String): String {
        val start = html.indexOf("id=\"taskModal\"")
        if (start < 0) return ""
        val end = html.indexOf("task-modal.js", start)
        return html.substring(start, if (end < 0) html.length else end)
    }

    /** A picker's opening tag, which is where its `disabled` and its `name` are written. */
    private fun selectTag(html: String, name: String): String =
        Regex("<select[^>]*name=\"${Regex.escape(name)}\"[^>]*>").find(html)?.value.orEmpty()

    /** A picker and its options, since which person or column the field offers is the other half of the rule. */
    private fun selectRegion(html: String, name: String): String {
        val start = Regex("<select[^>]*name=\"${Regex.escape(name)}\"").find(html)?.range?.first ?: return ""
        val end = html.indexOf("</select>", start)
        return html.substring(start, if (end < 0) html.length else end + "</select>".length)
    }

    private fun selectedOptionIn(html: String, name: String, value: String): Boolean =
        selectedOption(selectRegion(html, name), value)

    /** One field's own tag, by its id, so `disabled` can be read off the field rather than off its neighbours. */
    private fun inputTag(html: String, id: String): String =
        Regex("<input[^>]*id=\"${Regex.escape(id)}\"[^>]*>").find(html)?.value.orEmpty()

    /** One swapped block, from its opening tag to the close of that same tag — none of these nest. */
    private fun region(html: String, id: String): String {
        val tag = Regex("<(\\w+)[^>]*id=\"${Regex.escape(id)}\"[^>]*>").find(html) ?: return ""
        val open = tag.value.length + tag.range.first
        val close = "</${tag.groupValues[1]}>"
        val end = html.indexOf(close, open)
        return html.substring(open, if (end < 0) html.length else end)
    }

    private fun selectedOption(html: String, value: String): Boolean =
        Regex("<option[^>]*value=\"${Regex.escape(value)}\"[^>]*selected").containsMatchIn(html)

    @Test
    fun `each bucket prints its name on top and its cards cut by the five statuses`() {
        val site = bucket("Site works")
        bucket("Idle plant")
        card("Grade the haul road", site, status = "In progress")
        val html = page(canManage = true)
        val row = board(html)
        // One group per bucket that holds work, and the empty one kept off the narrow board until it is asked for.
        assertEquals(1, occurrences(row, "bucket-group-head"), "the board is not one group per bucket it shows")
        assertEquals(1, occurrences(row, "dot d-bucket"), "the group head carries no marker of its own")
        assertTrue(row.contains(">Site works<"), "the bucket holding the card is not named on top")
        assertFalse(row.contains(">Idle plant<"), "an empty bucket was drawn as though it were work")
        // Under the name sits the same five-status cut a project board prints (Stage 57): the bucket's cards are
        // read by where they have got to, not by which column of names they sit in.
        assertEquals(5, occurrences(row, "kcol-head"), "the bucket's board is not cut by the five statuses")
        TASK_STATUSES.forEach {
            assertTrue(row.contains(">$it<"), "the status board is missing its $it column")
        }
        // The card is still a backlog card, and its status is said once — by the column it stands in, not
        // repeated on the card body the way the old per-bucket column used to print it.
        assertEquals(1, occurrences(row, "s-other"), "the backlog card is not drawn as a backlog card")
        assertFalse(row.contains("ostatus"), "the card still prints its own status word")
        assertTrue(row.contains("<input type=\"hidden\" name=\"column\" value=\"$site\">"),
            "the bucket has no line to add a card to")
        // The page says once what the board is: nothing under the head repeats the page's own name.
        assertFalse(row.contains("other-head"), "the board still carries a heading of its own")
    }

    @Test
    fun `the head's toggle is asked of the same read the board prints`() {
        // The two halves of the page ask one question of one source: the head draws `Show all buckets` only
        // while the board has a column it is hiding, so a button over a board with nothing hidden — or a
        // hidden column with no way to reach it — cannot be drawn by this markup at all.
        val site = bucket("Site works")
        card("Grade the haul road", site)
        assertFalse(otherToggleApplies(false), "a board with nothing hidden says it is hiding a column")
        assertFalse(page(canManage = true).contains("buckets-toggle"), "the head drew a control with nothing to do")
        bucket("Idle plant")
        assertTrue(otherToggleApplies(false), "a board with a hidden column says there is nothing to show")
        assertTrue(otherToggleApplies(true), "the wide board is told to widen again")
        assertTrue(page(canManage = true).contains("buckets-toggle"), "the head disagrees with its own board")
        // The wide view always has a way back, even after the last column has been emptied of its hiding.
        assertTrue(page(canManage = true, showAll = true).contains("buckets-toggle"),
            "the wide board cannot be closed again")
    }

    @Test
    fun `the page names what the board is not`() {
        // The head's second line is the one thing a reader arriving from the sidebar has to check: this work
        // belongs to no project. It is the sentence the section head used to print, and it is a constant
        // because the route writes it into the page head rather than the board.
        assertEquals("tasks outside of projects", OTHER_TASKS_SUBTITLE, "the head says something else")
    }

    @Test
    fun `the add bucket control is a small button in the page head, not a box on the board`() {
        val html = page(canManage = true)
        val boxes = formTags(html, "opener-form addbucket-form")
        assertEquals(1, boxes.size, "the head does not hold exactly one add-bucket button")
        val box = boxes.first()
        assertTrue(box.contains("action=\"/app/other-tasks\""), "the button aims at another page: $box")
        assertTrue(box.contains("method=\"get\""), "the button is not a GET onto this page: $box")
        assertTrue(box.contains("data-live-target=\"#bucketModal\""), "the button would reload the page: $box")
        assertTrue(box.contains("data-live-extra=\"#bucketsToggle,#bucketModal\""),
            "the button leaves the head's toggle or the popup stale: $box")
        // It reads as the head's other small button — the show-all toggle — so the two are indistinguishable in shape.
        val whole = formRegion(html, Regex("<form[^>]*class=\"opener-form addbucket-form\""))
        assertTrue(whole.contains("class=\"ghost-btn btn-sm\""),
            "the add-bucket control is not a small head button: $whole")
        assertTrue(html.contains("<input type=\"hidden\" name=\"newbucket\" value=\"1\">"),
            "the button does not ask for a blank popup")
        assertTrue(whole.contains("+ Add bucket"), "the button does not say what it adds")
        // It is a button, not a field: the name is written in the popup, where a refusal has somewhere to print.
        assertFalse(html.contains("placeholder=\"+ Add bucket\""), "a bare field is drawn on the page")
        // The board itself no longer carries the control it used to trail as a dashed column.
        assertFalse(board(html).contains("newbucket"), "an add-bucket box is still drawn inside the board")
        assertFalse(html.contains("kcol addbucket"), "the old dashed column-shaped box is still rendered")
        assertEquals(1, occurrences(html, "/app/other-buckets"), "something besides the popup posts a bucket")
    }

    @Test
    fun `each column head carries the menu that opens its own popup`() {
        val first = bucket("Site works")
        val second = bucket("Overhaul")
        card("Grade the haul road", first)
        card("Strip the loader", second)
        val html = page(canManage = true)
        val menus = formTags(html, "kcol-menu")
        assertEquals(2, menus.size, "a column of the board has no menu")
        val menu = menus.first()
        assertTrue(menu.contains("action=\"/app/other-tasks\""), "the menu opens another page: $menu")
        assertTrue(menu.contains("data-live-target=\"#bucketModal\""), "the menu would reload the page: $menu")
        assertTrue(menu.contains("data-live-extra=\"#bucketsToggle\""), "the menu leaves the head's toggle stale: $menu")
        assertTrue(html.contains("<input type=\"hidden\" name=\"bucket\" value=\"$first\">"),
            "the menu does not name the column it is about")
        assertTrue(html.contains("<input type=\"hidden\" name=\"bucket\" value=\"$second\">"),
            "a second column's menu names the wrong one")
        // One label and one glyph per column: the menus are identical controls over different ids, which is
        // exactly what they should read as.
        assertEquals(2, occurrences(html, "aria-label=\"Manage this bucket\""), "the menus are not labelled alike")
        assertEquals(2, occurrences(board(html), "⋮"), "the menus do not read as menus")
    }

    @Test
    fun `a column's popup renames it by id and asks where its cards go`() {
        val site = bucket("Site works")
        val depot = bucket("Depot")
        card("Grade the haul road", site)
        card("Re-line the crusher", site, status = "Blocked")
        card("Fix the gate", depot)
        val html = page(canManage = true, bucket = BucketForm(bucketId = site))
        assertTrue(elementWithId(html, "bucketModal").orEmpty().contains("style=\"display:block\""),
            "the menu did not put the popup up")
        val form = popup(html)
        val rename = formWithAction(form, "/app/other-buckets/$site/rename")
        assertTrue(rename.isNotEmpty(), "the popup cannot rename the column it was opened on")
        assertTrue(rename.contains("data-live-target=\"#otherBoard\""), "a rename would restart the page")
        assertTrue(rename.contains("data-live-extra=\"#bucketModal,#bucketsToggle\""), "a rename leaves the board up")
        // The stored name is the field's value before any refusal, so the box is never blank on open.
        assertTrue(rename.contains("value=\"Site works\""), "the rename field does not hold the column's name")
        assertTrue(rename.contains("Rename bucket"), "the rename is not offered as a button")
        val delete = formWithAction(form, "/app/other-buckets/$site/delete")
        assertTrue(delete.isNotEmpty(), "the popup cannot empty the column it was opened on")
        assertTrue(delete.contains("data-live-target=\"#otherBoard\""), "a delete would restart the page")
        // The count is said out loud because the cards have to be accounted for before the column goes.
        assertTrue(form.contains("hands its 2 cards to another"), "the delete does not say how much work it moves")
        val move = elementWithId(form, "bucketMove")
        assertTrue(move != null && move.contains("name=\"moveTo\""), "the destination is not a field of the delete")
        assertTrue(move != null && move.contains("required"), "the delete would post with no destination")
        assertTrue(delete.contains(">Depot<"), "the other column is not offered as the destination")
        assertFalse(delete.contains(">Site works<"), "the column being deleted was offered for its own cards")
        assertTrue(delete.contains("Delete bucket"), "the delete is not offered as a button")
        assertTrue(delete.contains("danger-btn"), "the destructive button does not read as destructive")
    }

    @Test
    fun `the rename field keeps the name a refusal rejected, not the stored one`() {
        val site = bucket("Site works")
        bucket("Depot")
        card("Grade the haul road", site)
        val reason = "There is already a bucket called that."
        val html = page(canManage = true, bucket = BucketForm(typed = "Depot", nameError = reason, bucketId = site))
        assertTrue(elementWithId(html, "bucketModal").orEmpty().contains("style=\"display:block\""),
            "a refusal closed the form it just rejected")
        val form = formWithAction(popup(html), "/app/other-buckets/$site/rename")
        assertTrue(form.contains("value=\"Depot\""), "the word the route refused was thrown away")
        assertFalse(form.contains("value=\"Site works\""), "the popup shows the name the reader did not type")
        assertEquals(1, occurrences(html, "field-err"), "a refusal printed more than one message")
        assertTrue(html.contains(">$reason<"), "the reason is not the line the route gave")
        // At the field, not at the top of the popup.
        assertTrue(
            form.indexOf("id=\"bucketName\"") < form.indexOf("field-err"),
            "the message is not under the field it refuses",
        )
    }

    @Test
    fun `a refused delete comes back with its destination still chosen`() {
        val site = bucket("Site works")
        val depot = bucket("Depot")
        card("Grade the haul road", site)
        val reason = "Choose which bucket takes its cards."
        val html = page(
            canManage = true,
            bucket = BucketForm(deleteError = reason, bucketId = site, moveId = depot),
        )
        assertTrue(elementWithId(html, "bucketModal").orEmpty().contains("style=\"display:block\""),
            "a refusal closed the form it just rejected")
        val form = formWithAction(popup(html), "/app/other-buckets/$site/delete")
        assertTrue(selectedOption(form, "$depot"), "the destination the route refused came back unchosen")
        assertEquals(1, occurrences(html, "field-err"), "a refusal printed more than one message")
        assertTrue(html.contains(">$reason<"), "the reason is not the line the route gave")
        assertTrue(
            form.indexOf("id=\"bucketMove\"") < form.indexOf("field-err"),
            "the message is not under the choice it refuses",
        )
    }

    @Test
    fun `the last column on the board is not offered for deletion while it holds cards`() {
        // `tasks.bucket_id` answers ON DELETE SET NULL and this board has no Unsorted column to catch what
        // falls out of it, so a route refusal is not enough — the page must not draw the button either.
        val only = bucket("Site works")
        card("Grade the haul road", only)
        val html = page(canManage = true, bucket = BucketForm(bucketId = only))
        val form = popup(html)
        assertTrue(formWithAction(form, "/app/other-buckets/$only/rename").isNotEmpty(), "renaming went missing")
        assertFalse(form.contains("/delete"), "a delete was offered with nowhere to put the cards")
        assertFalse(form.contains("bucketMove"), "a destination picker was drawn over an empty board")
        assertTrue(form.contains("so there is nowhere to move them"), "the popup does not say why it refuses")
        assertTrue(form.contains("Add another bucket first"), "the popup does not say what would unblock it")
    }

    @Test
    fun `an empty column is deleted without asking where its cards go`() {
        val site = bucket("Site works")
        val idle = bucket("Idle plant")
        card("Grade the haul road", site)
        val html = page(canManage = true, bucket = BucketForm(bucketId = idle), showAll = true)
        val form = popup(html)
        assertTrue(
            formWithAction(form, "/app/other-buckets/$idle/delete").isNotEmpty(),
            "an empty column cannot be taken off the board",
        )
        assertFalse(form.contains("bucketMove"), "a destination was asked for a column holding nothing")
        assertTrue(form.contains("moves nothing"), "the popup does not say that deleting it moves nothing")
    }

    @Test
    fun `the all-buckets toggle swaps the board and its own label together`() {
        val site = bucket("Site works")
        bucket("Idle plant")
        card("Grade the haul road", site)
        val hidden = page(canManage = true)
        val toggles = formTags(hidden, "opener-form buckets-toggle")
        assertEquals(1, toggles.size, "a board with a hidden column has no way to show it")
        val toggle = formRegion(hidden, Regex("<form[^>]*class=\"opener-form buckets-toggle\""))
        assertTrue(toggle.contains("data-live-target=\"#otherBoard\""), "the toggle would reload the page")
        assertTrue(toggle.contains("data-live-extra=\"#bucketsToggle,#bucketModal\""),
            "the toggle leaves its own label, or the open popup, behind")
        // The field posts the view it asks for, never the one it leaves.
        assertTrue(toggle.contains("<input type=\"hidden\" name=\"allbuckets\" value=\"1\">"),
            "the toggle did not ask for the full board")
        assertTrue(toggle.contains("Show all buckets"), "the button does not say what pressing it does")
        assertFalse(board(hidden).contains(">Idle plant<"), "the board was drawn with the toggle ignored")
        val shown = page(canManage = true, showAll = true)
        assertTrue(board(shown).contains(">Idle plant<"), "Show all buckets did not draw the empty bucket")
        // The wide board reveals the hidden bucket as its own group of five empty status columns, each of which
        // says "Nothing here." the way a project board's empty column does (Stage 57).
        assertTrue(shown.contains("Nothing here."), "an empty status column says nothing about itself")
        val back = formRegion(shown, Regex("<form[^>]*class=\"opener-form buckets-toggle\""))
        assertTrue(back.contains("<input type=\"hidden\" name=\"allbuckets\" value=\"0\">"),
            "the full board does not offer the way back")
        assertTrue(back.contains("Show fewer buckets"), "the button still says what it did before pressing it")
    }

    @Test
    fun `the toggle is not drawn when every column is already on the board`() {
        val site = bucket("Site works")
        card("Grade the haul road", site)
        val html = page(canManage = true)
        assertFalse(html.contains("buckets-toggle"), "a board with nothing hidden offers a choice")
        assertFalse(html.contains("allbuckets"), "the page carries a view nobody was offered")
    }

    @Test
    fun `a bucket that has just been named is shown on the board that answers`() {
        // An empty column that disappears the moment it appears reads as a control that does not work, so the
        // create's own response is drawn with the board on the wide view.
        bucket("Site works")
        val html = page(canManage = true, bucket = BucketForm(open = true), showAll = true)
        assertTrue(board(html).contains(">Site works<"), "the create closed over a board that hid the new column")
    }

    @Test
    fun `a backlog card opens the editor popup on the card itself`() {
        val site = bucket("Site works")
        val cardId = card("Grade the haul road", site, status = "In progress")
        val html = page(canManage = true)
        val cardTag = Regex("<div[^>]*class=\"kcard s-other\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue(cardTag.contains("data-id=\"$cardId\""), "the card does not name itself: $cardTag")
        // Stage 58: management moves a backlog card by dragging it between the status columns, so their copy is
        // draggable — the one attribute `kanban.js` looks for on a drag source, and it prints for exactly the
        // reader whose status write `/app/tasks/status` accepts.
        assertTrue(cardTag.contains("draggable=\"true\""), "management cannot drag a backlog card: $cardTag")
        val openers = formTags(html, "kcard-open")
        assertEquals(1, openers.size, "the card is not a single click target")
        val opener = openers.first()
        assertTrue(opener.contains("action=\"/app/other-tasks\""), "the card opens another page: $opener")
        assertTrue(opener.contains("method=\"get\""), "the card is not a GET onto this page: $opener")
        assertTrue(opener.contains("data-live-target=\"#taskModal\""), "the card would reload the page: $opener")
        assertTrue(html.contains("<input type=\"hidden\" name=\"edit\" value=\"$cardId\">"),
            "the card does not say which task to open")
        assertTrue(html.contains("aria-label=\"Open Grade the haul road\""), "the click says nothing about itself")
        // The editor is the one the assigned board uses, not a second popup for the backlog — and this page
        // has no other board on it to own `#taskEditOpener`, so the backlog's own click needs no marker of its
        // own: one form per card says which card was pressed without a script reading a class.
        assertEquals(1, occurrences(html, "id=\"taskModal\""), "the two boards draw different editors")
        assertFalse(html.contains("data-task-card"), "the page re-used the assigned board's card opener")
    }

    @Test
    fun `the owner of a backlog card moves its status with the board's own arrows`() {
        val site = bucket("Site works")
        val cardId = card("Grade the haul road", site, assignee = "EMP0002", status = "In progress")
        val mine = page(canManage = false)
        val row = board(mine)
        val idx = TASK_STATUSES.indexOf("In progress")
        // One arrow each way, in the contract `kanban.js` binds globally: `kmove-btn` appears twice because the
        // card sits between two statuses, not because the board drew a second control.
        assertEquals(2, occurrences(row, "kmove-btn"), "the card is not between two statuses")
        assertTrue(row.contains("data-id=\"$cardId\""), "the arrow names a different card")
        assertTrue(row.contains("data-status=\"${TASK_STATUSES[idx - 1]}\""), "the way back is missing")
        assertTrue(row.contains("data-status=\"${TASK_STATUSES[idx + 1]}\""), "the way on is missing")
        // Stage 58: the arrows are the owner's no-JS path to the same write a drag does — and their own card is
        // now draggable too, so both the mouse and the keyboard reach the columns. The card's `draggable` and its
        // `kmove-btn` arrows stand for one right, not two: whoever the route lets change the status.
        assertTrue(
            Regex("<div[^>]*class=\"kcard s-other\"[^>]*>").find(row)?.value.orEmpty().contains("draggable=\"true\""),
            "the owner cannot drag their own backlog card",
        )
        // The card is still clickable for its owner, and the editor popup exists for `?edit=` to land in. The
        // count is on the form's own class, since the button inside it is named `kcard-open-btn` as well.
        assertEquals(1, formTags(mine, "kcard-open").size, "an owner cannot open their own backlog card")
        assertTrue(elementWithId(mine, "taskModal") != null, "the backlog's only card had no editor to open")
        // Management does not get arrows on a card they can edit in place: the popup owns their status.
        assertFalse(board(page(canManage = true)).contains("kmove-btn"),
            "arrows were drawn on a card management edits in the popup")
    }

    @Test
    fun `a backlog card drags between status columns, never a bucket column`() {
        // Stage 58: dragging a card along the bucket's status row is the mouse path to the same status write the
        // owner's arrows post. `kanban.js` starts a drag on a `.kcard[draggable="true"]` and only recognises a status
        // drop on a `.kcol[data-status]`. But the card's editor opens through a whole-card overlay (Stage 40), and
        // that overlay's `<button>` is the node the pointer actually grabs — a button is not a native drag origin, so
        // without a handle on the overlay itself nothing dragged (the bug this fixes). So BOTH halves are pinned here:
        // the card carries `draggable` (the `data-id` carrier `cardOf` climbs to) and every opener button carries
        // `draggable` (the handle the drag starts on). And a backlog column is a STATUS, never a `[data-lane]` bucket
        // column, which is what keeps a drag from filing the card into another bucket.
        val site = bucket("Site works")
        card("Grade the haul road", site, assignee = "EMP0002")
        card("Re-stock the laydown", site, assignee = "EMP0002", status = "In progress")

        val management = board(page(canManage = true))
        // Every backlog card is both a draggable node and has a draggable opener button — one handle per card, so no
        // card management cannot move looks grabbable and none they can move stays stuck.
        val openers = Regex("<button[^>]*class=\"kcard-open-btn\"[^>]*>").findAll(management).toList()
        assertEquals(2, openers.size, "management's board did not print one opener per backlog card")
        assertTrue(
            openers.all { it.value.contains("draggable=\"true\"") },
            "a backlog card's whole-card opener was not a drag handle, so dragging never starts",
        )
        assertTrue(
            Regex("<div[^>]*class=\"kcard s-other\"[^>]*>").findAll(management).all { it.value.contains("draggable=\"true\"") },
            "a movable backlog card did not carry the draggable node kanban.js climbs to",
        )

        // The columns are statuses: five per bucket head, and there is no lane column anywhere, so every drop on this
        // board posts `/app/tasks/status` and the bucket the card sits under is untouched.
        assertEquals(TASK_STATUSES.size, occurrences(management, "kcol-head"), "the bucket did not print its five status columns")
        assertFalse(management.contains("data-lane"), "a backlog column was a bucket lane a drag could re-file into")
    }

    @Test
    fun `a backlog column's add line opens the card's own popup on that column`() {
        val site = bucket("Site works")
        bucket("Overhaul")
        card("Grade the haul road", site)
        val html = page(canManage = true, showAll = true)
        // The lane list's own control, on both columns of the wide board: same class, same GET, same target —
        // the thing a reader has to be able to tell apart is which column it aims at, not how it works.
        val openers = formTags(html, "kcol-add")
        assertEquals(2, openers.size, "a column of the board has no line to add a card to")
        val opener = openers.first()
        assertTrue(opener.contains("action=\"/app/other-tasks\""), "the line opens another page: $opener")
        assertTrue(opener.contains("method=\"get\""), "the line is not a GET onto this page: $opener")
        assertTrue(opener.contains("data-live-target=\"#taskModal\""), "the line would reload the page: $opener")
        // The line's own fields, sliced out of the board so the column menu's `bucket` field next door is not
        // read as part of it: `?bucket=` already names a column's *popup* on this URL, and one key meaning two
        // things would make a column's menu and an aimed create indistinguishable to a bookmark.
        val adder = formRegion(html, Regex("<form[^>]*class=\"kcol-add\""))
        assertTrue(adder.contains("<input type=\"hidden\" name=\"new\" value=\"1\">"),
            "the line does not ask for a blank card")
        assertTrue(adder.contains("<input type=\"hidden\" name=\"column\" value=\"$site\">"),
            "the line does not say which column it writes into")
        assertTrue(adder.contains("+ Add task"), "the line does not say what it adds")
        assertFalse(adder.contains("name=\"bucket\""), "the card's opener would open the column popup")
        // It is a line, not a title box: the card is written in the popup, where the rest of it has somewhere
        // to go and a refusal has somewhere to print.
        assertFalse(adder.contains("<input type=\"text\""), "a bare title field is drawn on the board")
        // Nothing on this page opens the assigned board any more — the one thing that still points there is
        // the card popup's POST, which is one save endpoint for both boards by design (Stage 42).
        assertEquals(0, occurrences(html, "action=\"/app/assigned-tasks\""), "a control opens the other page")
        assertTrue(html.contains("action=\"/app/assigned-tasks/save\""), "the popup posts to another endpoint")
    }

    @Test
    fun `the popup a backlog line opens files the card in that column and asks for no project`() {
        val site = bucket("Site works")
        val depot = bucket("Fuel depot")
        val html = page(
            canManage = true,
            state = backlogFormOf(true, "EMP0001", BucketRepository.findAll(), into = depot),
        )
        assertTrue(elementWithId(html, "taskModal").orEmpty().contains("style=\"display:block\""),
            "?new=1&column=<id> did not put the popup up")
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.contains("<input type=\"hidden\" name=\"taskId\" value=\"0\">"),
            "the popup posts as an edit of some card")
        assertTrue(form.contains("id=\"taskColumn\""), "the popup has no field for the column it files the card in")
        assertTrue(selectedOption(form, "$depot"), "the picker does not open on the column that was pressed")
        assertTrue(form.contains(">Site works<") && form.contains(">Fuel depot<"),
            "the picker is not the board's columns")
        // The whole board, and none of it greyed: the column is management's to say on this board (Stage 49),
        // which is the only thing that separates this popup from the one an ordinary account is handed.
        assertFalse(selectTag(html, "column").contains("disabled"), "management was refused its own column picker")
        assertTrue(form.contains("A new card lands under Fuel depot."), "nothing says where the card will go")
        // The one thing that tells the two kinds of card apart: a backlog card belongs to a column and to no
        // project, so the slot a project card's holds its project in holds the column here.
        assertFalse(form.contains("id=\"taskProject\""), "a backlog card is asked which project it belongs to")
        assertFalse(form.contains("id=\"taskBucket\""), "a backlog card is asked which lane of a project it sits in")
        assertTrue(form.contains("Create task"), "the popup's own button still says Save")
        // And a card saved from here is answered here: `back=other` is what brings a refusal back to this board
        // rather than to the assigned one, which has no column on it to choose.
        assertTrue(form.contains("<input type=\"hidden\" name=\"back\" value=\"other\">"),
            "a refused save would answer on another page")
    }

    @Test
    fun `a viewer is handed no backlog create popup, only the editor of their own card`() {
        // Stage 51 retired the ordinary account's backlog create: writing a *new* card onto the shared board is
        // an Other-tasks function management keeps, and the board draws no `+ Add task` line for a Viewer (see
        // the scoped-board test). What survives is the personal path — the editor a Viewer opens on the card
        // that is already theirs — so this pins that the blank-create half of the popup is gone from their page
        // while their own card still opens. The create popup used to be handed a Viewer with the writer and the
        // starting status greyed; nothing on their page reaches that form any more.
        val site = bucket("Site works")
        val cardId = card("Grade the haul road", site, assignee = "EMP0002")
        val board = page(canManage = false)
        // No opener for a blank card, and the popup that would have answered one is shut.
        assertEquals(0, formTags(board, "kcol-add").size, "a Viewer was offered a line to create a backlog card")
        assertFalse(board.contains("name=\"new\""), "a Viewer's page still carries the create opener")
        assertTrue(taskPopup(board).isNotEmpty() && !taskPopup(board).contains("display:block"),
            "a Viewer's page arrived with the create popup already up")
        // Their own card, by contrast, still opens in the editor: the edit popup is the one that carries their
        // name and a greyed Column, and it is reachable because the card is theirs.
        val editor = page(
            canManage = false,
            state = editFormOf(
                TaskRepository.findById(cardId)!!, false, "EMP0002", backlogColumns = BucketRepository.findAll(),
            ),
        )
        assertTrue(taskPopup(editor).contains("style=\"display:block\""), "the owner's own card had no editor to open")
        assertEquals(1, formTags(editor, "kcard-open").size, "the owner cannot open their own backlog card")
    }

    @Test
    fun `a backlog card's popup opens on its own column and offers the board's other ones`() {
        val site = bucket("Site works")
        val depot = bucket("Fuel depot")
        val cardId = card("Grade the haul road", site, assignee = "EMP0002")
        ChecklistRepository.insert(cardId, "Ring the area")
        val html = page(
            canManage = false,
            state = editFormOf(
                TaskRepository.findById(cardId)!!, false, "EMP0002", backlogColumns = BucketRepository.findAll(),
            ),
        )
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.contains("<input type=\"hidden\" name=\"taskId\" value=\"$cardId\">"),
            "the popup posts a new card instead of this one")
        assertTrue(selectedOption(form, "$site"), "the picker does not open on the column the card stands in")
        assertTrue(form.contains(">Fuel depot<"), "the popup offers no other column to move the card to")
        // The column is greyed for this reader too (Stage 49). It used to be the one field wider than an
        // assigned card's, on the reasoning that nothing on this board drags; the drag is now management's on a
        // project, so the column here is theirs as well, and the popup says so under the picker rather than
        // leaving the greyed field to look like a page that lost its controls.
        assertTrue(selectTag(html, "column").contains("disabled"), "an ordinary account could move their own card")
        assertTrue(selectTag(html, "assignee").contains("disabled"), "the owner is offered the assignee picker")
        assertTrue(selectTag(html, "status").contains("disabled"), "the owner is offered the status picker")
        // An edit prints the whole board greyed rather than the one column pinned, the way the lane picker has
        // since Stage 48: the field still has to say which column the card stands in, and a reader who could
        // choose from the list would find nothing to say they cannot.
        assertTrue(html.contains("The dates and the priority are yours to set."),
            "the popup explains nothing about the fields it has greyed")
        assertTrue(html.contains("Management moves a card between the columns."),
            "nothing names the reader who answers the column")
        assertFalse(form.contains("id=\"taskProject\""), "a backlog card is asked which project it belongs to")
        // Ticking a step of this card moves the card out on the column it is standing in — the same region the
        // assigned board swaps, which is why that region has to exist here too.
        val ticker = formWithAction(html, "/app/tasks/checklist/toggle")
        assertTrue(ticker.contains("data-live-extra=\"#project-progress,#${cardBodyRegion(cardId)}\""),
            "a tick on a backlog card leaves the card's own percentage behind")
    }

    @Test
    fun `a refused backlog create comes back as the popup with its reason under the column field`() {
        bucket("Site works")
        val html = page(
            canManage = true,
            state = TaskForm(
                open = false,
                canCreate = true,
                // The half this popup answers as. Since Stage 48 the form carries that answer itself, and a
                // create for this board has no row to read it off: without this flag the refusal would come
                // back as the *project* half of the form — its own error still set, but printed under a
                // Column picker this page never drew.
                backlog = true,
                backlogColumns = BucketRepository.findAll(),
                values = TaskDraft(title = "Clean the spill bund", columnId = null),
                errors = mapOf(TaskField.COLUMN to "Choose which column this card sits in."),
            ),
        )
        // A refusal is this markup with the popup up, not a redirect: a redirect lands on a closed popup with
        // nowhere for the sentence to go.
        assertTrue(taskPopup(html).contains("style=\"display:block\""), "the refusal closed the popup that was typed in")
        assertEquals(1, occurrences(html, "field-err"), "the page explains itself somewhere besides that field")
        assertTrue(html.contains("value=\"Clean the spill bund\""), "what was typed is not back in the field")
        assertTrue(html.contains(">Site works<"), "the picker came back with no columns")
        val column = html.indexOf("id=\"taskColumn\"")
        val reason = html.indexOf("Choose which column this card sits in.")
        assertTrue(column >= 0 && column < reason, "the reason did not come back under its own field")
    }

    @Test
    fun `a backlog card prints its own percentage on the column it stands in`() {
        val site = bucket("Site works")
        val planned = card("Grade the haul road", site, assignee = "EMP0002")
        val first = ChecklistRepository.insert(planned, "Ring the area")
        ChecklistRepository.insert(planned, "Water the surface")
        ChecklistRepository.setDone(first, true)
        val unplanned = card("Replace the pump seal", site, assignee = "EMP0002")
        val body = region(page(canManage = true), cardBodyRegion(planned))
        assertTrue(body.contains("kpct"), "the card prints no percentage of its own")
        assertTrue(body.contains(">50%<"), "the chip is not what the card's own steps add up to")
        // The same rule the assigned board prints by: a card nobody has planned is unmeasured, not at zero.
        assertFalse(region(page(canManage = true), cardBodyRegion(unplanned)).contains("kpct"),
            "a card with no steps prints a percentage")
    }

    @Test
    fun `the popup arrives closed and both openers put it up`() {
        val site = bucket("Site works")
        card("Grade the haul road", site)
        val closed = page(canManage = true)
        assertFalse(elementWithId(closed, "bucketModal").orEmpty().contains("display:block"), "the popup is up on load")
        assertFalse(closed.contains("field-err"), "a blank page explains nothing")
        val added = page(canManage = true, bucket = BucketForm(open = true))
        assertTrue(elementWithId(added, "bucketModal").orEmpty().contains("style=\"display:block\""),
            "?newbucket=1 did not put the popup up")
        assertTrue(popup(added).contains("Add a bucket"), "the create half of the popup is not the one drawn")
        val managed = page(canManage = true, bucket = BucketForm(bucketId = site))
        assertTrue(elementWithId(managed, "bucketModal").orEmpty().contains("style=\"display:block\""),
            "?bucket=<id> did not put the popup up")
        assertFalse(popup(managed).contains("Add a bucket"), "the column's menu opened the create form")
        // The other popup is a different question: opening this one must not raise that one.
        assertFalse(
            elementWithId(added, "taskModal").orEmpty().contains("display:block"),
            "the bucket opener also opened the task editor",
        )
    }

    @Test
    fun `the create popup names the board it adds a column to and itself`() {
        val html = page(canManage = true, bucket = BucketForm(open = true))
        val row = elementWithId(html, "otherBoard")
        assertTrue(row != null && row.contains("other-groups"), "the backlog board has no id to swap into")
        val poster = formWithAction(html, "/app/other-buckets")
        assertTrue(poster.contains("data-live-target=\"#otherBoard\""), "a new column would restart the page")
        assertTrue(poster.contains("data-live-extra=\"#bucketModal,#bucketsToggle\""), "the popup is not told to close itself")
        assertTrue(poster.contains("method=\"post\""), "the name is not posted: $poster")
        assertTrue(poster.contains("name=\"name\""), "the popup has no field for the name")
        assertTrue(poster.contains("id=\"bucketName\""), "the field cannot be focused when the popup lands")
        assertTrue(poster.contains("required"), "the field would post an empty name")
        // Two ways out without posting anything, both named apart from the task popup's own markers so one
        // click is never read as closing two different popups by two different files.
        assertEquals(2, occurrences(html, "data-bucket-modal-close"), "the × and Cancel are not both there")
        assertTrue(poster.contains("Cancel"), "the popup has no Cancel line")
        assertTrue(html.contains("/static/js/bucket-modal.js"), "nothing can close the popup")
    }

    @Test
    fun `a viewer sees only their own backlog card and none of the board's column tools`() {
        // Stage 51: the shared backlog and its columns are management's function again. A Viewer reaches this page
        // only to see and work the card that is *theirs* — the one a management assign or their own notification
        // handed them. Everything that describes the board as a whole is taken back: the columns they own nothing
        // in, the cards in them that belong to somebody else, the dashed box that names a column, the add line
        // that writes a new card, the column popup and the show-all toggle. The one path kept personal is their
        // own card, which still opens in the editor and still carries the status arrows an owner is given.
        val site = bucket("Site works")
        card("Grade the haul road", site, assignee = "EMP0002")
        card("Someone else's job", site, assignee = "EMP0001")
        card("A card nobody has taken", site)
        bucket("Idle plant")
        val html = page(canManage = false, showAll = true)
        val row = board(html)
        // Their own card, and nothing of the rest of the board's contents.
        assertTrue(row.contains("Grade the haul road"), "the reader's own card is missing from their board")
        assertFalse(row.contains("Someone else's job"), "a Viewer was shown a backlog card that is not theirs")
        assertFalse(row.contains("A card nobody has taken"), "an unassigned card leaked onto a Viewer's board")
        // A bucket holding none of their cards is not part of their view — the board prints exactly one group,
        // the bucket their own card stands in (Stage 57's per-bucket status board).
        assertFalse(row.contains(">Idle plant<"), "a Viewer was shown a bucket with none of their cards in it")
        assertEquals(1, occurrences(row, "bucket-group-head"), "a Viewer was shown more than their own bucket")
        // None of the board-management UI is drawn for them: the box that names a column, its opener, the popup
        // it swaps into, the per-column add line, the column menu, and the head's show-all toggle.
        assertFalse(html.contains("+ Add bucket"), "a Viewer was offered a column to name")
        assertFalse(html.contains("newbucket"), "the dashed box is drawn where a Viewer cannot use it")
        assertTrue(elementWithId(html, "bucketModal") == null, "a Viewer was handed the column popup")
        assertEquals(0, formTags(html, "kcol-add").size, "a Viewer was offered a line to write a new card")
        assertFalse(row.contains("kcol-menu"), "a Viewer was offered a column to rename or delete")
        assertFalse(row.contains("⋮"), "a Viewer was given the column menu")
        assertFalse(html.contains("/rename\""), "a Viewer's page can rename a column")
        assertFalse(html.contains("/delete\""), "a Viewer's page can empty a column")
        // The show-all toggle is a board-management view and is not drawn for them (the head asks `canManage`).
        // The word `allbuckets` may still ride in their own card's editor opener — carrying a view they are
        // already standing on is not a management control, so the toggle *node* is what is asserted away here.
        assertTrue(elementWithId(html, "bucketsToggle") == null, "a Viewer was offered the board-management view")
        assertFalse(html.contains("buckets-toggle"), "the show-all toggle is drawn on a Viewer's board")
        // The one thing that stays theirs is their own card: it opens in the shared editor, and the editor's
        // landing point exists on the page for `?edit=` to swap into. The owner's status arrows print because
        // the popup's Status field is management's alone.
        assertEquals(1, formTags(html, "kcard-open").size, "the reader's own card does not open its editor")
        assertTrue(elementWithId(html, "taskModal") != null, "their own card had no editor to open")
        assertTrue(row.contains("kmove-btn"), "the owner was given no way to move their own card's status")
        // The card they do not own has no opener for them, confirmed by the single kcard-open counting only theirs.
        assertFalse(row.contains("aria-label=\"Open Someone else's job\""), "a Viewer can open a card not theirs")
        // Management still sees the whole board, every card and every tool, on the same page.
        val admin = page(canManage = true, showAll = true)
        val adminRow = board(admin)
        assertTrue(adminRow.contains("Grade the haul road"), "management lost a backlog card")
        assertTrue(adminRow.contains("Someone else's job"), "management does not see everybody's cards")
        assertTrue(adminRow.contains("A card nobody has taken"), "an unassigned card is missing from management")
        assertTrue(adminRow.contains(">Idle plant<"), "the wide board hid a column from management")
        assertTrue(admin.contains("+ Add bucket"), "management lost the box that names a column")
        assertEquals(2, formTags(admin, "kcol-add").size, "management lost a column's add line")
        assertTrue(elementWithId(admin, "bucketModal") != null, "management lost the column popup")
    }

    @Test
    fun `the view the reader is standing on travels with every write`() {
        // `?allbuckets` is a view, not a right. A refusal, a rename, a delete and an add all answer on the board
        // the reader was looking at, or the column they just created would be hidden again by their own response.
        val site = bucket("Site works")
        bucket("Idle plant")
        card("Grade the haul road", site)
        val narrow = page(canManage = true, bucket = BucketForm(open = true))
        val wide = page(canManage = true, bucket = BucketForm(open = true), showAll = true)
        assertFalse(formWithAction(narrow, "/app/other-buckets").contains("allbuckets"),
            "the create would widen a board the reader chose to keep narrow")
        assertTrue(formWithAction(wide, "/app/other-buckets")
            .contains("<input type=\"hidden\" name=\"allbuckets\" value=\"1\">"),
            "a write that landed would answer on the narrow board")
        // Both columns of the wide board draw their menu and their add line, and each one carries the view; the
        // click on the one card here carries it too — five forms on the board. The head's add-bucket button left
        // the board (Stage 59) but still carries the view, asserted separately below.
        assertEquals(5, occurrences(board(wide), "<input type=\"hidden\" name=\"allbuckets\" value=\"1\">"),
            "the board's own forms do not all keep the wide view")
        assertFalse(board(narrow).contains("allbuckets"), "the narrow board's forms ask to be widened")
        // The head's add-bucket button travels with the wide view too, now that it lives there rather than on the board.
        assertTrue(formRegion(wide, Regex("<form[^>]*class=\"opener-form addbucket-form\""))
            .contains("<input type=\"hidden\" name=\"allbuckets\" value=\"1\">"),
            "the head's add-bucket button would answer on the narrow board")
        assertFalse(formRegion(narrow, Regex("<form[^>]*class=\"opener-form addbucket-form\""))
            .contains("allbuckets"), "the narrow head's add-bucket button asks to be widened")
        // The card makes two trips of its own, and both load a page rather than swapping a block: the click
        // that opens the editor is a GET onto this page, and the save posts back to it. Either one answering
        // narrow would hide the columns the reader had just opened up — the popup closing on them makes it
        // worse still, because the board they pressed a card on is gone.
        assertTrue(formRegion(wide, Regex("<form[^>]*class=\"kcard-open\"")).contains("name=\"allbuckets\""),
            "opening a card off the wide board hid its own columns")
        assertFalse(formRegion(narrow, Regex("<form[^>]*class=\"kcard-open\"")).contains("name=\"allbuckets\""),
            "the narrow board's click asks to be widened")
        assertTrue(formWithAction(wide, "/app/assigned-tasks/save").contains("name=\"allbuckets\""),
            "a save from the wide view answers on the narrow board")
        assertFalse(formWithAction(narrow, "/app/assigned-tasks/save").contains("name=\"allbuckets\""),
            "a save from the narrow view widens the board on its own")
    }
}

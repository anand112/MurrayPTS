package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.Parameters
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import mn.murray.pts.service.TaskService
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
 * [page] helper renders it the way the page does: the head's `+ Add bucket`, above the board from
 * [otherTasksContent]. Every control on it is a GET onto `/app/other-tasks` because every one of
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
     * The page as the reader gets it: the head's one control, then the board. `appPage` renders `actions` into the
     * head and the block below it, so this is the one place the two halves can be read together — which is what
     * lets a test say the head offers nothing the board cannot answer.
     */
    private fun page(
        canManage: Boolean,
        bucket: BucketForm = BucketForm(),
        state: TaskForm? = null,
        showBucketId: Int = 0,
    ): String = render {
        // Stage 59: the head's small `+ Add bucket` button prints for management only, the way
        // `respondOtherTasks` gates it, so this helper mirrors that. Since Stage 93 it carries the board's bucket
        // filter, and a stale id is not a filter — the same read `respondOtherTasks` makes. *(Until **Stage 97**
        // a second control shared the head, the `Show all buckets` toggle, and this helper asked
        // [otherToggleApplies] before drawing it; the toggle, its flag and its wide view are gone.)*
        val filtered = TaskService.bucketRows().any { it.id == showBucketId }
        val filterId = if (filtered) showBucketId else 0
        if (canManage) addBucketButton(filterId)
        otherTasksContent(
            if (canManage) manager else engineer, canManage, state, bucket = bucket,
            showBucketId = showBucketId,
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

    /**
     * A backlog card's own opening tag: `kcard`, then its stage tint (Stage 95), then `kcard-overlay` — the class
     * that marks the card wearing a whole-card opener, which is a layout hook and not a colour.
     */
    private fun cardTag(html: String): List<String> =
        Regex("<div[^>]*class=\"kcard s-[a-z]+ kcard-overlay\"[^>]*>").findAll(html).map { it.value }.toList()

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
        // The card is still a backlog card — it carries the whole-card opener's node class — and its status is said
        // once, by the column it stands in, not repeated on the card body the way the old per-bucket column did.
        // What it now wears, since Stage 95, is the same stage tint the assigned board paints.
        assertEquals(1, cardTag(row).size, "the backlog card is not drawn as a backlog card")
        assertTrue(row.contains("class=\"kcard s-inprogress kcard-overlay\""), "the backlog card wears no status tint")
        assertFalse(row.contains("s-other"), "the backlog card is still drawn in its own edge colour")
        assertFalse(row.contains("ostatus"), "the card still prints its own status word")
        assertTrue(row.contains("<input type=\"hidden\" name=\"column\" value=\"$site\">"),
            "the bucket has no line to add a card to")
        // The page says once what the board is: nothing under the head repeats the page's own name.
        assertFalse(row.contains("other-head"), "the board still carries a heading of its own")
    }

    @Test
    fun `the head offers one control and the picker is the board's only view`() {
        // **Stage 97.** The ask was the button, with the reason attached: "no need this button on other tasks
        // function since we have dropdown". The dropdown answers both halves of what the toggle did — name a
        // bucket to see an empty one, name *All buckets* to come back — so the toggle, the `allbuckets` flag and
        // the wide view they carried went together. What is pinned here is that none of them is left half-gone:
        // no control, no flag on any form, and no stale companion list pointing at a node the page no longer draws.
        val site = bucket("Site works")
        bucket("Idle plant")
        card("Grade the haul road", site)
        for (html in listOf(
            page(canManage = true),
            page(canManage = true, showBucketId = site),
            page(canManage = true, bucket = BucketForm(open = true)),
            page(canManage = false),
        )) {
            assertFalse(html.contains("allbuckets"), "a retired view is still being carried")
            assertFalse(html.contains("buckets-toggle"), "the head still draws the show-all control")
            assertTrue(elementWithId(html, "bucketsToggle") == null, "a node nobody draws is still named")
        }
        // The head's one control, and only management's.
        assertEquals(1, formTags(page(canManage = true), "opener-form addbucket-form").size,
            "the head is not down to one control")
        // And the view the reader can still change is the picker's: an empty bucket is reached by naming it.
        val idle = TaskService.bucketRows().first { it.name == "Idle plant" }.id
        assertFalse(board(page(canManage = true)).contains(">Idle plant<"),
            "the narrow board widened itself for nobody")
        val named = board(page(canManage = true, showBucketId = idle))
        assertTrue(named.contains(">Idle plant<"), "naming an empty bucket does not reveal it")
        assertTrue(named.contains("Nothing here."), "the revealed bucket prints no empty status columns")
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
        assertTrue(box.contains("data-live-extra=\"#bucketModal\""),
            "the button leaves the popup it opened stale: $box")
        assertFalse(box.contains("bucketsToggle"), "the button names a control the page no longer draws: $box")
        // It reads as the head's other small buttons — `+ New project` on the projects page — so the whole app's
        // "add one to this page" controls are indistinguishable in shape.
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
        // It swaps one block and nothing else: the head used to name its toggle here too, and the toggle is gone.
        assertFalse(menu.contains("data-live-extra"), "the menu names a companion block the page does not draw: $menu")
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
        assertTrue(rename.contains("data-live-extra=\"#bucketModal,#otherBucketFilter\""), "a rename leaves the board up")
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
        // The empty column is not on the narrow board, so the reader reaching its popup is reading it through the
        // picker: the same `?show=` the picker posts, and the popup the `⋮` opens over it.
        val html = page(canManage = true, bucket = BucketForm(bucketId = idle), showBucketId = idle)
        val form = popup(html)
        assertTrue(
            formWithAction(form, "/app/other-buckets/$idle/delete").isNotEmpty(),
            "an empty column cannot be taken off the board",
        )
        assertFalse(form.contains("bucketMove"), "a destination was asked for a column holding nothing")
        assertTrue(form.contains("moves nothing"), "the popup does not say that deleting it moves nothing")
    }

    @Test
    fun `the picker reveals a bucket the board is hiding`() {
        // What the head's toggle used to do, done by the control that stays. The default board prints the buckets
        // that hold work; naming one of the others in the picker prints its group — five *Nothing here.* columns,
        // the same empty answer a project board gives — and *All buckets* is the way back.
        val site = bucket("Site works")
        val idle = bucket("Idle plant")
        card("Grade the haul road", site)
        val hidden = page(canManage = true)
        assertFalse(board(hidden).contains(">Idle plant<"), "the board drew a bucket that holds nothing")
        assertTrue(selectedOptionIn(hidden, "show", "0"), "the unfiltered board did not open on All buckets")
        assertFalse(selectedOptionIn(hidden, "show", "$idle"), "a hidden bucket came up as chosen")
        val shown = page(canManage = true, showBucketId = idle)
        val row = board(shown)
        assertTrue(row.contains(">Idle plant<"), "naming an empty bucket did not draw it")
        assertFalse(row.contains(">Site works<"), "the filter printed the whole board")
        assertEquals(1, occurrences(row, "bucket-group-head"), "the chosen bucket is not one group")
        assertEquals(TASK_STATUSES.size, occurrences(row, "kcol-head"), "the bucket did not print its status columns")
        assertTrue(row.contains("Nothing here."), "an empty status column says nothing about itself")
        assertTrue(selectedOptionIn(shown, "show", "$idle"), "the picker did not stay on the bucket it was asked for")
        // And the way back is the same control, not a second press of something else.
        assertTrue(pickerForm(shown).contains(">All buckets</option>"), "the narrowed board cannot be un-narrowed")
    }

    /** The board's picker, from its own form tag through its close. */
    private fun pickerForm(html: String): String =
        formRegion(html, Regex("<form[^>]*class=\"board-toolbar other-bucket-filter\""))

    @Test
    fun `a board with nothing hidden says so and grows no extra control`() {
        val site = bucket("Site works")
        card("Grade the haul road", site)
        val html = page(canManage = true)
        assertEquals(1, occurrences(html, "opener-form"), "the head grew a second control for a board with nothing to show")
        assertFalse(html.contains("allbuckets"), "the page carries a view nobody was offered")
        assertFalse(html.contains("Show all buckets"), "the retired toggle is still announcing itself")
        assertFalse(html.contains("Show fewer buckets"), "the retired toggle's other half is still drawn")
    }

    @Test
    fun `a bucket that has just been named lands on its own filter`() {
        // An empty column that disappears the moment it appears reads as a control that does not work. The create
        // used to answer with the whole board wide open; since Stage 97 it answers narrowed to the newcomer, which
        // is the same sight and leaves the picker holding one state rather than two. The route's redirect is
        // `bucketsBack(createdId)`, and the page it names is this one.
        val site = bucket("Site works")
        val html = page(canManage = true, bucket = BucketForm(open = true), showBucketId = site)
        assertTrue(board(html).contains(">Site works<"), "the create closed over a board that hid the new column")
        assertEquals(1, occurrences(board(html), "bucket-group-head"), "the create's answer printed the whole board")
        assertTrue(selectedOptionIn(html, "show", "$site"), "the picker forgot the column the reader just added")
    }

    @Test
    fun `a backlog card opens the editor popup on the card itself`() {
        val site = bucket("Site works")
        val cardId = card("Grade the haul road", site, status = "In progress")
        val html = page(canManage = true)
        val card = cardTag(html).first()
        assertTrue(card.contains("data-id=\"$cardId\""), "the card does not name itself: $card")
        // Stage 58: management moves a backlog card by dragging it between the status columns, so their copy is
        // draggable — the one attribute `kanban.js` looks for on a drag source, and it prints for exactly the
        // reader whose status write `/app/tasks/status` accepts.
        assertTrue(card.contains("draggable=\"true\""), "management cannot drag a backlog card: $card")
        // Stage 95: the same node wears the assigned board's tint, and the opener class that used to carry its own
        // slate edge now only says which card has a whole-card form.
        assertTrue(card.contains("class=\"kcard s-inprogress kcard-overlay\""), "the card wears no stage tint: $card")
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
    fun `a backlog card's status is moved by the column, and the board prints no button for anybody`() {
        val site = bucket("Site works")
        val cardId = card("Grade the haul road", site, assignee = "EMP0002", status = "In progress")
        val mine = page(canManage = false)
        val row = board(mine)
        // **Stage 103 retired the prev/next arrows** on both status boards, so the card that sits between two
        // statuses grows nothing: no `.kmove` row, no `kmove-btn`, and no arrow character anywhere on the board.
        assertFalse(row.contains("kmove"), "a backlog card grew a button row again")
        assertFalse(row.contains("← "), "the way back came back")
        // What the owner keeps is the two affordances everyone else has: the grab, and the click. The drag is
        // Stage 58's path to the same `/app/tasks/status` the arrows used to post.
        assertTrue(
            cardTag(row).singleOrNull()?.contains("draggable=\"true\"") == true,
            "the owner cannot drag their own backlog card",
        )
        assertTrue(row.contains("data-id=\"$cardId\""), "the card does not name itself for the drop")
        // The card is still clickable for its owner, and the editor popup exists for `?edit=` to land in. The
        // count is on the form's own class, since the button inside it is named `kcard-open-btn` as well.
        assertEquals(1, formTags(mine, "kcard-open").size, "an owner cannot open their own backlog card")
        assertTrue(elementWithId(mine, "taskModal") != null, "the backlog's only card had no editor to open")
        // Management never had buttons — the popup owns their Status — and now nobody does.
        assertFalse(board(page(canManage = true)).contains("kmove"),
            "a button was drawn on a card management edits in the popup")
    }

    @Test
    fun `a backlog card drags between status columns, never a bucket column`() {
        // Stage 58: dragging a card along the bucket's status row is the way a status is changed here — the
        // arrows that posted the same write were retired in Stage 103. `kanban.js` starts a drag on a
        // `.kcard[draggable="true"]` and only recognises a status
        // drop on a `.kcol[data-status]`. But the card's editor opens through a whole-card overlay (Stage 40), and
        // that overlay's `<button>` is the node the pointer actually grabs — a button is not a native drag origin, so
        // without a handle on the overlay itself nothing dragged (the bug this fixes). So BOTH halves are pinned here:
        // the card carries `draggable` (the `data-id` carrier `cardOf` climbs to) and every opener button carries
        // `draggable` (the handle the drag starts on). And a backlog column is a STATUS, never a `[data-lane]` bucket
        // column, which is what keeps a drag from filing the card into another bucket.
        val site = bucket("Site works")
        card("Grade the haul road", site, assignee = "EMP0002")
        card("Re-stock the laydown", site, assignee = "EMP0002", status = "In progress")

        val html = page(canManage = true)
        val management = board(html)
        assertEquals(1, occurrences(html, "data-kanban-board=\"true\""),
            "a backlog status move has no board boundary or quiet-refresh target")
        // Every backlog card is both a draggable node and has a draggable opener button — one handle per card, so no
        // card management cannot move looks grabbable and none they can move stays stuck.
        val openers = Regex("<button[^>]*class=\"kcard-open-btn\"[^>]*>").findAll(management).toList()
        assertEquals(2, openers.size, "management's board did not print one opener per backlog card")
        assertTrue(
            openers.all { it.value.contains("draggable=\"true\"") && it.value.contains("data-drag-handle=\"true\"") },
            "a backlog card's whole-card opener was not an explicit drag handle, so dragging never starts",
        )
        val cards = cardTag(management)
        assertEquals(2, cards.size, "management's board did not print both backlog cards")
        assertTrue(
            cards.all { it.contains("draggable=\"true\"") },
            "a movable backlog card did not carry the draggable node kanban.js climbs to",
        )

        // The columns are statuses: five per bucket head, and there is no lane column anywhere, so every drop on this
        // board posts `/app/tasks/status` and the bucket the card sits under is untouched.
        assertEquals(TASK_STATUSES.size, occurrences(management, "kcol-head"), "the bucket did not print its five status columns")
        assertFalse(management.contains("data-lane"), "a backlog column was a bucket lane a drag could re-file into")
    }

    @Test
    fun `a backlog card wears the same stage tint the assigned board paints`() {
        // Stage 95. Both boards cut their cards by the same five statuses, so a reader who looks at the assigned
        // page and then at this one was seeing one stage painted two ways: the assigned card wore its stage's edge
        // and wash, the backlog card wore a slate edge of its own no matter what it was. Every status is now drawn
        // here with the very class [mn.murray.pts.taskCard] puts on its card — one mapping, [statusSlug], both
        // boards reading it. What stays the backlog's own is the opener node (`kcard-overlay`, a layout hook) and
        // the silence: the column head above the card already says the word (Stage 57), so no `.kstat` is printed.
        val site = bucket("Site works")
        TASK_STATUSES.forEachIndexed { i, status -> card("Haul $i loads", site, status = status) }
        val row = board(page(canManage = true))
        TASK_STATUSES.forEach { status ->
            val slug = when (status) {
                "In progress" -> "s-inprogress"
                "On hold" -> "s-onhold"
                "Blocked" -> "s-blocked"
                "Completed" -> "s-completed"
                else -> "s-notstarted"
            }
            assertTrue(
                row.contains("class=\"kcard $slug kcard-overlay\""),
                "a $status backlog card did not wear the assigned board's $slug tint",
            )
        }
        assertEquals(TASK_STATUSES.size, cardTag(row).size, "not every card on the board is a tinted backlog card")
        assertFalse(row.contains("s-other"), "the backlog still carries its own edge colour")
        assertFalse(row.contains("kstat"), "a card under its own status column was told its status again")
    }

    @Test
    fun `a backlog column's add line opens the card's own popup on that column`() {
        val site = bucket("Site works")
        val overhaul = bucket("Overhaul")
        card("Grade the haul road", site)
        card("Strip the loader", overhaul)
        val html = page(canManage = true)
        // The lane list's own control, on both columns of the board: same class, same GET, same target —
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
        assertTrue(poster.contains("data-live-extra=\"#bucketModal,#otherBucketFilter\""), "the popup is not told to close itself")
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
        // that writes a new card, the column popup and the board's picker. The one path kept personal is their
        // own card, which still opens in the editor and still carries the drag an owner is given.
        val site = bucket("Site works")
        val overhaul = bucket("Overhaul")
        card("Grade the haul road", site, assignee = "EMP0002")
        card("Someone else's job", site, assignee = "EMP0001")
        card("A card nobody has taken", site)
        card("Strip the loader", overhaul)
        bucket("Idle plant")
        val html = page(canManage = false)
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
        // The board's view is a board-management read, so no control for it is drawn for them — and since
        // Stage 97 the only such control there is, is the picker.
        assertTrue(elementWithId(html, "otherBucketFilter") == null, "a Viewer was handed the shared board's picker")
        assertFalse(html.contains("allbuckets"), "the retired wide view is carried on a Viewer's page")
        assertFalse(html.contains("buckets-toggle"), "the show-all toggle is drawn on a Viewer's board")
        // The one thing that stays theirs is their own card: it opens in the shared editor, the editor's
        // landing point exists on the page for `?edit=` to swap into, and the card is grabbable — the drag is
        // how a status changes now that Stage 103 took the arrows off this board.
        assertEquals(1, formTags(html, "kcard-open").size, "the reader's own card does not open its editor")
        assertTrue(elementWithId(html, "taskModal") != null, "their own card had no editor to open")
        assertTrue(
            cardTag(row).singleOrNull()?.contains("draggable=\"true\"") == true,
            "the owner was given no way to move their own card's status",
        )
        assertFalse(row.contains("kmove"), "the retired arrows are still drawn for an owner")
        // The card they do not own has no opener for them, confirmed by the single kcard-open counting only theirs.
        assertFalse(row.contains("aria-label=\"Open Someone else's job\""), "a Viewer can open a card not theirs")
        // Management still sees the whole board, every card and every tool, on the same page.
        val admin = page(canManage = true)
        val adminRow = board(admin)
        assertTrue(adminRow.contains("Grade the haul road"), "management lost a backlog card")
        assertTrue(adminRow.contains("Someone else's job"), "management does not see everybody's cards")
        assertTrue(adminRow.contains("A card nobody has taken"), "an unassigned card is missing from management")
        assertTrue(adminRow.contains("Strip the loader"), "management lost a second bucket's card")
        assertFalse(adminRow.contains(">Idle plant<"), "the board printed a bucket holding nothing")
        assertTrue(admin.contains("+ Add bucket"), "management lost the box that names a column")
        assertEquals(2, formTags(admin, "kcol-add").size, "management lost a column's add line")
        assertTrue(elementWithId(admin, "bucketModal") != null, "management lost the column popup")
        // The bucket the board is hiding is one choice away, and the choice is the picker's.
        val idle = TaskService.bucketRows().first { it.name == "Idle plant" }.id
        assertTrue(board(page(canManage = true, showBucketId = idle)).contains(">Idle plant<"),
            "management has no way to see the empty bucket")
    }

    @Test
    fun `no write on this page carries a view state any more`() {
        // **Stage 97.** Every form used to hold a second piece of state besides the filter — the hidden
        // `allbuckets` field, so a refusal, a rename, a delete or an add could answer on the wide board the reader
        // was standing on. The wide board is gone, so the field is gone from every one of them, and the filter is
        // the only view the page has to carry. `the chosen bucket travels with every way off the board` below pins
        // the half that remains: the same forms still carrying `show`.
        val site = bucket("Site works")
        bucket("Idle plant")
        val cardId = card("Grade the haul road", site)
        val plain = page(canManage = true, bucket = BucketForm(open = true))
        val narrowed = page(
            canManage = true,
            bucket = BucketForm(bucketId = site),
            state = editFormOf(
                TaskRepository.findById(cardId)!!, true, "EMP0001", backlogColumns = BucketRepository.findAll(),
            ),
            showBucketId = site,
        )
        for ((label, html) in listOf("unfiltered board" to plain, "filtered board" to narrowed)) {
            assertFalse(html.contains("allbuckets"), "$label still carries the retired wide view")
            assertFalse(html.contains("buckets-toggle"), "$label still draws the retired control")
        }
        // No form names a companion block that no longer exists, either — a swap of a missing node is a silent
        // half-update, which is the failure this mechanism exists to prevent.
        assertFalse(narrowed.contains("#bucketsToggle"), "a swap list still names the toggle")
    }

    // --- Stage 93: the board's own Bucket picker -------------------------------------------------

    @Test
    fun `the board carries the same bucket picker the assigned page has`() {
        // The user's ask, "on the other tasks make it same as assigned tasks' other tasks make a dropdown for
        // bucket choosing". Same control, same classes, same label, same `data-autosubmit` — the page where the
        // backlog lives simply did not have one.
        val hr = bucket("HR")
        val site = bucket("Site works")
        card("Sign the contract", hr)
        card("Re-line the crusher", site)
        val html = page(canManage = true)
        val picker = formRegion(html, Regex("<form[^>]*class=\"board-toolbar other-bucket-filter\""))
        assertTrue(picker.isNotEmpty(), "the board has no bucket picker")
        assertTrue(picker.contains("action=\"/app/other-tasks\""), "the picker does not come back here")
        assertTrue(picker.contains("method=\"get\""), "the picker is not a view of this page")
        assertTrue(picker.contains("name=\"show\""), "the picker does not post the filter")
        // `?bucket=` is the column popup on this URL; a filter re-using it would make one bookmark two pages.
        assertFalse(picker.contains("name=\"bucket\""), "the filter would open a column's popup")
        assertTrue(picker.contains("data-autosubmit"), "picking a bucket would wait for a second press")
        assertTrue(picker.contains(">All buckets</option>"), "the whole board is not offered")
        assertTrue(picker.contains(">HR</option>") && picker.contains(">Site works</option>"),
            "the board's columns are not offered")
        // Stage 89's rule, unchanged: the select sits inside its label, the pair the field styling keys on.
        val label = Regex("<label class=\"board-key\">.*?</label>", RegexOption.DOT_MATCHES_ALL)
            .find(picker)?.value.orEmpty()
        assertTrue(label.contains(">Bucket<"), "the picker's field has no label of its own")
        assertTrue(label.contains("id=\"otherBucket\""), "the select left its label, and the styling with it")
        // The answer is swapped into the board rather than reloading the page: the board is the live region and
        // the head's button rides along, because it carries the filter in its own hidden field and must stop
        // carrying the one that is gone.
        assertTrue(elementWithId(html, "otherBoard").orEmpty().contains("data-live-region"),
            "the board cannot be replaced in place")
        assertTrue(picker.contains("data-live-extra=\"#addBucketOpener\""),
            "the picker leaves the head's button on the old view")
        assertTrue(selectedOptionIn(html, "show", "0"), "the unfiltered board did not open on All buckets")
    }

    @Test
    fun `choosing a bucket prints that bucket's group and no other`() {
        val hr = bucket("HR")
        val site = bucket("Site works")
        card("Sign the contract", hr)
        card("Re-line the crusher", site)
        val html = page(canManage = true, showBucketId = site)
        val row = board(html)
        assertTrue(row.contains(">Site works<"), "the chosen bucket has no group")
        assertFalse(row.contains(">HR<"), "another bucket was left on the narrowed board")
        assertEquals(1, occurrences(row, "bucket-group-head"), "the filter printed more than one bucket")
        assertTrue(row.contains("Re-line the crusher"), "the chosen bucket's card is missing")
        assertFalse(row.contains("Sign the contract"), "another bucket's card was printed under the filter")
        assertTrue(selectedOptionIn(html, "show", "$site"), "the picker did not open on the chosen bucket")
        assertFalse(selectedOptionIn(html, "show", "0"), "All buckets stayed selected")
        // Still the whole status board under the one name.
        assertEquals(TASK_STATUSES.size, occurrences(row, "kcol-head"), "the filter redrew the columns")
        // A bucket the reader names explicitly is printed even when it holds nothing — an empty answer that names
        // it is honest; silently showing the other buckets instead would be the wrong answer to a direct question.
        val idle = bucket("Idle plant")
        val empty = page(canManage = true, showBucketId = idle)
        assertTrue(board(empty).contains(">Idle plant<"), "an explicitly chosen empty bucket was hidden")
        assertTrue(board(empty).contains("Nothing here."), "the empty status columns say nothing")
        // A stale id — a deleted column, an old bookmark — answers with every bucket, never an empty board.
        val stale = page(canManage = true, showBucketId = 9999)
        assertTrue(board(stale).contains(">Site works<") && board(stale).contains(">HR<"),
            "a stale bucket id hid the board")
        assertTrue(selectedOptionIn(stale, "show", "0"), "a stale bucket id left the picker on nothing")
    }

    @Test
    fun `a Viewer's page keeps its one bucket and grows no picker`() {
        // The shared board is management's, so its filter is too: a Viewer keeps the slice Stage 51 gave them —
        // the bucket their own card stands in — and an address bar naming another bucket neither lists one they
        // cannot see nor prints an empty group under its name.
        val hr = bucket("HR")
        val site = bucket("Site works")
        card("Sign the contract", hr, assignee = "EMP0002")
        val own = page(canManage = false)
        assertFalse(own.contains("other-bucket-filter"), "a Viewer was handed the shared board's picker")
        assertFalse(board(own).contains("Site works"), "a Viewer's page named a bucket they hold no card in")
        val aimed = page(canManage = false, showBucketId = site)
        assertFalse(aimed.contains("other-bucket-filter"), "a URL filter put a picker on a Viewer's page")
        assertFalse(board(aimed).contains("Site works"), "a URL filter printed a bucket the Viewer cannot see")
        assertTrue(board(aimed).contains("Sign the contract"), "a URL filter hid the Viewer's own card")
    }

    @Test
    fun `the chosen bucket travels with every way off the board`() {
        val site = bucket("Site works")
        val other = bucket("HR")
        card("Re-line the crusher", site)
        card("Sign the contract", other)
        val html = page(canManage = true, showBucketId = site)
        // The three GET openers on the board and the head's add-bucket button are all reads of this page: the
        // filter has to be in each, or the page that comes back is the full run of buckets.
        assertTrue(formRegion(html, Regex("<form[^>]*class=\"kcard-open\"")).contains("name=\"show\" value=\"$site\""),
            "opening a card from a narrowed board widened it")
        assertTrue(formRegion(html, Regex("<form[^>]*class=\"kcol-add\"")).contains("name=\"show\" value=\"$site\""),
            "the add line would file into a board the reader is not looking at")
        assertTrue(formRegion(html, Regex("<form[^>]*class=\"kcol-menu\"")).contains("name=\"show\" value=\"$site\""),
            "the column's menu would widen the board behind its popup")
        assertTrue(formRegion(html, Regex("<form[^>]*class=\"opener-form addbucket-form\""))
            .contains("name=\"show\" value=\"$site\""), "the head's add-bucket popup forgot the filter")
        // The card popup's own save names the narrowed board in the token the routes read back, the same way
        // the Assigned page's `assigned:<id>` does.
        assertTrue(formWithAction(html, "/app/assigned-tasks/save")
            .contains("<input type=\"hidden\" name=\"back\" value=\"other:$site\">"),
            "a card saved from a narrowed board would answer on the full one")
        // The column popup's own writes carry it too, so a refused rename comes back over the same group.
        val managed = page(canManage = true, bucket = BucketForm(bucketId = site), showBucketId = site)
        assertTrue(formWithAction(popup(managed), "/app/other-buckets/$site/rename").contains("name=\"show\" value=\"$site\""),
            "a rename would answer on the full board")
        assertTrue(formWithAction(popup(managed), "/app/other-buckets/$site/delete").contains("name=\"show\" value=\"$site\""),
            "a delete would answer on the full board")
        // And the create popup, opened from the head while narrowed.
        val created = page(canManage = true, bucket = BucketForm(open = true), showBucketId = site)
        assertTrue(formWithAction(created, "/app/other-buckets").contains("name=\"show\" value=\"$site\""),
            "a refused create would answer on the full board")
    }

    @Test
    fun `All buckets is the way back and the address bar follows it`() {
        // **Stage 97.** The toggle used to be the second way off a narrowed board, and its form carried the wide
        // flag so coming back did not close columns the reader had opened. The picker is the one way now, so this
        // pins that the way back is real: *All buckets* is an option, the board it answers with carries no filter
        // anywhere, and a filter form's own send rewrites the URL (`app.js`'s `refresh`), so the state a reader
        // chose is the state a reload gives them back.
        val site = bucket("Site works")
        bucket("Idle plant")
        card("Grade the haul road", site)
        val narrowed = page(canManage = true, showBucketId = site)
        assertTrue(
            narrowed.contains("<input type=\"hidden\" name=\"show\" value=\"$site\">"),
            "a narrowed board carries no filter for its own controls to ride",
        )
        val back = page(canManage = true)
        // The picker's own select is always called `show`; what must be absent on an unfiltered board is a
        // hidden `show` riding in one of its forms, which is how the filter travels.
        assertFalse(back.contains("<input type=\"hidden\" name=\"show\""), "an unfiltered board carries a filter")
        assertTrue(selectedOptionIn(back, "show", "0"), "the picker did not come back to All buckets")
        assertFalse(back.contains("allbuckets"), "coming back from a filter re-opened the retired wide view")
        assertTrue(pickerForm(back).contains("data-autosubmit"), "a bucket choice would wait for a second press")
        // A filter form names the live region rather than a target block, so it needs no `data-live-url`:
        // `refresh()` rewrites the address bar for every one of them. The toggle needed the attribute because it
        // was a block form; nothing here is one any more.
        assertFalse(pickerForm(back).contains("data-live-url"), "the picker asked for a second URL rewrite")
    }

    @Test
    fun `a shared-backlog bucket filter is a view token the routes read back`() {
        // The token machinery behind the picker. `other` is the full board, `other:<id>` the narrowed one; only
        // the second puts `show` on the page it answers with, and only on this page — the Assigned page's
        // `bucket` key and this one's `show` never stand in for each other.
        assertEquals("other:7", backForOtherBucket(7))
        assertEquals("other", backForOtherBucket(0))
        assertEquals(7, bucketOfOtherBack("other:7"))
        assertEquals(0, bucketOfOtherBack("other"))
        assertEquals(0, bucketOfOtherBack("other:not-a-number"))
        assertTrue(isOtherBack("other"))
        assertTrue(isOtherBack("other:7"))
        assertFalse(isOtherBack("otherwise"))
        assertFalse(isOtherBack(BACK_ASSIGNED))
        assertEquals("/app/other-tasks?show=7", taskRedirect("other:7", 0))
        assertTrue(taskRedirect("other:7", 0, edit = 5).contains("edit=5"))
        assertEquals("/app/other-tasks", taskRedirect("other", 0), "an unfiltered board grew a filter key")
        assertFalse(taskRedirect("other:7", 0).contains("allbuckets"), "a narrowed board was handed a wide view")
        // A posted token is normalised: a made-up id is the full board, a real one keeps its number.
        val made = Parameters.build { append("back", "other:7") }
        assertEquals("other:7", backOf(made), "a posted bucket filter was dropped")
        val junk = Parameters.build { append("back", "other:'; drop table tasks; --") }
        assertEquals("other", backOf(junk), "a hand-built back token was echoed back")
    }
}

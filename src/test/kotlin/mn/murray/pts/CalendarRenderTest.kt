package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.currentMonthKey
import mn.murray.pts.domain.monthKey
import mn.murray.pts.domain.shiftMonth
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.Authorization
import mn.murray.pts.security.PasswordHasher
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the Calendar page draws for the reader who opens it.
 *
 * The month plan is one bar per card, so the page has exactly one interesting decision: whose cards. It used to
 * follow the old task-view rule — management got the whole team's month, everybody else got their own. That made
 * it the last view that had not moved to the personal rule the Assigned board settled on in Stage 53 and the
 * project page confirmed in Stage 56, and it is why the sidebar count and the calendar could disagree about how
 * much work a person has. Now the calendar is *the reader's own month* for every role: management reads a
 * colleague's schedule from the Team sheet's drill-down or that colleague's project board, where the card is
 * actionable, not from a grid of bars.
 *
 * Pinned here: a manager's page holds their own bar and never a colleague's; the wording on the page is the same
 * sentence for both roles (the scope is no longer something a reader has to infer from their rights); a card with
 * nobody on it belongs to nobody's month; the list under the grid is the same slice the bars come from; and a
 * reader with nothing of their own is told so instead of being shown an empty team plan.
 *
 * Stage 65 added the second half of the page's job — *which* month — because the plan used to be hard-pinned to
 * the one the server clock happens to be in. Pinned along with it: the address decides the month, the month
 * controls carry resolved months rather than a step, the box that names any month shows the month on screen and
 * moves the plan in place, an unreadable month opens this month instead of echoing the word back, a bar that runs
 * in from the month before still says what it is, and the list under the grid is deliberately not cut to the
 * month (an undated card is real work that no month can hold).
 *
 * Stage 66 answered the two halves of "see all of their assigned tasks, neat but different from one another":
 * **which cards** — the month now comes from both boards the reader's work lives on, so a dated card sitting on
 * Other tasks is a bar like any project card (before this it was invisible, which is exactly the gap the ask
 * named) — and **how they tell each other apart** — a bar's hue is its group (its project, or the bucket a backlog
 * card stands in) rather than its priority, priority moved to a dot inside the bar, and a key under the strip
 * names the hues whenever the month holds more than one group. Both halves are layout, never rights: the widened
 * read stays personal, so a colleague's backlog card is still absent, and the palette is fixed over the reader's
 * whole workload so a project does not change colour as the reader steps through the year.
 */
class CalendarRenderTest {

    private lateinit var ds: HikariDataSource
    private val manager = UserSession("EMP0001")
    private val engineer = UserSession("EMP0002")

    /** Bars are drawn inside the month the page renders, so the fixtures are dated against it. */
    private val month = YearMonth.now()

    private fun day(n: Int) = month.atDay(n).toString()

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "calendar.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        // EMP0001 must be a *real* management account: the page's scope is the only thing under test, and a
        // reader who cannot see the team by rights would pass the whole file without the rule meaning anything.
        account("EMP0001", "Munkhtsetseg", "Manager")
        account("EMP0002", "Bat-Erdene", "Viewer")
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun account(employeeNumber: String, name: String, access: String) = UserRepository.insert(
        employeeNumber = employeeNumber, name = name, passwordHash = PasswordHasher.hash(employeeNumber),
        access = access, email = "", positionId = null, isSuperAdmin = false, mustChangePassword = false,
    )

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    /** The page as [who] sees it, on the month the address asked for. */
    private fun page(who: UserSession, month: String = currentMonthKey()): String =
        render { calendarContent(who, month) }

    /** The `value=` carried by each `name="month"` button, in the order the page draws them. */
    private fun monthButtons(html: String): List<String> =
        Regex("""<button[^>]*name="month"[^>]*>""").findAll(html).map { tag ->
            Regex("""value="([^"]+)"""").find(tag.value)
                ?.groupValues?.get(1)
                ?: error("a month button arrived with no month to name: ${tag.value}")
        }.toList()

    /** The opening `<input>` tag for one named control — `createHTML` keeps a tag's attributes on one line. */
    private fun inputNamed(html: String, name: String): String =
        Regex("""<input[^>]*name="$name"[^>]*>""").find(html)
            ?.value
            ?: error("the page has no box named $name")

    private fun formTag(html: String): String =
        Regex("""<form[^>]*>""").find(html)?.value ?: error("the month controls are not in a form")

    /** The heading a month is read under — the same sentence the renderer writes. */
    private fun headingOf(of: YearMonth): String =
        "Calendar - ${of.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${of.year}"

    /** Work on this board, owned by [assignee], running from [from] to [to] of the month being drawn. */
    private fun scheduled(
        title: String,
        assignee: String?,
        from: String,
        to: String,
    ): Int = TaskRepository.insert(
        kind = "ASSIGNED",
        title = title,
        projectId = null,
        due = to,
        priority = "Medium",
        assigneeEmpNo = assignee,
        status = "In progress",
        bucketId = null,
        started = from,
    )

    // --- the two boards a month is now read from (Stage 66) --------------------------------------

    /** A project, so a group has a name of its own to be coloured and keyed by. */
    private fun project(code: String, name: String): Int = ProjectRepository.insert(
        code = code, name = name, status = "In progress", progress = 0,
        managerEmpNo = "EMP0001", due = "", type = "Site projects",
    )

    /** Work filed under [projectId], owned by [assignee], running [from] to [to] at [priority]. */
    private fun onProject(
        title: String,
        projectId: Int,
        assignee: String?,
        from: String,
        to: String,
        priority: String = "Medium",
    ): Int = TaskRepository.insert(
        kind = "ASSIGNED",
        title = title,
        projectId = projectId,
        due = to,
        priority = priority,
        assigneeEmpNo = assignee,
        status = "In progress",
        bucketId = null,
        started = from,
    )

    /** One column of the Other tasks board. */
    private fun bucket(name: String): Int = BucketRepository.insert(name)

    /** Backlog work standing in [bucketId], owned by [assignee], running [from] to [to] at [priority]. */
    private fun backlog(
        title: String,
        bucketId: Int,
        assignee: String?,
        from: String,
        to: String,
        priority: String = "Medium",
    ): Int = TaskRepository.insert(
        kind = "OTHER",
        title = title,
        projectId = null,
        due = to,
        priority = priority,
        assigneeEmpNo = assignee,
        status = "In progress",
        bucketId = bucketId,
        started = from,
    )

    /**
     * Every bar fragment as (its card's title, the hue it wears), in the order the page prints them.
     *
     * The hue is an inline style rather than a class, so it has to be read off the tag itself; `createHTML` keeps
     * one tag's attributes together, which is what makes slicing at the first `>` safe.
     */
    private fun barHues(html: String): List<Pair<String, String>> =
        html.split("<div class=\"calendar-task-bar\"").drop(1).map { fragment ->
            val tag = fragment.substringBefore(">")
            val hue = Regex("""background-color:\s*([^;"\n]+)""").find(tag)?.groupValues?.get(1)?.trim()
                ?: error("a bar arrived with no hue: $tag")
            val title = Regex("""data-task-title="([^"]*)"""").find(tag)?.groupValues?.get(1)
                ?: error("a bar arrived with no title: $tag")
            title to hue
        }

    /** The hue one card's bar is drawn in, or null when the page did not draw that card as a bar at all. */
    private fun hueOf(html: String, title: String): String? =
        barHues(html).firstOrNull { it.first == title }?.second

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    @Test
    fun `the reader under test really is management`() {
        // The guard for the file: every "management sees only their own" assertion below is worthless if this
        // account is not a manager, because then the page would be filtered by rights rather than by the rule.
        assertTrue(Authorization.canManage(manager.employeeNumber), "EMP0001 is not a management account")
        assertFalse(Authorization.canManage(engineer.employeeNumber), "EMP0002 was made a manager by accident")
    }

    @Test
    fun `management's month holds their own bars and never a colleague's`() {
        scheduled("Grade the haul road", "EMP0001", day(3), day(5))
        scheduled("Service the crusher", "EMP0002", day(3), day(5))

        val asManager = page(manager)
        assertTrue(asManager.contains("Grade the haul road"), "the manager's own card is off their calendar")
        assertFalse(asManager.contains("Service the crusher"), "a colleague's card was drawn on management's month")
        // Three days of span for one card, and nothing for the other: the bars are the reader's own.
        assertEquals(3, occurrences(asManager, "calendar-task-bar"), "the month is not drawn from one person's cards")

        // The same rule the page has always applied to a non-manager, stated once for both of them.
        val asEngineer = page(engineer)
        assertTrue(asEngineer.contains("Service the crusher"), "the engineer's own card is missing")
        assertFalse(asEngineer.contains("Grade the haul road"), "a Viewer was shown management's card")
    }

    @Test
    fun `the page says the same thing to every reader`() {
        val asManager = page(manager)
        val asEngineer = page(engineer)
        // The scope line and the list heading are no longer conditional on the role, so the old team-wide
        // wording must not survive anywhere on either page.
        listOf(asManager, asEngineer).forEach { html ->
            assertTrue(html.contains("Your assigned tasks, with start dates and deadlines"),
                "the page did not say whose month this is")
            assertTrue(html.contains(">Your assigned tasks<"), "the list under the grid was titled for a team")
            assertFalse(html.contains("View all assigned tasks"), "a team-wide scope line is still on the page")
            assertFalse(html.contains("All Assigned Tasks Summary"), "a team-wide summary heading is still on the page")
        }
    }

    @Test
    fun `a card with nobody on it belongs to nobody's month`() {
        scheduled("Awaiting an owner", null, day(4), day(6))
        assertFalse(page(manager).contains("Awaiting an owner"),
            "an unassigned card was drawn on management's calendar")
        assertFalse(page(engineer).contains("Awaiting an owner"),
            "an unassigned card was drawn on a Viewer's calendar")
    }

    @Test
    fun `the list under the grid is the same slice the bars are drawn from`() {
        // A card with no dates is not a bar — but it is still the reader's work, so it belongs in the list, and
        // the two halves of the page must never disagree about whose cards they are.
        scheduled("Grade the haul road", "EMP0001", "", "")
        scheduled("Service the crusher", "EMP0002", "", "")

        val asManager = page(manager)
        assertEquals(0, occurrences(asManager, "calendar-task-bar"), "a card with no dates drew a bar")
        assertTrue(asManager.contains("Grade the haul road"), "the manager's undated card is missing from the list")
        assertFalse(asManager.contains("Service the crusher"), "the list reached outside the reader")
    }

    @Test
    fun `a reader with nothing of their own is told so`() {
        scheduled("Service the crusher", "EMP0002", day(3), day(5))
        val asManager = page(manager)
        assertTrue(asManager.contains("No assigned tasks yet."), "an empty personal month said nothing")
        assertEquals(0, occurrences(asManager, "calendar-task-bar"), "the month still held somebody's bars")
        // The grid itself is always drawn — an empty month is still a month, and the alternative is a page that
        // changes shape depending on how much work is on the reader.
        assertEquals(1, occurrences(asManager, "calendar-grid"), "the calendar grid went away")
    }

    // --- which month the plan is reading ----------------------------------------------------------

    @Test
    fun `the address decides the month the plan draws`() {
        val next = month.plusMonths(1)
        scheduled("Overhaul the conveyor belt", "EMP0001", next.atDay(4).toString(), next.atDay(6).toString())

        val atNext = page(manager, monthKey(next))
        assertTrue(atNext.contains(headingOf(next)), "the heading still names the month the reader did not ask for")
        assertTrue(atNext.contains("Overhaul the conveyor belt"), "the month asked for does not hold that month's cards")
        assertEquals(3, occurrences(atNext, "calendar-task-bar"), "the chosen month is not drawing its own bars")
        // Standing somewhere else in the year, `This month` is one click from being back where they started.
        assertTrue(atNext.contains("This month"), "a reader in another month has no way home")

        // The same card one month earlier: no bar, because nothing of it is dated here — and the page says so.
        val atThis = page(manager, currentMonthKey())
        assertEquals(0, occurrences(atThis, "calendar-task-bar"), "a bar leaked out of the month it is dated in")
        assertTrue(atThis.contains("Nothing assigned to you is dated in"), "an empty month said nothing about itself")
        assertFalse(atThis.contains("This month"), "the current month offered a trip to the current month")
    }

    @Test
    fun `the month controls name the months they mean, not a step`() {
        val shown = month.plusMonths(1)
        val steps = monthButtons(page(manager, monthKey(shown)))
        // ‹, ›, then `This month`: three buttons, three absolute months, in the order the page draws them. A
        // relative value would step twice on a reload, and the address is what gets bookmarked and shared.
        assertEquals(
            listOf(monthKey(month), monthKey(shown.plusMonths(1)), currentMonthKey()),
            steps,
            "the month controls do not carry the resolved neighbours",
        )
        // Year turning: the strip is still stating months, never an offset.
        val december = YearMonth.of(2026, 12)
        assertEquals(
            listOf("2026-11", "2027-01", currentMonthKey()),
            monthButtons(page(manager, monthKey(december))).take(2) + currentMonthKey(),
            "the year does not turn on the month controls",
        )
    }

    @Test
    fun `the box that names any month holds the month on screen`() {
        val shown = month.minusMonths(3)
        val html = page(manager, monthKey(shown))
        val box = inputNamed(html, "pick")
        assertTrue(box.contains("value=\"${monthKey(shown)}\""), "the box does not show the month being read: $box")
        assertTrue(box.contains("type=\"month\""), "the box is not the browser's own month control: $box")
        assertTrue(box.contains("data-autosubmit=\"true\""), "naming a month would not move the plan in place")

        val form = formTag(html)
        assertTrue(form.contains("action=\"/app/calendar\""), "the month form does not post to this page: $form")
        assertTrue(form.contains("method=\"get\""), "a month is a read, so it belongs on the address: $form")

        // One question, two controls: pressing a step also sends whatever the box holds, so the two answers are
        // asked under different names and the route believes the control that was acted on.
        assertEquals(3, occurrences(html, "name=\"month\""), "the step buttons are not the only `month` on the page")
        assertEquals(1, occurrences(html, "name=\"pick\""), "the month box is not answering under its own name")
    }

    @Test
    fun `a word that is not a month opens this month`() {
        val html = page(manager, "banana")
        assertTrue(html.contains("value=\"${currentMonthKey()}\""), "an unreadable month left the box with nothing to show")
        assertFalse(html.contains("banana"), "the rejected word was echoed back into the page")
        // And the steps point at this month's neighbours, not at a month the reader typed.
        assertEquals(
            listOf(shiftMonth(currentMonthKey(), -1), shiftMonth(currentMonthKey(), 1)),
            monthButtons(html).take(2),
            "a rejected month moved the controls off this month",
        )
    }

    @Test
    fun `a bar that runs in from the month before still says what it is`() {
        // Running from the 20th of last month to the 10th of next: the whole month on screen is one long bar, and
        // neither of its real ends is visible here.
        scheduled(
            "Rebuild the jaw crusher",
            "EMP0001",
            month.minusMonths(1).atDay(20).toString(),
            month.plusMonths(1).atDay(10).toString(),
        )
        val html = page(manager, monthKey(month))
        assertTrue(html.contains("Rebuild the jaw crusher"), "a card that runs in is not drawn at all")
        assertEquals(1, occurrences(html, "calendar-task-link"), "a bar entering the month arrived unlabelled")
        assertEquals(1, occurrences(html, "border-top-left-radius"), "the month did not open the bar")
        assertEquals(1, occurrences(html, "border-top-right-radius"), "the month did not close the bar")
    }

    @Test
    fun `the whole month view moves as one block`() {
        val html = page(manager, monthKey(month.plusMonths(1)))
        // One live region, holding the heading, the controls and the grid together: the strip has to be swapped
        // with the plan it changes, or its buttons keep pointing at the month they were first drawn for.
        assertEquals(1, occurrences(html, "data-live-region=\"true\""), "a month switch would not know what to swap")
        assertTrue(html.contains("id=\"calendarMonth\""), "the month view has no block for app.js to swap")
        assertTrue(
            html.indexOf("id=\"calendarMonth\"") < html.indexOf("calendar-grid"),
            "the grid is outside the block the month changes",
        )
    }

    @Test
    fun `the list under the grid is the whole workload, not the month`() {
        // The grid answers "what is happening this month"; the list answers "what is mine". Cutting the list to the
        // month would drop every undated card without saying so, which is a worse answer than the one it replaces.
        scheduled("Grade the haul road", "EMP0001", day(3), day(5))
        scheduled("Wait on the parts quote", "EMP0001", "", "")

        val atNext = page(manager, monthKey(month.plusMonths(1)))
        assertEquals(0, occurrences(atNext, "calendar-task-bar"), "a bar leaked into a month it is not dated in")
        assertTrue(atNext.contains(">Grade the haul road<"), "the list under the grid was cut to the month")
        assertTrue(atNext.contains(">Wait on the parts quote<"), "the list dropped the card that has no dates")
    }

    // --- every card the reader has, and a hue that says which work it is (Stage 66) ----------------

    @Test
    fun `a dated card on Other tasks is on the month too`() {
        // The gap this stage exists to close: a backlog card with real start and deadline dates used to be
        // invisible on the calendar, because the month only ever read the project board.
        val site = bucket("Site projects")
        backlog("Clean the workshop yard", site, "EMP0001", day(6), day(8))

        val html = page(manager)
        assertTrue(html.contains("Clean the workshop yard"), "the reader's backlog card never reached the calendar")
        assertEquals(3, occurrences(html, "calendar-task-bar"), "the backlog card is not drawn as a three-day bar")
        // The strip counts CARDS, not days — this one card is three days of bar. Before this stage the line read
        // "no cards dated here" for a reader whose whole month was backlog work.
        assertTrue(html.contains("1 card dated here"), "the month still counts only project cards")
    }

    @Test
    fun `widening the boards did not widen who they are read for`() {
        // The Stage 64 rule, restated against the new source: the Other-tasks slice is personal by construction,
        // so a colleague's backlog card and an unassigned one both stay off the reader's month.
        val site = bucket("Site projects")
        backlog("Clean the workshop yard", site, "EMP0002", day(6), day(8))
        backlog("Write the shutdown report", site, null, day(6), day(8))

        val html = page(manager)
        assertFalse(html.contains("Clean the workshop yard"), "a colleague's backlog card reached management's month")
        assertFalse(html.contains("Write the shutdown report"), "an unassigned backlog card was drawn for the reader")
        assertEquals(0, occurrences(html, "calendar-task-bar"), "the month drew bars that are nobody's")
    }

    @Test
    fun `an undated backlog card is listed but never barred`() {
        val site = bucket("Site projects")
        backlog("Chase the missing timesheet", site, "EMP0001", "", "")

        val html = page(manager)
        assertEquals(0, occurrences(html, "calendar-task-bar"), "a card with no dates drew a bar")
        assertTrue(html.contains(">Chase the missing timesheet<"), "the reader's undated backlog card is off the list")
    }

    @Test
    fun `one project is one hue and two projects are two`() {
        val widening = project("S-01", "Haul road widening")
        val crusher = project("M-02", "Crusher overhaul")
        onProject("Set out the alignment", widening, "EMP0001", day(3), day(5))
        onProject("Order the liners", crusher, "EMP0001", day(10), day(12))

        val html = page(manager)
        val first = hueOf(html, "Set out the alignment") ?: error("the first project drew no bar")
        val second = hueOf(html, "Order the liners") ?: error("the second project drew no bar")
        assertNotEquals(first, second, "two projects read as one undifferentiated group")
        // And one card is one object: every day of its span wears the same colour.
        assertEquals(3, barHues(html).count { it.second == first }, "a card's bar changed hue part-way along its span")
        assertEquals(1, barHues(html).filter { it.first == "Set out the alignment" }.map { it.second }.distinct().size,
            "a card's own hue is not stable across its days")
    }

    @Test
    fun `a backlog card wears its bucket's hue`() {
        val site = bucket("Site projects")
        val labor = bucket("Labor supply")
        backlog("Clean the workshop yard", site, "EMP0001", day(2), day(4))
        backlog("Top up the welding rods", site, "EMP0001", day(10), day(12))
        backlog("Cover the night shift", labor, "EMP0001", day(20), day(22))

        val html = page(manager)
        assertEquals(hueOf(html, "Clean the workshop yard"), hueOf(html, "Top up the welding rods"),
            "two cards in one bucket did not read as a family")
        assertNotEquals(hueOf(html, "Clean the workshop yard"), hueOf(html, "Cover the night shift"),
            "two buckets of the same board wore the same hue")
    }

    @Test
    fun `priority is a dot inside the bar, not the bar's colour`() {
        val crusher = project("M-02", "Crusher overhaul")
        onProject("Order the liners", crusher, "EMP0001", day(3), day(5), priority = "High")
        onProject("Fit the new screen", crusher, "EMP0001", day(10), day(12), priority = "Low")

        val html = page(manager)
        // Same project, so the same hue — the dots are what tells the two apart now.
        assertEquals(hueOf(html, "Order the liners"), hueOf(html, "Fit the new screen"),
            "priority still owns the bar's colour")
        assertEquals(1, occurrences(html, "title=\"High priority\""), "the high card arrived with no dot on its bar")
        assertEquals(1, occurrences(html, "title=\"Low priority\""), "the low card arrived with no dot on its bar")
        assertTrue(html.contains("cal-dot-high") && html.contains("cal-dot-low"), "the dots carry no class to style them")
        // The old priority palette is gone, not merely shadowed by the new one.
        assertFalse(html.contains("#d93025"), "a high-priority red is still painting a bar")
        assertFalse(html.contains("#e0a800"), "a medium-priority amber is still painting a bar")
    }

    @Test
    fun `the key names the groups only when the month holds more than one`() {
        val crusher = project("M-02", "Crusher overhaul")
        onProject("Fit the new screen", crusher, "EMP0001", day(4), day(6))
        val one = page(manager)
        assertEquals(0, occurrences(one, "cal-key-group"), "a single group was given a colour key all to itself")
        // The dots are new whatever the month holds, so the priority line is always there to explain them.
        assertTrue(one.contains("Priority:"), "the page never says what the dots mean")

        val site = bucket("Site projects")
        backlog("Clean the workshop yard", site, "EMP0001", day(10), day(12))
        val two = page(manager)
        assertEquals(2, occurrences(two, "cal-key-group"), "the month is not keying the groups it drew")
        assertTrue(two.contains(">Crusher overhaul<"), "the key does not name the project")
        assertTrue(two.contains(">Site projects<"), "the key does not name the bucket")
        // Backlog work is tagged, because a bucket's names are the same six words a project lane uses.
        val keyRegion = two.substringAfter("class=\"cal-key\"").substringBefore("calendar-grid")
        assertEquals(1, occurrences(keyRegion, "cal-tag"), "the bucket entry in the key is not marked as Other tasks")
        assertEquals(1, occurrences(keyRegion, ">Crusher overhaul<"), "the key names a group the month does not hold")
    }

    @Test
    fun `the hues do not move when the month does`() {
        val alpha = project("S-01", "Alpha plant")
        val beta = project("S-02", "Beta workshop")
        val gamma = project("S-03", "Gamma site")
        onProject("Grade the north ramp", alpha, "EMP0001", day(3), day(4))
        onProject("Swap the belt roller", beta, "EMP0001", day(5), day(6))
        // One card that is a bar in both months on purpose, so the two pages can be compared on a hue each draws.
        onProject("Reline the ball mill", gamma, "EMP0001", day(20), month.plusMonths(1).atDay(5).toString())

        val here = page(manager)
        val there = page(manager, monthKey(month.plusMonths(1)))
        assertEquals(3, barHues(here).map { it.second }.distinct().size,
            "one month is not drawing its three groups in three hues")
        assertEquals(hueOf(here, "Reline the ball mill"), hueOf(there, "Reline the ball mill"),
            "the palette was assigned per month, so a project changes colour as the reader steps through the year")
    }

    @Test
    fun `the list under the grid says where each card lives`() {
        val crusher = project("M-02", "Crusher overhaul")
        onProject("Fit the new screen", crusher, "EMP0001", day(3), day(5))
        val site = bucket("Site projects")
        backlog("Clean the workshop yard", site, "EMP0001", "", "")

        val html = page(manager)
        assertTrue(html.contains(">Where it lives<"),
            "the list still heads the column Project, which is a name a backlog card does not have")
        assertTrue(html.contains(">Crusher overhaul<"), "a project card is not named by its project in the list")
        assertTrue(html.contains(">Clean the workshop yard<"), "the undated backlog card left the list")
        // The page also says, in its own words, which cards the month reads and what the colours and dots mean.
        assertTrue(html.contains("From your projects and from Other tasks."),
            "the page does not say which boards the month is reading")
    }

    @Test
    fun `a backlog card tells the popup it belongs to no project`() {
        val site = bucket("Site projects")
        backlog("Clean the workshop yard", site, "EMP0001", day(6), day(8))
        val bar = page(manager).substringAfter("<div class=\"calendar-task-bar\"").substringBefore(">")
        assertTrue(bar.contains("data-task-project=\"Other tasks · Site projects\""),
            "the popup was handed a blank where the card's bucket belongs: $bar")
    }

    /**
     * The two rules that keep a coloured month inside its own panel, pinned where they live — in `app.css`.
     *
     * A bar is a flex row of dot + label + avatar, and the label is `white-space: nowrap`. Left on the table's
     * default auto layout, that nowrap label is a *minimum* content width, so one long card title widened its
     * whole column, the seven columns stopped being seven equal sevenths, and the month spilled past the right
     * edge of the card it sits in — which is exactly what a month of backlog titles does, because a bucket name
     * is not short. `table-layout: fixed` makes the columns equal whatever the labels say, and `min-width: 0` is
     * what lets the label give up its own width so the ellipsis can finally clip it.
     */
    @Test
    fun `the grid is seven equal columns and a long title clips instead of pushing them`() {
        val css = File("src/main/resources/static/css/app.css").readText()
        val grid = css.lineSequence().firstOrNull { it.startsWith(".calendar-grid {") }
        val link = css.lineSequence().firstOrNull { it.startsWith(".calendar-task-link {") }
        assertTrue(grid != null && grid.contains("table-layout: fixed"),
            "the month is back on the auto table layout, so one long card title widens its column: $grid")
        assertTrue(link != null && link.contains("min-width: 0"),
            "the bar's label cannot shrink, so it forces its column open instead of ellipsising: $link")
    }

    @Test
    fun `the month grid sits in the panel that scrolls it sideways`() {
        assertTablesScrollInTheirPanel(page(manager), "<table class=\"calendar-grid\">", 1)
    }
}

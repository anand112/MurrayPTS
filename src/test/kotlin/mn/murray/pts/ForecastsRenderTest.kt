package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.Parameters
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.FULLY_INVOICED
import mn.murray.pts.domain.ForecastRecord
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.NOT_INVOICED
import mn.murray.pts.domain.PARTIALLY_INVOICED
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.currentMonthKey
import mn.murray.pts.domain.typeChipClass
import mn.murray.pts.domain.typeLabel
import mn.murray.pts.repository.ForecastLedgerRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.ForecastDraft
import mn.murray.pts.service.ForecastFilter
import mn.murray.pts.service.ForecastResult
import mn.murray.pts.service.ForecastService
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
 * What the Forecasts sheet (Stage 43) puts on the page, and what it does not.
 *
 * The grid in the picture is the specification: eleven columns in a stated order, a row coloured by
 * the invoice state management chose, two derived figures printed as text because nobody types them,
 * and a footer that adds the rows the reader can see. Those are markup promises, so they are pinned
 * here rather than in the service's tests.
 *
 * The other half of what this suite guards is the update contract, because that is what makes the
 * page a sheet instead of a form: the toolbar sits **outside** the live region so a refresh cannot
 * take the caret out of a half-typed search, every row owns a form that names **only** its own row
 * plus the totals line under its table, and a Viewer is served the same figures with nothing on the
 * page that could post them.
 *
 * Stage 45 moved the sheet onto the Projects board's grouping: a row of tabs above one panel per
 * category, each panel heading its own running table and — when that category has finished work — its
 * own completed table and totals line. That makes the per-page assertions above per panel, so the
 * suite now reads a panel's own subtree (`panel(html, type)`) wherever the promise belongs to one
 * category, and it pins the two new things the grouping brought: a tab's pill says what clicking it
 * leaves, and a row's save repaints the totals under **its** panel rather than the first one on the
 * page.
 *
 * `createHTML()` puts every tag on its own line, so the assertions here are made on single tags
 * (`<th …>text</th>`, `<span …>text</span>`) or on one tag's attribute run, never across two tags.
 */
class ForecastsRenderTest {

    private lateinit var ds: HikariDataSource

    /**
     * A month that is not the current one, so both the ‹ / › controls and the way back to today are
     * on the page. February 2023 rather than a date a year away, because a suite should not depend
     * on when it happens to run.
     */
    private val month = "2023-02"

    /** The focus attribute, spelled whichever way kotlinx.html writes it out. */
    private val autoFocus: Regex = Regex("autofocus", RegexOption.IGNORE_CASE)

    private var haulRoad = 0
    private var linerSupply = 0
    private var excavator = 0

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "forecasts-page.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))

        // One running project with money on this month and last, one that has never been forecast, and
        // one finished job, so both halves of the sheet have a row to prove the columns against.
        haulRoad = project("S-01", "Haul road widening", "In progress", 12_500_000.0, "Site projects", "Oyu Tolgoi LLC")
        linerSupply = project("M-01", "Crusher liner supply", "Not started", 4_000_000.0, "Fabrication/Manufacturing", "")
        excavator = project("R-01", "Excavator overhaul", "Completed", 8_000_000.0, "Repair/Maintenance/other services", "Power Mining LLC")
        put(haulRoad, "2023-01", 3_000_000.0, 3_000_000.0, FULLY_INVOICED)
        put(haulRoad, month, 4_000_000.0, 1_000_000.0, PARTIALLY_INVOICED)
        put(excavator, "2023-01", 5_000_000.0, 5_000_000.0, FULLY_INVOICED)
        // A finished job's last line has no plan on it and an invoice under it, which is what its sheet reads.
        put(excavator, month, 0.0, 3_000_000.0, FULLY_INVOICED)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun project(
        code: String,
        name: String,
        status: String,
        revenue: Double,
        type: String,
        client: String,
    ): Int = ProjectRepository.insert(
        code = code, name = name, status = status, progress = 0, managerEmpNo = "EMP0001",
        due = "", type = type, client = client, revenue = revenue,
    )

    private fun put(pid: Int, atMonth: String, forecast: Double, invoiced: Double, status: String) {
        ForecastLedgerRepository.put(ForecastRecord(pid, atMonth, forecast, invoiced, "", status))
    }

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    private fun sheet(
        filter: ForecastFilter = ForecastFilter(),
        canManage: Boolean = true,
        refused: ForecastResult? = null,
    ): String = render {
        forecastsContent(
            month = month,
            board = ForecastService.board(month, filter),
            filter = filter,
            canManage = canManage,
            refused = refused,
        )
    }

    /** The opening tag whose attributes carry [id] — the whole attribute run of one element, on one line. */
    private fun tag(html: String, id: String): String =
        Regex("<[^>]*id=\"$id\"[^>]*>").find(html)?.value ?: error("no element with id=$id")

    // --- the columns -------------------------------------------------------------------------------

    @Test
    fun `the running sheet prints the eleven columns in the order the picture reads them`() {
        val html = sheet()
        // Two categories hold running rows here, so the sheet prints the eleven columns twice — once per
        // panel — and nothing about the grid itself changed when it was cut up by category.
        assertEquals(2, occurrences(html, "class=\"grid fc-table\""), "a running panel printed no table or two")
        val headers = listOf(
            "f-type" to "Type", "f-code" to "Project #", "f-desc" to "Description", "f-client" to "Client",
            "f-rev" to "Revenue, MNT", "f-prev" to "Invoiced in previous months",
            "f-forecast" to "Forecast (current month)", "f-remain" to "Remainant to next months",
            "f-imonth" to "Invoiced month", "f-invoiced" to "Invoiced amount, MNT", "f-status" to "Invoice status",
        )
        // The company's own spelling of the eighth column is kept: this sheet is read against a paper one.
        listOf("Site projects", "Fabrication/Manufacturing").forEach { type ->
            val p = panel(html, type)
            headers.forEach { (klass, text) ->
                assertEquals(1, occurrences(p, "<th class=\"$klass\">$text</th>"), "$type: $klass expected one header saying \"$text\"")
            }
            // `table-layout: fixed` takes its widths from the colgroup, so the columns that are sized are
            // the columns that are headed — same names, same order, in every panel.
            val cols = Regex("<col class=\"(f-[a-z]+)\"").findAll(p).map { it.groupValues[1] }.toList()
            assertEquals(headers.map { it.first }, cols, "$type: the widths are not over its headers")
        }
        // One row per running project, and every cell of it carries its column's class.
        assertEquals(2, occurrences(html, "<td class=\"f-type\""), "expected one running cell per column per row")
        assertEquals(2, occurrences(html, "<td class=\"f-status\""))
        // Three rows on the page in all: the two running ones and the completed row below them.
        assertEquals(3, occurrences(html, "<tr class=\"fc-row"), "the sheet's rows changed count")
    }

    @Test
    fun `the completed sheet loses the two forward columns and keeps what the job totalled`() {
        val html = sheet()
        val done = listOf("d-type", "d-code", "d-desc", "d-client", "d-rev", "d-total", "d-imonth", "d-invoiced")
        assertEquals(done, Regex("<col class=\"(d-[a-z]+)\"").findAll(html).map { it.groupValues[1] }.toList())
        assertEquals(1, occurrences(html, "<th class=\"d-total\">Total invoiced, MNT</th>"))
        // The two figures a finished job has no use for are not on its table at all — not headers, not cells.
        listOf("d-forecast", "d-remain").forEach {
            assertFalse(html.contains("\"$it\""), "the completed sheet still carries $it")
        }
        assertEquals(1, occurrences(html, "Completed projects — invoiced"), "the completed half doubled its heading")
        assertTrue(html.contains("<span class=\"money fc-covered\">8,000,000</span>"), "a job invoiced for its whole value is not marked")
        // The identity closes on the running rows, printed as derived text beside the two typed cells:
        // 12,500,000 revenue - 3,000,000 invoiced in January - 4,000,000 planned here = 5,500,000 left.
        assertTrue(html.contains("<span class=\"money dim\">3,000,000</span>"), "January's invoice is not this month's previous")
        assertTrue(html.contains("<span class=\"money dim\">5,500,000</span>"), "remaining is not revenue minus previous minus forecast")
    }

    // --- colour, and what a blank row is not ---------------------------------------------------------

    @Test
    fun `a row's colour is its invoice state and the key above the grid names all three`() {
        val html = sheet()
        assertEquals(1, occurrences(html, "<tr class=\"fc-row fc-partial\" id=\"fc-row-$haulRoad\">"), "the row ignored its stated state")
        assertEquals(1, occurrences(html, "<tr class=\"fc-row fc-full\" id=\"fc-row-$excavator\">"), "the completed row lost its state colour")
        // The line nobody has written on carries no colour at all: the key says yellow means a forecast
        // was entered, and an untouched row that arrived yellow would claim a plan nobody wrote.
        assertEquals(1, occurrences(html, "<tr class=\"fc-row\" id=\"fc-row-$linerSupply\">"), "a blank row was tinted")
        listOf(
            "fc-entered" to "Forecast entered",
            "fc-partial" to "Partially invoiced",
            "fc-full" to "Fully invoiced",
        ).forEach { (klass, text) ->
            assertEquals(1, occurrences(html, "<span class=\"fc-key $klass\">$text</span>"), "the key lost $text")
        }
        assertEquals(1, occurrences(html, "class=\"fc-legend\""))
    }

    @Test
    fun `a blank line opens as empty boxes for a writer and as zero for a reader`() {
        val html = sheet()
        // A prefilled `0` would be a figure the reader has to delete before typing their own, and the
        // sheet's blank cells are meant to read as blank.
        assertTrue(tag(html, "fc-forecast-$linerSupply").contains("value=\"\""), "a blank row's plan box was prefilled")
        assertTrue(tag(html, "fc-invoiced-$linerSupply").contains("value=\"\""), "a blank row's invoice box was prefilled")
        // Its derived neighbours are still real figures, and the identity still closes.
        assertTrue(html.contains("<span class=\"money dim\">4,000,000</span>"), "a blank row's remaining is not its revenue")

        // On the read-only sheet the same cell counts as zero, because the totals below the table add it.
        val read = sheet(canManage = false)
        assertTrue(read.contains("<span class=\"money\">0</span>"), "a blank line is not zero to a Viewer")
        assertTrue(read.contains("<td class=\"f-client\">—</td>"), "a project with no client should print a dash")
    }

    // --- the writers --------------------------------------------------------------------------------

    @Test
    fun `a manager's row holds its fields and the one form they all post to`() {
        val html = sheet()
        val form = tag(html, "fc-line-$haulRoad")
        assertTrue(form.contains("action=\"/app/forecasts/save\""), "the row form does not post to the save route")
        assertTrue(form.contains("method=\"post\""))
        // It swaps its own row and the totals line under its own panel — nothing else on the page moves,
        // and with a panel per category that means the Site projects line, not the first one on the sheet.
        assertTrue(form.contains("data-live-target=\"#fc-row-$haulRoad\""), "the save would replace the whole grid")
        assertTrue(form.contains("data-live-extra=\"#fc-totals-S\""), "the totals line is not a companion of the swap")
        // Every writer names the row's form with `form=` and saves when it is left: that is what lets a
        // single row hold three independent inputs with no button among them.
        listOf("fc-forecast" to "forecast", "fc-invoiced" to "invoiced").forEach { (prefix, name) ->
            val field = tag(html, "$prefix-$haulRoad")
            assertTrue(field.contains("name=\"$name\""), "$prefix has no name")
            assertTrue(field.contains("form=\"fc-line-$haulRoad\""), "$prefix is not bound to the row's form")
            assertTrue(field.contains("data-autosubmit"), "$prefix does not save when it is left")
        }
        val picker = tag(html, "fc-status-$haulRoad")
        assertTrue(picker.startsWith("<select"), "the invoice state is not a picker")
        assertTrue(picker.contains("form=\"fc-line-$haulRoad\""))
        assertTrue(picker.contains("data-autosubmit"))
        assertTrue(Regex("value=\"$PARTIALLY_INVOICED\"[^>]*selected").containsMatchIn(html), "the stored state came up unselected")
        // The values in the boxes are the figures the sheet prints, grouped the way the column reads.
        assertTrue(tag(html, "fc-forecast-$haulRoad").contains("value=\"4,000,000\""), "the plan box is not the stored figure")
        assertTrue(tag(html, "fc-invoiced-$haulRoad").contains("value=\"1,000,000\""))
        assertTrue(tag(html, "fc-imonth-$haulRoad").contains("value=\"2023-02\""), "the invoice cell lost its month")
        // The completed row writes through the same rule and points at the totals line under its own table,
        // and the other panel's rows point at their own — three panels here, three ids, so a save in one
        // category cannot repaint the money under another.
        assertTrue(tag(html, "fc-line-$excavator").contains("data-live-extra=\"#fc-totals-done-R\""))
        assertTrue(tag(html, "fc-line-$linerSupply").contains("data-live-extra=\"#fc-totals-M\""), "two panels share one totals line")
        assertEquals(1, occurrences(html, "id=\"fc-totals-S\""))
        assertEquals(1, occurrences(html, "id=\"fc-totals-M\""))
        assertEquals(1, occurrences(html, "id=\"fc-totals-done-R\""))
        // Ten writers on this sheet: the toolbar's one picker, and four fields on each running row — plan,
        // invoice month, invoice amount, invoice state — of which the completed table prints only the two
        // money ones, because a finished job has no plan and no state column of its own.
        assertEquals(1 + 4 + 4 + 2, occurrences(html, "data-autosubmit"), "the sheet's writers changed shape")
    }

    @Test
    fun `the row form carries the sheet it was drawn on so its totals cannot jump`() {
        val html = sheet(filter = ForecastFilter(text = "widening", type = "Site projects", status = "In progress"))
        // The toolbar's three keys ride along as `board_*`: a row that posted without them would come back
        // as the whole grid, and its totals line would change to every project's money mid-typing.
        assertTrue(html.contains("<input type=\"hidden\" name=\"project\" value=\"$haulRoad\">"))
        assertTrue(html.contains("<input type=\"hidden\" name=\"month\" value=\"2023-02\">"))
        assertTrue(html.contains("<input type=\"hidden\" name=\"board_q\" value=\"widening\">"), "the search is not preserved")
        assertTrue(html.contains("<input type=\"hidden\" name=\"board_type\" value=\"Site projects\">"))
        assertTrue(html.contains("<input type=\"hidden\" name=\"board_status\" value=\"In progress\">"))
        assertTrue(html.contains("class=\"fc-line-form\""))
        // `month` is the line being written, never the month the invoice cell names: the two are different
        // questions, and only the first decides where the money is counted.
        assertEquals(4_000_000.0, ForecastLedgerRepository.line(haulRoad, month)!!.forecast)
    }

    @Test
    fun `a viewer is served the same figures with nothing on the page that can write them`() {
        val html = sheet(canManage = false)
        assertFalse(html.contains("/app/forecasts/save"), "a Viewer was served the save route")
        assertFalse(html.contains("fc-line-form"), "a Viewer was served a row's form")
        assertFalse(html.contains("board_q"), "a Viewer was served a row's hidden fields")
        // The writers are gone entirely rather than greyed out, so the page does not offer what it will
        // refuse; the cells they replaced are the same figures as text.
        listOf("name=\"forecast\"", "name=\"invoiced\"", "name=\"invoicedmonth\"", "class=\"fc-num\"", "fc-status-field")
            .forEach { assertFalse(html.contains(it), "a Viewer was served the $it writer") }
        assertTrue(html.contains("<span class=\"money\">4,000,000</span>"), "the plan disappeared with its box")
        assertTrue(html.contains("<span class=\"fc-state\">$PARTIALLY_INVOICED</span>"), "the state is not printed as text")
        assertTrue(html.contains("<span class=\"dim\">Feb 2023</span>"), "the invoice month is not printed as text")
        // What a Viewer keeps is the whole read: both halves, every panel's totals line, and the colours.
        assertTrue(html.contains("Haul road widening"))
        assertTrue(html.contains("Excavator overhaul"))
        assertTrue(html.contains("id=\"fc-totals-S\""))
        assertTrue(html.contains("id=\"fc-totals-M\""))
        assertTrue(html.contains("id=\"fc-totals-done-R\""))
        assertEquals(1, occurrences(html, "<tr class=\"fc-row fc-partial\""), "the row lost its state colour for a reader")
        // Only the toolbar's picker is left wired to submit, and the one live-filtering search box.
        assertEquals(1, occurrences(html, "data-autosubmit"))
        assertEquals(1, occurrences(html, "data-livefilter"))
    }

    // --- the month switch -----------------------------------------------------------------------------

    @Test
    fun `the month controls belong to the toolbar from inside the region and carry resolved neighbours`() {
        val html = sheet()
        assertTrue(html.contains("<span class=\"fc-month\">Feb 2023</span>"), "the sheet does not say which month it reads")
        fun step(value: String): String = Regex("<button[^>]*value=\"$value\"[^>]*>").find(html)?.value
            ?: error("no month control pointing at $value")
        // Absolute months, not "one step back": reloading the address bar has to land where the button
        // says it will, and a relative control would step twice from a stale page.
        listOf("2023-01", "2023-03").forEach {
            val button = step(it)
            assertTrue(button.contains("form=\"fcFilter\""), "$it does not belong to the toolbar's form")
            assertTrue(button.contains("name=\"month\""), "$it posts the wrong key")
        }
        // Off the current month, the page also offers the way back to it.
        assertTrue(html.contains("This month"), "no way back to the current month")
        assertTrue(step(currentMonthKey()).contains("form=\"fcFilter\""))
        // The count the strip prints is the sheet's own, per half.
        assertTrue(html.contains("<span class=\"task-meta\"> · 2 running · 1 completed</span>"), "the strip miscounts its rows")

        // And on the current month there is nowhere to jump back to, so the control is not offered.
        val now = render {
            forecastsContent(
                month = currentMonthKey(),
                board = ForecastService.board(currentMonthKey()),
                filter = ForecastFilter(),
                canManage = true,
            )
        }
        assertFalse(now.contains("This month"), "the sheet offered a jump to the month it is already on")
    }

    /**
     * The markup from the `<div` at [openIndex] to the `</div>` that closes it — the element itself
     * included, and everything inside it.
     *
     * `createHTML()` gives no tree to walk, so this is the one structural read the suite makes: an
     * element's own subtree, found by counting the `<div`s that open and close after it.
     */
    private fun blockFrom(html: String, openIndex: Int): String {
        if (openIndex < 0) return ""
        var depth = 0
        var cursor = openIndex
        while (true) {
            val nextOpen = html.indexOf("<div", cursor + 1)
            val nextClose = html.indexOf("</div>", cursor + 1)
            if (nextClose < 0) return ""
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen
            } else {
                if (depth == 0) return html.substring(openIndex, nextClose)
                depth--
                cursor = nextClose
            }
        }
    }

    /**
     * The live region: what a refresh replaces. Everything outside it survives a swap, which is where
     * the search box, the picker and the colour key have to stay.
     */
    private fun liveRegion(html: String): String =
        blockFrom(html, Regex("<div[^>]*data-live-region[^>]*>").find(html)?.range?.first ?: -1)

    /**
     * The toolbar: its `<form>` tag to the `</form>` that closes it, so a claim about the page's pickers
     * is about the picker the reader sets filters with — not the invoice-state pickers inside each row,
     * which are writers and share the `<select>` element.
     */
    private fun toolbar(html: String): String {
        val start = Regex("<form[^>]*id=\"fcFilter\"").find(html)?.range?.first ?: return ""
        val end = html.indexOf("</form>", start)
        return if (end < 0) "" else html.substring(start, end)
    }

    /**
     * One category's panel, from its `<div class="card-panel">` to its own `</div>`.
     *
     * While the sheet was two tables, a whole-page assertion said everything worth saying. With a panel
     * per category the promises are per panel — the columns it heads, the totals line under it, the rows
     * inside it — so a test has to be able to say *inside the Site projects panel* rather than
     * somewhere on the page.
     */
    private fun panel(html: String, type: String): String {
        val chip = "<span class=\"chip ${typeChipClass(type)}\">$type</span>"
        val head = html.indexOf(chip).takeIf { it >= 0 } ?: error("no panel headed $type")
        val open = html.lastIndexOf("<div class=\"card-panel\"", head)
            .takeIf { it >= 0 } ?: error("the $type chip is not inside a panel")
        return blockFrom(html, open)
    }

    @Test
    fun `a refresh replaces the month strip and the grid but leaves the toolbar alone`() {
        val html = sheet()
        val region = liveRegion(html)
        assertTrue(region.isNotEmpty(), "the sheet has no live region")
        assertTrue(region.contains("id=\"fc-board\""), "the region is not the block app.js names")
        // Everything a month change, a filter change or a row save alters is inside it, or the page
        // shows a stale grid — which is the one thing that makes this layout count as broken.
        listOf("class=\"filter-tabs\"", "class=\"fc-strip\"", "class=\"grid fc-table\"", "id=\"fc-totals-S\"", "id=\"fc-row-$haulRoad\"")
            .forEach { assertTrue(region.contains(it), "$it is outside the region and would go stale") }
        // The tabs are in the region for the same reason the month controls are: their pills count what the
        // search leaves, so a filter change that repainted the panels but not the pills would leave the
        // page advertising a category it no longer shows.
        assertTrue(region.contains("class=\"ftab active\""), "no tab is marked as the open one")
        // The box being typed in is not: replacing it mid-word puts the caret back at the start.
        assertFalse(region.contains("data-livefilter"), "the search box would be swapped while typing in it")
        assertFalse(region.contains("board-toolbar"), "the toolbar is inside the region")
        assertFalse(region.contains("class=\"fc-legend\""), "the colour key is inside the region")
        // The month controls are the exception the region exists for: they live inside it because a month
        // change has to re-point them, and they reach the toolbar's form through `form=`.
        assertTrue(region.contains("name=\"month\""), "the month controls would keep their old target")
    }

    @Test
    fun `the toolbar is one get form onto the page and keeps its own value`() {
        val html = sheet(filter = ForecastFilter(text = "widening"))
        assertTrue(Regex("<form action=\"/app/forecasts\"[^>]*method=\"get\"").containsMatchIn(html), "the toolbar is not a GET form")
        assertTrue(html.contains("id=\"fcFilter\""))
        assertTrue(html.contains("value=\"widening\""), "the box came back empty after the search")
        assertTrue(autoFocus.containsMatchIn(html), "a filtered sheet does not take the caret back")
        // The Type picker is gone, and the tab row above the panels is what answers that question now —
        // with a count as well as a choice. What the toolbar still carries is the open tab, as a hidden
        // field: a search or a month step from inside a category has to stay inside it.
        val bar = toolbar(html)
        assertFalse(bar.contains("All types"), "the type picker came back")
        assertEquals(1, occurrences(bar, "<select"), "the toolbar is not one picker (status) deep")
        assertEquals(1, occurrences(bar, "All statuses"))
        assertTrue(toolbar(sheet(filter = ForecastFilter(type = "Site projects")))
            .contains("<input type=\"hidden\" name=\"type\" value=\"Site projects\">"), "the open tab is not carried")
        assertFalse(toolbar(sheet()).contains("name=\"type\""), "a sheet on All posts a category it is not on")
        // A filter that is on has to read as one and be one click from being off.
        assertTrue(html.contains("class=\"board-reset\""), "an active filter offers no Clear link")
        assertTrue(html.contains("href=\"/app/forecasts?month=2023-02\""), "Clear keeps the filter it claims to drop")
        assertFalse(sheet().contains("board-reset"), "a Clear link appeared with nothing to clear")
        assertFalse(autoFocus.containsMatchIn(sheet()), "an untouched sheet stole the focus for no reason")
    }

    /** Each tab of the strip as (its label, the count printed on its pill), in the order it renders. */
    private fun tabs(html: String): List<Pair<String, Int>> =
        Regex("<a class=\"ftab[^\"]*\"[^>]*>([^<]*) <span class=\"pill\">(\\d+)</span>").findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()

    // --- the category tabs and the panels under them (Stage 45) -----------------------------------------

    @Test
    fun `the tab row is the board's control and every pill counts what clicking it leaves`() {
        val html = sheet()
        val printed = tabs(html)
        // All first, then the nine categories with the letters that lead their project numbers — spelled
        // exactly the way the Projects board spells them, because a reader moves between the two pages and
        // a filter named one thing here and another there reads as two different questions.
        assertEquals(listOf("All") + PROJECT_TYPES.map { typeLabel(it) }, printed.map { it.first },
            "the tab row is not the board's")
        // A pill that advertised six and opened on two is the lie the board refuses, so each one is
        // checked against the sheet that tab's own address answers rather than against the page it is
        // printed on — with no tab chosen here, that is the only way the two can disagree.
        listOf("Fabrication/Manufacturing", "Repair/Maintenance/other services", "Site projects").forEach { type ->
            val rowsOnThatTab = ForecastService.board(month, ForecastFilter(type = type)).let { it.open.size + it.completed.size }
            assertEquals(rowsOnThatTab, printed.first { it.first == typeLabel(type) }.second, "$type's pill is not what clicking it leaves")
            assertEquals(rowsOnThatTab, occurrences(panel(html, type), "<tr class=\"fc-row"), "$type's pill is not its panel's rows")
        }
        assertEquals(3, printed.drop(1).sumOf { it.second }, "a project sat under two tabs or none")
        assertEquals(printed.first().second, printed.drop(1).sumOf { it.second }, "\"All\" is not the count the tabs add to")
    }

    @Test
    fun `the panels print in the category list's order and an empty category gets no panel`() {
        val html = sheet()
        val heads = Regex("<span class=\"chip (t-[a-z])\">([^<]*)</span>").findAll(html).map { it.groupValues[2] }.toList()
        // [PROJECT_TYPES] is the order both pages list categories in, so the sheet and the board line down
        // the screen the same way instead of in whatever order the ledger returned.
        assertEquals(
            listOf("Fabrication/Manufacturing", "Repair/Maintenance/other services", "Site projects"),
            heads, "the panels are not in the category list's order",
        )
        // Six of the nine categories have nothing here, and none of them stands over an empty table — the
        // rule the board's panels and the task lanes both follow.
        assertEquals(3, occurrences(html, "<div class=\"card-panel\""), "an empty category got a panel")
        listOf("Site projects", "Repair/Maintenance/other services").forEach { type ->
            assertTrue(panel(html, type).contains("class=\"pill\">1<"), "$type's panel lost its own count")
        }
        assertTrue(panel(html, "Site projects").contains("class=\"chip t-s\""), "the panel's chip is not the board's colour")
    }

    @Test
    fun `a tab that is open prints its own panel and the rest of the sheet keeps reading it`() {
        val html = sheet(filter = ForecastFilter(type = "Site projects"))
        assertEquals(1, occurrences(html, "<div class=\"card-panel\""), "a tab opened on more than its category")
        assertTrue(html.contains("class=\"chip t-s\""), "the open category lost its panel head")
        assertFalse(html.contains("Crusher liner supply"), "a tab left another category's rows on the page")
        // The strip says which tab is open, and the tab's address carries the category it stands for.
        // `kotlinx.html` writes `href` ahead of `class`, so the opening tag is matched as a whole.
        val openTab = Regex("<a [^>]*class=\"ftab active\"[^>]*>([^<]*)").find(html)
            ?: error("no tab is marked as the open one")
        assertEquals(typeLabel("Site projects") + " ", openTab.groupValues[1], "the wrong tab came up open")
        assertTrue(openTab.value.contains("type=Site+projects"), "the open tab's link dropped the category: ${openTab.value}")
        // Its totals line is its own, and the row inside it names that line rather than the sheet's.
        assertTrue(html.contains("id=\"fc-totals-S\""))
        assertFalse(html.contains("id=\"fc-totals-M\""), "a panel that is not printed still footer-added money")
        assertTrue(tag(html, "fc-line-$haulRoad").contains("data-live-extra=\"#fc-totals-S\""))
        // The month controls live inside the swapped region and submit the toolbar's form, so the open tab
        // has to be a field of that form or stepping a month would drop the reader out of the category.
        assertTrue(toolbar(html).contains("value=\"Site projects\""), "the month strip would leave the tab behind")
    }

    @Test
    fun `a category the sheet has no panel for is read as no category at all`() {
        // The tabs are links, so `?type=` is part of an address a reader can edit or receive from someone;
        // a name the sheet does not know must not cut the page to nothing and still print the word.
        val query = Parameters.build {
            append("type", "Not a category")
            append("q", "widening")
        }
        val filter = forecastFilterOf(query)
        assertEquals("", filter.type, "a hand-typed category was taken as a filter")
        assertEquals("widening", filter.text, "the rest of the address went with it")
        val html = sheet(filter = filter)
        assertEquals(1, occurrences(html, "<div class=\"card-panel\""), "an unknown category blanked the sheet")
        assertTrue(html.contains("class=\"ftab active\""), "no tab is open on a sheet that read its category as none")
        // A row's hidden `board_type` is markup rather than a tab the page offered, and it gets the same
        // answer: the save cannot be painted onto a panel that has no rows and therefore no totals line.
        val form = Parameters.build { append("board_type", "Not a category") }
        assertEquals("", forecastFilterOfForm(form).type, "a posted category was taken as a filter")
        assertEquals("Site projects", forecastFilterOfForm(
            Parameters.build { append("board_type", "Site projects") }).type, "a real category was dropped")
    }

    // --- totals, refusals and the empty states ---------------------------------------------------------

    @Test
    fun `each panel's footer adds its own rows and the completed half gets its own line`() {
        val html = sheet()
        val site = panel(html, "Site projects")
        val fab = panel(html, "Fabrication/Manufacturing")
        val repair = panel(html, "Repair/Maintenance/other services")
        assertTrue(site.contains("<tr class=\"fc-totals\" id=\"fc-totals-S\">"), "the running table's totals line moved")
        assertTrue(site.contains(">Forecast total:<"))
        // 4,000,000 planned and 1,000,000 invoiced on the one running row this category has.
        assertTrue(site.contains("<span class=\"money\">4,000,000</span>"), "the plan total is not the rows' sum")
        assertTrue(site.contains("<span class=\"money\">1,000,000</span>"), "the invoice total is not the rows' sum")
        assertTrue(site.contains(">Actual invoiced total:<"))
        // The category whose only row is untouched footers its own two zeroes rather than the money above
        // it on the page: a panel that added another category's plan would state a figure no row of its
        // own is part of, and that is exactly what the sheet looked like before it was cut up by type.
        assertTrue(fab.contains("<tr class=\"fc-totals\" id=\"fc-totals-M\">"))
        assertEquals(2, occurrences(fab, "<span class=\"money\">0</span>"), "the empty category's totals are not its own rows")
        // The finished job is read by everything it ever totalled, and only inside its own panel.
        assertTrue(repair.contains("<tr class=\"fc-totals\" id=\"fc-totals-done-R\">"))
        assertTrue(repair.contains(">Invoiced against these projects:<"))

        // A filtered sheet totals the rows it printed, which is what a row form's `board_*` fields protect.
        val one = sheet(filter = ForecastFilter(text = "Crusher"))
        assertTrue(one.contains("<span class=\"money dim\">4,000,000</span>"), "the remaining column ignored the filter")
        assertTrue(one.contains(">Forecast total:<"))
        assertFalse(one.contains("12,500,000"), "a filtered sheet printed a project that is not on it")
        assertEquals(1, occurrences(one, "class=\"grid fc-table\""), "the search left another category's table standing")
    }

    @Test
    fun `a refused row keeps the words that were typed and prints its message under its own cell`() {
        val refused = ForecastService.save(
            ForecastDraft.of(
                mapOf(
                    "project" to haulRoad.toString(),
                    "forecast" to "about a billion",
                    "invoiced" to "9,000,000",
                    "invoicedmonth" to month,
                    "status" to PARTIALLY_INVOICED,
                )
            ),
            month,
            canManage = true,
        )
        assertFalse(refused.saved, "the bad amount should have been refused")
        val html = sheet(refused = refused)
        // One message on the whole page, and it belongs under the cell that caused it.
        assertEquals(1, occurrences(html, "class=\"field-err\""), "the refusal is not this row's alone")
        assertTrue(html.contains("Enter the amount as a number"), "the refusal does not say what a cell accepts")
        assertTrue(tag(html, "fc-forecast-$haulRoad").contains("value=\"about a billion\""), "the typed words were replaced by the stored figure")
        // The rest of the refused row is what was submitted, so the reader is looking at the line they left.
        assertTrue(tag(html, "fc-invoiced-$haulRoad").contains("value=\"9,000,000\""), "the other cell was reset under their hands")
        // Nothing was written, and every other row still comes from the ledger.
        assertEquals(4_000_000.0, ForecastLedgerRepository.line(haulRoad, month)!!.forecast, "a refused save stored the line anyway")
        assertEquals(1, occurrences(html, "about a billion"), "the refusal leaked into another row")
        assertTrue(html.contains("value=\"3,000,000\""), "the completed row lost its own figure")
        assertTrue(html.contains("<span class=\"money\">4,000,000</span>"), "the totals moved off the stored ledger")
    }

    @Test
    fun `an empty sheet says which of the two reasons it is empty`() {
        val missed = sheet(filter = ForecastFilter(text = "nothing like this"))
        assertTrue(missed.contains("Nothing here matches"), "the page blamed the ledger for the search")
        assertTrue(missed.contains("Show every project"), "no way out of the filter")
        assertTrue(missed.contains("class=\"row-link\""), "the way out is not a link")
        assertFalse(missed.contains("class=\"grid fc-table\""), "an empty board printed a table")
        // The strip and the tab row stay: the reader still has to know which month is the one with nothing
        // in it, and which category they are looking at an empty view of.
        assertTrue(missed.contains("class=\"fc-strip\""))
        assertTrue(missed.contains("class=\"filter-tabs\""))
        assertEquals(PROJECT_TYPES.size, occurrences(missed, "<a class=\"ftab\""), "a category the search emptied disappeared from the tabs")

        // Nothing filtered out, but nothing to forecast either.
        listOf(haulRoad, linerSupply, excavator).forEach { ProjectRepository.delete(it) }
        val blank = sheet()
        assertTrue(blank.contains("No projects yet"), "the page said the filter was to blame")
        assertTrue(blank.contains("href=\"/app/projects\""), "the empty sheet offers no way to fill it")
        assertTrue(blank.contains("class=\"fc-strip\""), "the empty page forgot which month it was reading")
        assertFalse(blank.contains("Completed projects"), "a heading was printed for no completed projects")
        assertFalse(blank.contains("fc-totals"), "a totals line was printed for no rows")
    }

    @Test
    fun `a board of finished projects alone prints no running sheet`() {
        listOf(haulRoad, linerSupply).forEach { ProjectRepository.delete(it) }
        val html = sheet()
        assertFalse(html.contains("class=\"grid fc-table\""), "an empty running sheet was printed over no rows")
        assertFalse(html.contains(">Forecast total:<"), "a running footer survived without running rows")
        assertTrue(html.contains("class=\"grid fc-done-table\""), "the completed half disappeared with the running rows")
        assertTrue(html.contains("id=\"fc-totals-done-R\""), "the completed half lost its own totals line")
    }

    @Test
    fun `a board of running projects alone prints no completed heading`() {
        ProjectRepository.delete(excavator)
        val html = sheet()
        assertFalse(html.contains("Completed projects"), "an empty completed sheet printed its heading")
        assertFalse(html.contains("fc-totals-done"))
        assertTrue(html.contains("class=\"grid fc-table\""), "the running sheet went with the completed rows")
        assertTrue(html.contains("id=\"fc-totals-S\""))
        assertTrue(html.contains("id=\"fc-totals-M\""))
        // Two rows left, both writable, and the strip counts one half as empty rather than hiding it.
        assertTrue(html.contains("<span class=\"task-meta\"> · 2 running · 0 completed</span>"), "the strip miscounts after a delete")
    }

    @Test
    fun `the legacy hour rows are gone from the sheet and the ledger is the only thing it reads`() {
        // The old `forecasts` table is left in place for the importer's sake (the user's call), but nothing
        // on this page is keyed on it: a line only appears when the monthly ledger holds one.
        ForecastLedgerRepository.put(ForecastRecord(linerSupply, "2023-03", 700_000.0, 0.0, "", NOT_INVOICED))
        assertFalse(sheet().contains("700,000"), "the sheet read a month it is not showing")
        val march = render {
            forecastsContent(
                month = "2023-03",
                board = ForecastService.board("2023-03"),
                filter = ForecastFilter(),
                canManage = true,
            )
        }
        assertTrue(march.contains("value=\"700,000\""), "March's own line did not reach its sheet")
        assertTrue(march.contains("<span class=\"fc-month\">Mar 2023</span>"))
    }
}

package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.FULLY_INVOICED
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.NOT_INVOICED
import mn.murray.pts.domain.PARTIALLY_INVOICED
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.currentMonthKey
import mn.murray.pts.repository.ForecastLedgerRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * The rules behind the Forecasts sheet (Stage 43), against a real SQLite file carrying the real
 * Flyway schema through V8 — the same harness [ProjectServiceTest] uses, because the arithmetic
 * this sheet is made of is only worth testing where the ledger is actually stored.
 *
 * The identity the whole page rests on is
 *
 *     revenue = invoiced in previous months + this month's forecast + remaining to next months
 *
 * and these cases are arranged around it: what counts as a *previous* month, which month a write
 * is keyed on, what a cell is allowed to refuse, and what happens to a line that has been emptied
 * out. Each refusal is asserted by field, because the sheet prints the sentence under its own cell
 * and nothing else.
 */
class ForecastServiceTest {

    private lateinit var ds: HikariDataSource

    /** A fixed "today" so the month fallbacks are about the rule, not about the date the suite ran. */
    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "forecasts.db").absolutePath}"
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

    /** A project is the only thing on the sheet that is not a ledger line, so every case starts with one. */
    private fun project(
        code: String,
        name: String = "Haul road widening",
        status: String = "Ongoing",
        revenue: Double = 12_500_000.0,
        type: String = "Site projects",
        client: String = "Oyu Tolgoi LLC",
        manager: String = "EMP0001",
    ): Int = ProjectRepository.insert(
        code = code, name = name, status = status, progress = 0, managerEmpNo = manager,
        due = "", type = type, client = client, revenue = revenue,
    )

    private fun draft(
        pid: Int,
        forecast: String = "",
        invoiced: String = "",
        invoiceMonth: String = "",
        status: String = NOT_INVOICED,
    ) = ForecastDraft(
        projectId = pid, forecastRaw = forecast, invoicedRaw = invoiced,
        invoicedMonth = invoiceMonth, status = status,
    )

    /** Write a line the way the grid would: as management, on the month named. */
    private fun write(pid: Int, month: String, forecast: String, invoiced: String, status: String): ForecastResult =
        ForecastService.save(draft(pid, forecast, invoiced, "", status), month, canManage = true)

    private fun rowOf(month: String, pid: Int, filter: ForecastFilter = ForecastFilter()): ForecastLine {
        val board = ForecastService.board(month, filter)
        return (board.open + board.completed).firstOrNull { it.project.id == pid }
            ?: error("project $pid is not on the $month sheet: ${board.open.map { it.project.id }} / ${board.completed.map { it.project.id }}")
    }

    // --- which month the page is reading ------------------------------------------------------------

    @Test
    fun `the sheet reads the month it is named and the current one otherwise`() {
        assertEquals("2026-10", ForecastService.monthOr(null, today))
        assertEquals("2025-03", ForecastService.monthOr("2025-03", today))
        assertEquals("2025-12", ForecastService.monthOr("  2025-12  ", today), "the toolbar's value is trimmed")
        // A single-digit month keeps its zero: '2027-1' is not a key the ledger can be summed against.
        assertEquals("2027-01", ForecastService.monthOr(null, LocalDate.of(2027, 1, 9)))
        // Anything that is not a month leaves the page where it was rather than somewhere it guessed.
        listOf("", "  ", "banana", "2026-13", "2026-10-05", "Oct 2026").forEach { bad ->
            assertEquals("2026-10", ForecastService.monthOr(bad, today), "?month=$bad should fall back")
        }
    }

    @Test
    fun `the month controls carry the resolved neighbour, not a step`() {
        assertEquals("2026-09", ForecastService.neighbourMonth("2026-10", -1))
        assertEquals("2026-11", ForecastService.neighbourMonth("2026-10", 1))
        // The two cases a relative control gets wrong: the year turning, and the year turning back.
        assertEquals("2027-01", ForecastService.neighbourMonth("2026-12", 1))
        assertEquals("2026-12", ForecastService.neighbourMonth("2027-01", -1))
        assertEquals("banana", ForecastService.neighbourMonth("banana", 1))
    }

    // --- the arithmetic -------------------------------------------------------------------------

    @Test
    fun `previous months are the earlier lines only, and never the month being read`() {
        val pid = project("S-01")
        write(pid, "2026-08", "1,000,000", "1,000,000", FULLY_INVOICED)
        write(pid, "2026-09", "2,000,000", "2,000,000", FULLY_INVOICED)
        write(pid, "2026-10", "4,000,000", "400,000", PARTIALLY_INVOICED)
        // A line in a month that has not happened yet: it is real money in the ledger, and it must not
        // be counted as "previous" by a sheet reading October.
        write(pid, "2026-11", "1,000,000", "5,000,000", FULLY_INVOICED)

        val october = rowOf("2026-10", pid)
        assertEquals(3_000_000.0, october.previous, "August and September only")
        assertEquals(4_000_000.0, october.record.forecast)
        assertEquals(400_000.0, october.record.invoiced)
        // The whole ledger, for the completed sheet's one figure.
        assertEquals(8_400_000.0, october.totalInvoiced)

        // Read a different month, and the same rows mean different things — the column is derived, not stored.
        val november = rowOf("2026-11", pid)
        assertEquals(3_400_000.0, november.previous, "October's invoice belongs in November's previous")
        // ... and the identity closes on both rows.
        listOf(october, november).forEach {
            assertEquals(
                it.project.revenue, it.previous + it.record.forecast + it.remaining, 0.001,
                "the row's four figures do not add up to the project's revenue",
            )
        }
    }

    @Test
    fun `remaining is what the identity leaves over, even below zero`() {
        val pid = project("S-01", revenue = 10_000_000.0)
        write(pid, "2026-09", "2,000,000", "2,000,000", FULLY_INVOICED)
        write(pid, "2026-10", "4,000,000", "", NOT_INVOICED)
        assertEquals(4_000_000.0, rowOf("2026-10", pid).remaining)

        // An over-forecast has spent the room the contract does not have. Clamping this to 0 would hide
        // the mistake on the one page whose job is to notice it, so the sheet prints the minus sign.
        write(pid, "2026-10", "20,000,000", "", NOT_INVOICED)
        val over = rowOf("2026-10", pid)
        assertEquals(-12_000_000.0, over.remaining, "an over-forecast must show as negative")
        assertTrue(over.remaining < 0.0)
    }

    @Test
    fun `a project with no line is on the sheet as a blank row and carries no colour`() {
        val pid = project("S-01")
        val line = rowOf("2026-10", pid)
        assertEquals(0.0, line.record.forecast)
        assertEquals(0.0, line.record.invoiced)
        assertEquals(NOT_INVOICED, line.record.status)
        assertEquals("2026-10", line.record.month, "a synthesised line is still keyed on the month being read")
        assertTrue(line.isBlank)
        // The key above the grid says pale yellow means a forecast has been entered; an untouched row that
        // arrived yellow would be the page claiming a plan nobody wrote.
        assertEquals("", line.tint)
        assertNull(ForecastLedgerRepository.line(pid, "2026-10"), "a blank row must not have been written")
    }

    @Test
    fun `the row's colour states the invoice label and not a figure`() {
        val pid = project("S-01")
        write(pid, "2026-10", "4,000,000", "400,000", PARTIALLY_INVOICED)
        assertEquals("fc-partial", rowOf("2026-10", pid).tint)
        write(pid, "2026-10", "4,000,000", "4,000,000", FULLY_INVOICED)
        assertEquals("fc-full", rowOf("2026-10", pid).tint)
        // A plan with no invoice yet is the yellow of the picture; and an invoice with no plan is still
        // coloured, because the label is what management stated and the page follows it.
        write(pid, "2026-10", "4,000,000", "", NOT_INVOICED)
        assertEquals("fc-entered", rowOf("2026-10", pid).tint)
        write(pid, "2026-10", "", "1,000,000", NOT_INVOICED)
        assertEquals("fc-entered", rowOf("2026-10", pid).tint)
    }

    // --- the two halves, the filter and the totals --------------------------------------------------

    @Test
    fun `running projects and finished ones are separate halves of one sheet`() {
        val ongoing = project("S-01", name = "Haul road widening", status = "Ongoing")
        val parked = project("S-02", name = "Weir repair", status = "Not started", revenue = 1_000_000.0)
        val done = project("R-01", name = "Excavator overhaul", status = "Completed", revenue = 8_000_000.0,
            type = "Repair/Maintenance/other services")

        val board = ForecastService.board("2026-10")
        assertEquals(listOf(ongoing, parked), board.open.map { it.project.id }, "both running rows, in id order")
        assertEquals(listOf(done), board.completed.map { it.project.id })
        // A finished job has no forward plan to read, so the sheet that lists it counts what it totalled.
        write(done, "2026-06", "5,000,000", "5,000,000", FULLY_INVOICED)
        write(done, "2026-10", "", "3,000,000", FULLY_INVOICED)
        assertEquals(8_000_000.0, rowOf("2026-10", done).totalInvoiced)
        assertFalse(board.isEmpty)
    }

    @Test
    fun `the search and the two pickers narrow both halves and the totals with them`() {
        val site = project("S-01", name = "Haul road widening", revenue = 12_000.0, client = "Oyu Tolgoi LLC")
        val fab = project("M-01", name = "Crusher liner supply", revenue = 4_000.0,
            type = "Fabrication/Manufacturing", client = "Power Mining LLC", manager = "EMP0002")
        val done = project("S-02", name = "Old stockpile survey", status = "Completed", revenue = 8_000.0)
        write(site, "2026-10", "5,000", "1,000", PARTIALLY_INVOICED)
        write(fab, "2026-10", "3,000", "2,000", PARTIALLY_INVOICED)
        write(done, "2026-10", "8,000", "8,000", FULLY_INVOICED)

        // The same four fields the Projects board searches, so a project found there is found here.
        assertTrue(ForecastFilter(text = "haul").matches(ForecastService.board("2026-10").open.first().project))
        assertEquals(listOf(site), ForecastService.board("2026-10", ForecastFilter(text = "widening")).open.map { it.project.id })
        assertEquals(listOf(site), ForecastService.board("2026-10", ForecastFilter(text = "TOLGOI")).open.map { it.project.id }, "the search is case-independent")
        assertEquals(listOf(fab), ForecastService.board("2026-10", ForecastFilter(text = "power")).open.map { it.project.id })
        assertEquals(listOf(site), ForecastService.board("2026-10", ForecastFilter(text = "munkhtsetseg")).open.map { it.project.id })
        assertEquals(listOf(site, done), ForecastService.board("2026-10", ForecastFilter(type = "Site projects")).let { it.open + it.completed }.map { it.project.id })
        assertEquals(listOf(done), ForecastService.board("2026-10", ForecastFilter(status = "Completed")).completed.map { it.project.id })
        assertTrue(ForecastService.board("2026-10", ForecastFilter(text = "nothing like this")).isEmpty)

        // Totals are the printed rows' sums: a filtered sheet that answered with the catalogue's money
        // would disagree with the rows above the line, which is why a row's save carries the filter.
        val all = ForecastService.board("2026-10")
        assertEquals(8_000.0, all.forecastTotal)
        assertEquals(3_000.0, all.invoicedTotal, "the completed row's invoice is not the running total")
        assertEquals(8_000.0, all.completedInvoicedTotal)
        val one = ForecastService.board("2026-10", ForecastFilter(text = "widening"))
        assertEquals(5_000.0, one.forecastTotal, "the totals ignored the filter")
        assertEquals(1_000.0, one.invoicedTotal)
    }

    // --- the category split (Stage 45) ----------------------------------------------------------------

    /** Three projects in three categories, so a panel per category has something to be a panel about. */
    private fun threeCategories(): Triple<Int, Int, Int> = Triple(
        project("S-01", name = "Haul road widening", revenue = 12_000.0, client = "Oyu Tolgoi LLC"),
        project("M-01", name = "Crusher liner supply", revenue = 4_000.0,
            type = "Fabrication/Manufacturing", client = "Power Mining LLC", manager = "EMP0002"),
        project("R-01", name = "Excavator overhaul", status = "Completed", revenue = 8_000.0,
            type = "Repair/Maintenance/other services", client = "Power Mining LLC"),
    )

    @Test
    fun `the sheet prints one panel per category, in the projects board's order, and none for an empty one`() {
        val (site, fab, done) = threeCategories()
        val board = ForecastService.board("2026-10")

        // [PROJECT_TYPES] is the order both pages list categories in, so the two screens line down the
        // page the same way; the ledger's id order would put them in a different sequence every month.
        assertEquals(
            listOf("Fabrication/Manufacturing", "Repair/Maintenance/other services", "Site projects"),
            board.groups.map { it.type },
            "the panels are not in the category list's order",
        )
        assertEquals(3, board.groups.size, "a category with no rows got a panel")
        assertTrue(board.groups.all { !it.isEmpty }, "an empty panel reached the page")
        assertEquals(listOf(fab), board.groups[0].open.map { it.project.id })
        assertEquals(listOf(done), board.groups[1].completed.map { it.project.id })
        assertEquals(listOf(site), board.groups[2].open.map { it.project.id })

        // Running and finished rows share their category's panel, so a job's money is read beside the
        // work it came out of rather than at the bottom of a sheet about something else.
        val mixed = project("S-02", name = "Old stockpile survey", status = "Completed", revenue = 8_000.0)
        val after = ForecastService.board("2026-10")
        val sitePanel = after.groups.first { it.type == "Site projects" }
        assertEquals(listOf(site), sitePanel.open.map { it.project.id })
        assertEquals(listOf(mixed), sitePanel.completed.map { it.project.id })
        assertEquals(2, sitePanel.count, "a panel counts its own rows, in both tables")
        // Together the panels are the sheet: every row the filter left is in exactly one of them.
        assertEquals(
            (after.open + after.completed).map { it.project.id }.sorted(),
            after.groups.flatMap { (it.open + it.completed).map { line -> line.project.id } }.sorted(),
            "a row is on the sheet but under no panel",
        )
    }

    @Test
    fun `each tab counts what clicking it would leave, not what the open tab is showing`() {
        val (site, fab, done) = threeCategories()
        write(site, "2026-10", "5,000", "1,000", PARTIALLY_INVOICED)

        val board = ForecastService.board("2026-10")
        assertEquals(PROJECT_TYPES, board.tabs.map { it.type }, "the tab row is not the category list")
        assertEquals(listOf(1, 1, 1), PROJECT_TYPES.mapNotNull { type ->
            board.tabs.first { it.type == type }.count.takeIf { it > 0 }
        }, "three categories, one project each")
        assertEquals(3, board.allCount, "\"All\" is the same projects, not a ninth category")

        // With a word typed, every pill reads the search but not the tab: a tab advertising four and
        // opening on two is the lie the Projects board refuses to tell, and it is the reason the pills
        // live inside the swapped region.
        val filtered = ForecastService.board("2026-10", ForecastFilter(text = "widening"))
        assertEquals(1, filtered.allCount, "the search did not narrow the pills")
        assertEquals(1, filtered.tabs.first { it.type == "Site projects" }.count)
        assertEquals(0, filtered.tabs.first { it.type == "Fabrication/Manufacturing" }.count)
        // A tab that a search emptied still prints, at zero: a category the company recognises is not
        // invented away by an unlucky word, and hiding it would make the row jump as the reader typed.
        assertEquals(PROJECT_TYPES, filtered.tabs.map { it.type })
        // The split between running and finished is what the panel's two tables read, so a completed job
        // cannot end up counted as work in progress.
        val repair = board.tabs.first { it.type == "Repair/Maintenance/other services" }
        assertEquals(0, repair.running)
        assertEquals(1, repair.finished)
        assertEquals(3, board.tabs.sumOf { it.count }, "every project is under exactly one tab")
        // And a tab that is open does not stop the other pills answering the same question.
        val onTab = ForecastService.board("2026-10", ForecastFilter(type = "Site projects"))
        assertEquals(3, onTab.allCount, "opening a tab changed what the others advertise")
        assertEquals(1, onTab.tabs.first { it.type == "Fabrication/Manufacturing" }.count)
        assertEquals(1, onTab.groups.size, "a sheet on one tab printed the other categories")
        assertEquals(listOf(site), onTab.groups.first().open.map { it.project.id })
    }

    @Test
    fun `a panel's totals are its own rows' money, so a row save moves one line and not the sheet`() {
        val (site, fab, done) = threeCategories()
        write(site, "2026-10", "5,000", "1,000", PARTIALLY_INVOICED)
        write(fab, "2026-10", "3,000", "2,000", PARTIALLY_INVOICED)
        write(done, "2026-06", "5,000", "5,000", FULLY_INVOICED)
        write(done, "2026-10", "", "3,000", FULLY_INVOICED)

        val board = ForecastService.board("2026-10")
        val byType = board.groups.associateBy { it.type }
        assertEquals(5_000.0, byType.getValue("Site projects").forecastTotal)
        assertEquals(1_000.0, byType.getValue("Site projects").invoicedTotal)
        assertEquals(3_000.0, byType.getValue("Fabrication/Manufacturing").forecastTotal)
        // The finished job is read by everything it ever totalled, and only inside its own panel.
        assertEquals(8_000.0, byType.getValue("Repair/Maintenance/other services").completedInvoicedTotal)
        assertEquals(0.0, byType.getValue("Repair/Maintenance/other services").forecastTotal)
        // The panels add up to the sheet the strip and the row forms speak about: same rows, same figures.
        assertEquals(board.forecastTotal, board.groups.sumOf { it.forecastTotal })
        assertEquals(board.invoicedTotal, board.groups.sumOf { it.invoicedTotal })
        assertEquals(board.completedInvoicedTotal, board.groups.sumOf { it.completedInvoicedTotal })
    }

    // --- who may write, and what a write means --------------------------------------------------------

    @Test
    fun `only management may write a line, and a refusal writes nothing`() {
        val pid = project("S-01")
        val refused = ForecastService.save(draft(pid, "4,000,000"), "2026-10", canManage = false)
        assertFalse(refused.saved)
        assertNotNull(refused.error(ForecastField.PERMISSION), "the refusal is not the permission one: ${refused.errors}")
        assertNull(ForecastLedgerRepository.line(pid, "2026-10"), "a refused line was stored anyway")
    }

    @Test
    fun `a line for a project that is no longer on the list is refused under that field`() {
        val result = ForecastService.save(draft(999_999, "1,000"), "2026-10", canManage = true)
        assertFalse(result.saved)
        assertNotNull(result.error(ForecastField.PROJECT))
        assertTrue(ForecastService.board("2026-10").isEmpty, "an orphan line reached the sheet")
    }

    @Test
    fun `amounts take the shapes the sheet is typed in and refuse the rest under their own cell`() {
        val pid = project("S-01")
        listOf("8600000", "8,600,000", "8 600 000", "8 600,000.00", " 8600000.00 ").forEach { typed ->
            val r = ForecastService.save(draft(pid, typed, "0"), "2026-10", canManage = true)
            assertTrue(r.saved, "$typed should be accepted: ${r.errors}")
            assertEquals(8_600_000.0, ForecastLedgerRepository.line(pid, "2026-10")!!.forecast, typed)
        }
        // Blank is not a mistake here: the grid opens on empty cells and has to be leftable.
        ForecastService.save(draft(pid, "", ""), "2026-10", canManage = true)
        assertNull(ForecastLedgerRepository.line(pid, "2026-10"), "two blanks should clear the line, not store zeroes")

        val negative = ForecastService.save(draft(pid, "-5", "1,000"), "2026-10", canManage = true)
        assertFalse(negative.saved)
        assertTrue(negative.error(ForecastField.FORECAST)!!.contains("zero"), negative.errors.toString())
        assertNull(ForecastLedgerRepository.line(pid, "2026-10"), "a refused save overwrote the line")

        val text = ForecastService.save(draft(pid, "about a billion", ""), "2026-10", canManage = true)
        assertTrue(text.error(ForecastField.FORECAST)!!.contains("number"), text.errors.toString())

        // Both cells wrong is both cells told: a sheet that reported one at a time would make the reader
        // fix, resubmit, and find the second mistake only on the next round.
        val both = ForecastService.save(draft(pid, "-1", "x"), "2026-10", canManage = true)
        assertEquals(setOf(ForecastField.FORECAST, ForecastField.INVOICED), both.errors.keys)

        // A figure that parses but is not a number anybody invoices with is its own refusal, not a typo.
        val huge = ForecastService.save(draft(pid, "1e999", ""), "2026-10", canManage = true)
        assertNotNull(huge.error(ForecastField.FORECAST))
        assertFalse(huge.error(ForecastField.FORECAST)!!.contains("number, like"), "a typo message for a non-typo")
    }

    @Test
    fun `the invoice state has to be one the picker offers`() {
        val pid = project("S-01")
        val bad = ForecastService.save(draft(pid, "1,000", status = "Almost invoiced"), "2026-10", canManage = true)
        assertNotNull(bad.error(ForecastField.STATUS), "an invented state was accepted: ${bad.errors}")
        // Leaving the state alone is a choice, not a failure — it means the plain state.
        val blank = ForecastService.save(draft(pid, "1,000", status = ""), "2026-10", canManage = true)
        assertTrue(blank.saved, "${blank.errors}")
        assertEquals(NOT_INVOICED, ForecastLedgerRepository.line(pid, "2026-10")!!.status)
    }

    @Test
    fun `a line is keyed on the month the page reads, never the month the invoice cell names`() {
        val pid = project("S-01")
        val result = ForecastService.save(
            draft(pid, "4,000,000", "1,000,000", invoiceMonth = "2026-11", status = PARTIALLY_INVOICED),
            month = "2026-10", canManage = true,
        )
        assertTrue(result.saved, "${result.errors}")
        val line = ForecastLedgerRepository.line(pid, "2026-10")!!
        assertEquals(4_000_000.0, line.forecast)
        assertEquals(1_000_000.0, line.invoiced)
        // The cell describes when the invoice was raised; it does not move the money.
        assertEquals("2026-11", line.invoicedMonth)
        assertNull(ForecastLedgerRepository.line(pid, "2026-11"), "the invoice month moved the money to another line")
        // Which is why November's "previous" counts that million as October's, not as its own.
        val november = rowOf("2026-11", pid)
        assertEquals(1_000_000.0, november.previous)
        assertEquals(0.0, november.record.forecast, "November has no plan of its own yet")

        // A month that is not a month is refused rather than stored as free text.
        val malformed = ForecastService.save(
            draft(pid, "4,000,000", "1,000,000", invoiceMonth = "10/2026", status = PARTIALLY_INVOICED),
            month = "2026-10", canManage = true,
        )
        assertNotNull(malformed.error(ForecastField.INVOICED_MONTH))
        assertEquals("2026-11", ForecastLedgerRepository.line(pid, "2026-10")!!.invoicedMonth, "a refused cell overwrote the line")
    }

    @Test
    fun `an invoice month of this line's own month is stored as blank and reads back as itself`() {
        val pid = project("S-01")
        ForecastService.save(draft(pid, "2,000", "1,000", invoiceMonth = "2026-10", status = PARTIALLY_INVOICED),
            "2026-10", canManage = true)
        val line = ForecastLedgerRepository.line(pid, "2026-10")!!
        assertEquals("", line.invoicedMonth, "the line's own month is not a difference worth storing")
        assertEquals("2026-10", line.shownInvoiceMonth, "the grid still has one month to print in that cell")
        // Leaving the cell empty says the same thing, and must not look different on the way back.
        ForecastService.save(draft(pid, "2,000", "1,000", invoiceMonth = "", status = PARTIALLY_INVOICED),
            "2026-10", canManage = true)
        assertEquals("", ForecastLedgerRepository.line(pid, "2026-10")!!.invoicedMonth)
    }

    @Test
    fun `saving the same month twice rewrites one line`() {
        val pid = project("S-01")
        write(pid, "2026-10", "4,000,000", "1,000,000", PARTIALLY_INVOICED)
        write(pid, "2026-10", "5,000,000", "5,000,000", FULLY_INVOICED)
        val lines = ForecastLedgerRepository.forProject(pid)
        assertEquals(1, lines.size, "the second save inserted a second October")
        assertEquals(5_000_000.0, lines.first().forecast)
        assertEquals(FULLY_INVOICED, lines.first().status)
    }

    @Test
    fun `a line emptied out is cleared, but a stated invoice is never nothing`() {
        val pid = project("S-01")
        write(pid, "2026-10", "4,000,000", "1,000,000", PARTIALLY_INVOICED)
        assertNotNull(ForecastLedgerRepository.line(pid, "2026-10"))
        write(pid, "2026-10", "", "", NOT_INVOICED)
        assertNull(ForecastLedgerRepository.line(pid, "2026-10"), "the cleared line stayed on the ledger")
        assertEquals(0, ForecastLedgerRepository.forProject(pid).size)
        assertTrue(rowOf("2026-10", pid).isBlank, "the sheet still shows the old money")

        // Zeroes that carry a claim are a statement, not a blank: clearing them would lose an invoice
        // somebody said exists.
        write(pid, "2026-10", "0", "0", FULLY_INVOICED)
        assertNotNull(ForecastLedgerRepository.line(pid, "2026-10"), "a stated state was discarded with the amounts")
        assertEquals("fc-full", rowOf("2026-10", pid).tint)
    }

    @Test
    fun `a save with no usable month lands on the current one`() {
        val pid = project("S-01")
        val month = currentMonthKey()
        assertTrue(ForecastService.save(draft(pid, "1,000", "500", status = PARTIALLY_INVOICED), "banana", canManage = true).saved)
        assertNotNull(ForecastLedgerRepository.line(pid, month), "the line went somewhere other than this month")
    }

    @Test
    fun `a saved line moves the next month's previous the moment it is stored`() {
        // The promise the two columns make to each other: type October's invoice and November's sheet
        // is already wrong if it does not include it. Nothing here is cached, so it cannot be.
        val pid = project("S-01")
        write(pid, "2026-09", "2,000,000", "2,000,000", FULLY_INVOICED)
        assertEquals(2_000_000.0, rowOf("2026-10", pid).previous)
        write(pid, "2026-10", "3,000,000", "3,000,000", FULLY_INVOICED)
        assertEquals(5_000_000.0, rowOf("2026-11", pid).previous)
        assertEquals(2_000_000.0, rowOf("2026-10", pid).previous, "October's own invoice is not its previous")
    }

    // --- what the route hands the service ------------------------------------------------------------

    @Test
    fun `the row form is read as the browser sends it`() {
        val pid = project("S-01")
        val fromForm = ForecastDraft.of(
            mapOf(
                "project" to " $pid ",
                "forecast" to " 8,600,000 ",
                "invoiced" to null,
                "invoicedmonth" to "2026-09",
                "status" to " $PARTIALLY_INVOICED ",
            )
        )
        assertEquals(pid, fromForm.projectId)
        assertEquals("8,600,000", fromForm.forecastRaw, "the raw text is what a refusal has to hand back")
        assertEquals("", fromForm.invoicedRaw, "an absent cell is blank, not the word null")
        assertEquals("2026-09", fromForm.invoicedMonth)
        assertEquals(PARTIALLY_INVOICED, fromForm.status)
        assertTrue(ForecastService.save(fromForm, "2026-10", canManage = true).saved)
        assertEquals(8_600_000.0, ForecastLedgerRepository.line(pid, "2026-10")!!.forecast)

        // A form that names no project is the row that disappeared, and it must not become project 0.
        val orphan = ForecastDraft.of(mapOf("forecast" to "1"))
        assertEquals(0, orphan.projectId)
        assertNotNull(ForecastService.save(orphan, "2026-10", canManage = true).error(ForecastField.PROJECT))
    }

    @Test
    fun `the sheet's own address is rebuilt from the filter it is standing on`() {
        assertEquals("/app/forecasts?month=2026-10", ForecastFilter().queryFor("2026-10"))
        assertEquals(
            "/app/forecasts?month=2026-10&q=haul+road&type=Site+projects&status=Ongoing",
            ForecastFilter("haul road", "Site projects", "Ongoing").queryFor("2026-10"),
        )
        // A row's save answers on the same sheet it came from, which is why the path is not fixed.
        assertEquals(
            "/app/forecasts/save?month=2026-10&q=OT%2FGoi",
            ForecastFilter(text = "OT/Goi").queryFor("2026-10", "/app/forecasts/save"),
        )
        // The tabs are links, so each one has to carry the sheet it stands on: a tab that dropped the
        // word in the search box would look like a filter control that clears the other one.
        val standing = ForecastFilter(text = "haul", status = "Ongoing")
        assertEquals(
            "/app/forecasts?month=2026-10&q=haul&type=Site+projects&status=Ongoing",
            standing.hrefFor("2026-10", "Site projects"),
        )
        assertEquals("/app/forecasts?month=2026-10&q=haul&type=Other&status=Ongoing", standing.hrefFor("2026-10", "Other"))
        // "All" is the sheet with no tab chosen, so its link drops the key rather than sending an empty one.
        assertEquals(
            "/app/forecasts?month=2026-10&q=haul&status=Ongoing",
            standing.hrefFor("2026-10", null),
        )
    }

    @Test
    fun `the six cells a row form can be refused on are the six the sheet prints messages under`() {
        // ForecastField is the contract between the service and the row: a field added here reappears as a
        // message under a cell, and a field dropped silently stops being reported.
        assertEquals(
            listOf("FORECAST", "INVOICED", "INVOICED_MONTH", "STATUS", "PROJECT", "PERMISSION"),
            ForecastField.entries.map { it.name },
        )
    }
}

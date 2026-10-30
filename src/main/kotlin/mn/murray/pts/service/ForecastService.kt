package mn.murray.pts.service

import mn.murray.pts.domain.ForecastRecord
import mn.murray.pts.domain.INVOICE_STATUSES
import mn.murray.pts.domain.NOT_INVOICED
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.currentMonthKey
import mn.murray.pts.domain.invoiceTint
import mn.murray.pts.domain.isValidMonthKey
import mn.murray.pts.domain.monthKeyOf
import mn.murray.pts.domain.shiftMonth
import mn.murray.pts.repository.ForecastLedgerRepository
import java.time.LocalDate

/**
 * The rules behind the Forecasts grid (V8): which month the page reads, what each of its
 * derived columns means, and what a cell is allowed to write.
 *
 * The grid's arithmetic is the whole feature, so it lives here rather than in the view, in one
 * identity the page never contradicts:
 *
 *     revenue = invoiced in previous months + this month's forecast + remaining to next months
 *
 * `previous` is the sum of the project's ledger lines for months strictly before the one being
 * read; `forecast` is the line the reader is standing on; `remaining` is what the identity
 * leaves over, and it is the only one of the three that can go negative — a project forecast
 * above its own revenue has spent its room and the page says so rather than clamping the figure
 * to zero and hiding the mistake.
 *
 * [ForecastLedgerRepository] holds the lines; nothing in this file is cached, so the numbers on
 * the page are the numbers in the database at the moment the page was drawn.
 */
object ForecastService {

    /** The status a freshly written line carries when the form does not choose one. */
    const val DEFAULT_STATUS = NOT_INVOICED

    /** The month a page reads when the URL does not name one. */
    fun monthOr(param: String?, today: LocalDate = LocalDate.now()): String =
        monthKeyOf(param, today = today)

    /** The month a ‹ / › control points at, given the one on screen. */
    fun neighbourMonth(month: String, steps: Long): String = shiftMonth(month, steps)

    /**
     * The board for one month: every project that is still running, then the ones that have
     * finished, each with the line this month holds for it — a blank line when it holds none, so
     * a project that has never been forecast still appears on the sheet that is meant to catch
     * it. Both halves honour the search and the two pickers, and both are ordered by project id,
     * which is the one order that cannot shuffle a row out from under the person typing in it.
     *
     * The sheet prints that order **grouped by project type**, the way the Projects board does, so
     * this also answers the two questions the tabs ask: what each type holds once the search and the
     * status picker have cut ([ForecastFilter.matchesWithoutType]), and the rows the open tab leaves
     * ([ForecastBoard.groups]). Ids order the rows inside a group exactly as they ordered them in the
     * single table, so grouping cannot move a row out from under somebody mid-typing.
     */
    fun board(month: String, filter: ForecastFilter = ForecastFilter()): ForecastBoard {
        val lines = ForecastLedgerRepository.forMonth(month)
        val previous = ForecastLedgerRepository.invoicedBeforeMonth(month)
        val totals = ForecastLedgerRepository.invoicedTotals()
        fun lineOf(project: Project) = ForecastLine(
            project = project,
            record = lines[project.id] ?: ForecastRecord(
                projectId = project.id,
                month = month,
                forecast = 0.0,
                invoiced = 0.0,
                status = DEFAULT_STATUS,
            ),
            previous = previous[project.id] ?: 0.0,
            totalInvoiced = totals[project.id] ?: 0.0,
        )
        val visible = ProjectService.all().filter { filter.matches(it) }
        val open = visible.filter { it.status != COMPLETED }.map(::lineOf)
        val completed = visible.filter { it.status == COMPLETED }.map(::lineOf)
        // The tab pills count what the search would leave in each type *whatever* tab is open, so a
        // tab never advertises six projects and then opens on two — the Projects board's rule.
        val counted = ProjectService.all().filter { filter.matchesWithoutType(it) }
        return ForecastBoard(
            month = month,
            open = open,
            completed = completed,
            groups = PROJECT_TYPES.mapNotNull { type ->
                ForecastGroup(
                    type = type,
                    open = open.filter { it.project.type == type },
                    completed = completed.filter { it.project.type == type },
                ).takeIf { !it.isEmpty }
            },
            tabs = PROJECT_TYPES.map { type ->
                ForecastTab(
                    type = type,
                    running = counted.count { it.type == type && it.status != COMPLETED },
                    finished = counted.count { it.type == type && it.status == COMPLETED },
                )
            },
            // "All" is not a ninth type; it is the sheet with no tab chosen, and its pill has to read
            // the same total the panels below it will.
            allCount = counted.size,
        )
    }

    /**
     * Write one cell-set — the whole line the form carries — and answer with either nothing to
     * say or the field each refusal belongs under.
     *
     * The line is keyed on [month], the month the page is reading, and never on the month the
     * form's own month cell names: that cell says *when the invoice is raised*, and letting it
     * move the money would let an unrelated keystroke rewrite the "previous months" column of
     * every month after it. A line that ends up saying nothing at all (no plan, no invoice, not
     * claimed as invoiced) is cleared rather than stored, so the grid's blank cells stay blank.
     */
    fun save(draft: ForecastDraft, month: String, canManage: Boolean): ForecastResult {
        val errors = LinkedHashMap<ForecastField, String>()
        if (!canManage) {
            return ForecastResult(false, mapOf(
                ForecastField.PERMISSION to "Forecast money is management's to set.",
            ), draft)
        }
        if (!ForecastLedgerRepository.projectExists(draft.projectId)) {
            return ForecastResult(false, mapOf(
                ForecastField.PROJECT to "That project is no longer on the list.",
            ), draft)
        }
        val key = if (isValidMonthKey(month)) month else currentMonthKey()

        val forecast = parseAmount(draft.forecastRaw)
        if (forecast == null) errors += ForecastField.FORECAST to amountRefusal(draft.forecastRaw)
        val invoiced = parseAmount(draft.invoicedRaw)
        if (invoiced == null) errors += ForecastField.INVOICED to amountRefusal(draft.invoicedRaw)
        // Blank and "this line's own month" are the same statement, so only the difference is stored.
        val invoiceMonth = draft.invoicedMonth.trim()
        if (invoiceMonth.isNotEmpty() && invoiceMonth != key && !isValidMonthKey(invoiceMonth)) {
            errors += ForecastField.INVOICED_MONTH to "Choose a month, or leave it on this one."
        }
        val status = draft.status.trim().ifEmpty { DEFAULT_STATUS }
        if (status !in INVOICE_STATUSES) {
            errors += ForecastField.STATUS to "Choose one of the three invoice states."
        }
        if (errors.isNotEmpty()) return ForecastResult(false, errors, draft)

        ForecastLedgerRepository.put(
            ForecastRecord(
                projectId = draft.projectId,
                month = key,
                forecast = forecast!!,
                invoiced = invoiced!!,
                invoicedMonth = if (invoiceMonth == key) "" else invoiceMonth,
                status = status,
            )
        )
        return ForecastResult(true, emptyMap(), draft)
    }

    private const val COMPLETED = "Completed"
}

/**
 * What the reader has asked to see: a search term, the open category tab and a status picker.
 *
 * The term matches the same four fields the Projects board matches — number, description,
 * client, manager — because it is the same reader looking for the same project, and a forecast
 * page that searched a narrower set of words would be a page that loses projects.
 *
 * [type] is the tab the sheet is standing on rather than a fourth search box: with nothing chosen
 * every category prints its own panel, and choosing one leaves only that panel. It is still a
 * filter on the rows, because a tab that narrowed the pills above it but not the money below it
 * would be a tab that lied.
 */
data class ForecastFilter(
    val text: String = "",
    val type: String = "",
    val status: String = "",
) {
    val isActive: Boolean get() = text.isNotBlank() || type.isNotEmpty() || status.isNotEmpty()

    fun matches(project: Project): Boolean {
        if (type.isNotEmpty() && project.type != type) return false
        return matchesWithoutType(project)
    }

    /** The same cut with **no** tab open — what every tab's pill counts, and what "All" leaves. */
    fun matchesWithoutType(project: Project): Boolean {
        if (status.isNotEmpty() && project.status != status) return false
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return true
        return listOf(project.code, project.name, project.client, project.manager)
            .any { it.lowercase().contains(needle) }
    }

    /**
     * This filter aimed at another month — what the ‹ / › controls and a reload link carry.
     *
     * [path] is the route that answers: the sheet's own address for a read, and
     * `/app/forecasts/save` for a row's form. A row that posted without the filter would come back
     * as the *whole* grid, and a page whose totals line changed to every project's money the moment
     * somebody typed in one filtered row would be a page that contradicts itself.
     */
    fun queryFor(month: String, path: String = "/app/forecasts"): String = buildString {
        append(path).append("?month=").append(enc(month))
        if (text.isNotBlank()) append("&q=").append(enc(text.trim()))
        if (type.isNotEmpty()) append("&type=").append(enc(type))
        if (status.isNotEmpty()) append("&status=").append(enc(status))
    }

    /**
     * The address of a tab: this sheet with [type] open instead of the one on screen.
     *
     * A tab is a link, so it carries everything the reader already set — the month they are reading,
     * the half-typed word, the status picker — and only changes the category. `null` is the All tab,
     * which asks for no type at all rather than for a type named "All", so the address bar keeps
     * stating the sheet the page shows.
     */
    fun hrefFor(month: String, type: String?): String = copy(type = type.orEmpty()).queryFor(month)

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

/** One project's row: the project's own figures, this month's line, and what the ledger adds up to. */
data class ForecastLine(
    val project: Project,
    val record: ForecastRecord,
    /** What this project invoiced in every month before the one being read. */
    val previous: Double,
    /** What it has invoiced across every month it has a line for. */
    val totalInvoiced: Double,
) {
    /**
     * What is left after this month's plan, by the identity at the top of [ForecastService].
     * Deliberately unclamped: a negative here is the page noticing an over-forecast, and a `0`
     * would be the page lying about it.
     */
    val remaining: Double get() = project.revenue - previous - record.forecast

    /**
     * The row's colour: the state management stated, not a figure derived from the amounts.
     *
     * A line that says nothing gets no colour at all. The key above the grid says pale yellow means
     * a forecast has been entered, so tinting an untouched row would have it claim a plan nobody
     * wrote — and an untouched row is exactly what the sheet exists to catch.
     */
    val tint: String get() = if (isBlank) "" else invoiceTint(record.status)

    /** True when the row holds no plan, no invoice and no claim — the grid's blank. */
    val isBlank: Boolean get() = record.forecast == 0.0 && record.invoiced == 0.0 && record.status == NOT_INVOICED
}

/**
 * The whole grid for one month: the rows the open tab leaves, split into the category panels the
 * sheet prints, plus what every tab would leave.
 *
 * [groups] is the sheet's own body — [PROJECT_TYPES] order, a type that holds nothing not in it at
 * all — and [tabs] is the row of controls above it, which counts every type whatever is open. Both
 * are read off the same [open] and [completed] lists, so a panel's rows, its totals line and its tab
 * pill cannot disagree with each other or with the month strip.
 */
data class ForecastBoard(
    val month: String,
    val open: List<ForecastLine>,
    val completed: List<ForecastLine>,
    val groups: List<ForecastGroup> = emptyList(),
    val tabs: List<ForecastTab> = emptyList(),
    val allCount: Int = 0,
) {
    val forecastTotal: Double get() = open.sumOf { it.record.forecast }
    val invoicedTotal: Double get() = open.sumOf { it.record.invoiced }
    val completedInvoicedTotal: Double get() = completed.sumOf { it.totalInvoiced }
    val isEmpty: Boolean get() = open.isEmpty() && completed.isEmpty()
}

/**
 * One category's panel: its running rows, its finished ones, and the money under each half.
 *
 * The totals are per panel rather than per sheet because the panel is what the reader is looking at:
 * a `Site projects` panel that footer-added the Fabrication shop's plan would be stating a figure no
 * row above it is part of. Summing the same two lists the panel prints is also what lets a row's save
 * swap one `<tr>` and one totals line and leave every other panel's arithmetic alone.
 */
data class ForecastGroup(
    val type: String,
    val open: List<ForecastLine>,
    val completed: List<ForecastLine>,
) {
    val forecastTotal: Double get() = open.sumOf { it.record.forecast }
    val invoicedTotal: Double get() = open.sumOf { it.record.invoiced }
    val completedInvoicedTotal: Double get() = completed.sumOf { it.totalInvoiced }
    val count: Int get() = open.size + completed.size
    val isEmpty: Boolean get() = open.isEmpty() && completed.isEmpty()
}

/**
 * One tab of the row above the panels, and the count it advertises.
 *
 * [running] and [finished] are counted with **no** tab applied, so the pill on a tab says what
 * clicking it would leave rather than what is already on screen. A tab whose type holds nothing the
 * search can find still prints, at zero: a category the company recognises is never invented away by
 * an unlucky word in the search box — the Projects board's rule, shared here.
 */
data class ForecastTab(val type: String, val running: Int, val finished: Int) {
    val count: Int get() = running + finished
}

/** The raw line form, kept as text so a refusal can hand back exactly what was typed. */
data class ForecastDraft(
    val projectId: Int,
    val forecastRaw: String = "",
    val invoicedRaw: String = "",
    val invoicedMonth: String = "",
    val status: String = NOT_INVOICED,
) {
    companion object {
        /** Read a posted line form. `project` is the only field that is not free text. */
        fun of(form: Map<String, String?>): ForecastDraft = ForecastDraft(
            projectId = form["project"]?.trim()?.toIntOrNull() ?: 0,
            forecastRaw = form["forecast"].orEmpty().trim(),
            invoicedRaw = form["invoiced"].orEmpty().trim(),
            invoicedMonth = form["invoicedmonth"].orEmpty().trim(),
            status = form["status"].orEmpty().trim(),
        )
    }
}

/** Which cell a refusal belongs under — the message prints there, not at the top of the page. */
enum class ForecastField { FORECAST, INVOICED, INVOICED_MONTH, STATUS, PROJECT, PERMISSION }

/** `saved` on success; field-keyed `errors` and the typed values when the row has to stay put. */
data class ForecastResult(val saved: Boolean, val errors: Map<ForecastField, String>, val values: ForecastDraft) {
    fun error(field: ForecastField): String? = errors[field]
}

/**
 * Accepts `8600000`, `8,600,000` and `8 600 000.50`, and reads blank as nothing — the grid opens
 * on empty cells, and a page that refused them would be a page nobody could leave alone.
 * Rejects anything non-numeric, negative or not finite. The accepted shapes are the same ones the
 * project create form takes for revenue, so the app's two money fields do not disagree about
 * what a number is.
 */
private fun parseAmount(raw: String): Double? {
    val cleaned = raw.trim().replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return 0.0
    val value = cleaned.toDoubleOrNull() ?: return null
    return if (value < 0.0 || !value.isFinite()) null else value
}

/**
 * Why [parseAmount] said no, in the words that go under the cell that said it.
 *
 * The three refusals are different mistakes and the sheet should not describe them the same way:
 * a negative is a sign, an unparseable cell is a typo, and a figure too large to be a number at
 * all is neither — it parsed fine, so saying "enter it as a number" would send the reader back to
 * the one thing they already did right.
 */
private fun amountRefusal(raw: String): String {
    val value = raw.trim().replace(" ", "").replace(",", "").toDoubleOrNull()
    return when {
        value == null -> "Enter the amount as a number, like 8600000."
        value < 0.0 -> "An amount cannot be below zero."
        else -> "That number is too large to put on a forecast."
    }
}

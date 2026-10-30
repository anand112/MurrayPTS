package mn.murray.pts.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Forecasting content, in two shapes that share a page name and nothing else.
 *
 * [Forecast] is the legacy *resource* record — a period, an hours string and a note, imported
 * verbatim and keyed to nothing the grid can do arithmetic with. It stays because the importer
 * still writes it and the V1 table still holds it; the Forecasts page simply no longer reads it.
 *
 * [ForecastRecord] is V8's money line: one project, one month, what it plans to raise, what it
 * raised, and how management labels that. Everything the grid prints is either a project's own
 * field or one of these, summed or subtracted — see
 * [mn.murray.pts.service.ForecastService.rowFor].
 */

@kotlinx.serialization.Serializable
data class Forecast(val project: String, val period: String, val hours: String, val note: String)

@kotlinx.serialization.Serializable
data class Lesson(val title: String, val project: String, val summary: String)

/** One project's line in the monthly ledger. Amounts are in the reporting currency. */
data class ForecastRecord(
    val projectId: Int,
    /** 'YYYY-MM' — the month this line belongs to and the month its money is counted in. */
    val month: String,
    val forecast: Double,
    val invoiced: Double,
    /**
     * When the invoice is raised, 'YYYY-MM', or blank for "this line's own month". Descriptive:
     * it names the invoice, it does not move the amount between lines — see V8's note on the
     * column, which is the whole reason the ledger's sums stay trustworthy.
     */
    val invoicedMonth: String = "",
    /** One of [INVOICE_STATUSES]; a label management states, not a figure the app derives. */
    val status: String,
) {
    /** The month the grid's month cell shows: the label when there is one, the line's own otherwise. */
    val shownInvoiceMonth: String get() = invoicedMonth.ifBlank { month }
}

/**
 * The three states the grid's last column offers, in the order the dropdown lists them.
 * The row's colour follows whichever one is stored, which is why these are constants here
 * rather than strings the view and the service each happen to spell the same way.
 */
val INVOICE_STATUSES = listOf("Not invoiced", "Partially invoiced", "Fully invoiced")

const val NOT_INVOICED = "Not invoiced"
const val PARTIALLY_INVOICED = "Partially invoiced"
const val FULLY_INVOICED = "Fully invoiced"

/**
 * The class a row carries for a status: `fc-entered` is the yellow of a month that has a plan
 * and no invoice, `fc-partial` the orange of one that has caught up with part of it, `fc-full`
 * the green of a settled month. An unknown label reads as the plain state, not as a colour the
 * page cannot explain.
 */
fun invoiceTint(status: String): String = when (status) {
    PARTIALLY_INVOICED -> "fc-partial"
    FULLY_INVOICED -> "fc-full"
    else -> "fc-entered"
}

/** True when a line says nothing: no plan, no invoice, and not claimed to be invoiced. */
fun isBlankLine(forecast: Double, invoiced: Double, status: String): Boolean =
    forecast == 0.0 && invoiced == 0.0 && status == NOT_INVOICED

private val MONTH_KEY: Regex = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")
private val MONTH_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

/** True for a 'YYYY-MM' string a ledger row may be keyed on. */
fun isValidMonthKey(value: String): Boolean = MONTH_KEY.matches(value)

/** A `YearMonth` written the way a page keys a month on it. */
fun monthKey(of: YearMonth): String = of.format(MONTH_OUT)

/** The month a page reads when the URL does not name one. */
fun currentMonthKey(today: LocalDate = LocalDate.now()): String = monthKey(YearMonth.from(today))

/**
 * The month an address asks for, out of the controls that are able to ask: the first name that is a
 * real 'YYYY-MM' wins, and an address that names none — or names `banana` — reads this month rather
 * than failing. A page with two ways to choose a month (step to a neighbour, or type one) passes both
 * answers here in the order they should be believed; a bookmark left on a month that no longer holds
 * anything still opens a page.
 */
fun monthKeyOf(vararg named: String?, today: LocalDate = LocalDate.now()): String =
    named.asSequence().map { (it ?: "").trim() }.firstOrNull { isValidMonthKey(it) } ?: currentMonthKey(today)

/**
 * [months] steps away from [key], kept inside a range nobody is forecasting in. An unparseable
 * key returns itself: a hand-typed `?month=banana` should leave the page on the month it was
 * asked for rather than jump somewhere else.
 */
fun shiftMonth(key: String, months: Long): String {
    val parsed = parseMonth(key) ?: return key
    return parsed.plusMonths(months.coerceIn(-1200, 1200)).format(MONTH_OUT)
}

/** A 'YYYY-MM' key as a `YearMonth`, or null for anything that is not one. */
fun parseMonth(key: String): YearMonth? =
    if (!isValidMonthKey(key)) null else try {
        YearMonth.parse(key, MONTH_OUT)
    } catch (e: Exception) {
        null
    }

/** `2026-10` -> `Oct 2026`; anything else is shown as it arrived rather than guessed at. */
fun monthLabel(key: String): String {
    val parsed = parseMonth(key) ?: return key
    return "${MONTH_NAMES[parsed.monthValue - 1]} ${parsed.year}"
}

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

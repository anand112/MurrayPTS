package mn.murray.pts.repository

import mn.murray.pts.db.ProjectForecasts
import mn.murray.pts.db.Projects
import mn.murray.pts.domain.ForecastRecord
import mn.murray.pts.domain.NOT_INVOICED
import mn.murray.pts.domain.isBlankLine
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Data-access layer for V8's monthly forecast ledger: one line per project per month.
 *
 * Everything the Forecasts grid *derives* — the amount invoiced before a month, the amount
 * invoiced overall — is summed here rather than in the view, so the page cannot show a
 * "previous months" figure that disagrees with the line it just saved. What it never does is
 * arithmetic across two different months' rows in one statement: the ledger is read as a set
 * of lines and [mn.murray.pts.service.ForecastService] decides what they mean.
 *
 * Amounts are stored as they are typed. Nothing here rounds, clamps or re-signs a figure,
 * because the grid prints the arithmetic its cells add up to and a silently adjusted number
 * would break that promise in the one place the app is read as a spreadsheet.
 */
object ForecastLedgerRepository {

    /** Every line for one month, keyed by project id. A project with no line is simply absent. */
    fun forMonth(month: String): Map<Int, ForecastRecord> = transaction {
        ProjectForecasts.selectAll().where { ProjectForecasts.month eq month }
            .associate { it[ProjectForecasts.projectId] to map(it) }
    }

    /** The sum of what this project invoiced in every month strictly before [month]. */
    fun invoicedBefore(projectId: Int, month: String): Double = transaction {
        ProjectForecasts.selectAll()
            .where { (ProjectForecasts.projectId eq projectId) and (ProjectForecasts.month less month) }
            .sumOf { it[ProjectForecasts.invoicedAmount] }
    }

    /** The sum of what this project has ever invoiced, across every line it holds. */
    fun invoicedTotal(projectId: Int): Double = transaction {
        ProjectForecasts.selectAll()
            .where { ProjectForecasts.projectId eq projectId }
            .sumOf { it[ProjectForecasts.invoicedAmount] }
    }

    /** The same total for every project at once — the completed table reads it in one pass. */
    fun invoicedTotals(): Map<Int, Double> = transaction {
        ProjectForecasts.selectAll()
            .groupBy { it[ProjectForecasts.projectId] }
            .mapValues { rows -> rows.value.sumOf { it[ProjectForecasts.invoicedAmount] } }
    }

    /**
     * What every project invoiced strictly before [month], in one pass. This is the grid's
     * "invoiced in previous months" column: the whole board needs one figure per project, and
     * asking per project would make the page's cost grow with the number of projects on it.
     */
    fun invoicedBeforeMonth(month: String): Map<Int, Double> = transaction {
        ProjectForecasts.selectAll().where { ProjectForecasts.month less month }
            .groupBy { it[ProjectForecasts.projectId] }
            .mapValues { rows -> rows.value.sumOf { it[ProjectForecasts.invoicedAmount] } }
    }

    /** Every line for one project, oldest first. */
    fun forProject(projectId: Int): List<ForecastRecord> = transaction {
        ProjectForecasts.selectAll().where { ProjectForecasts.projectId eq projectId }
            .orderBy(ProjectForecasts.month to SortOrder.ASC).map { map(it) }
    }

    /** A project's line for one month, or null when that month holds nothing for it. */
    fun line(projectId: Int, month: String): ForecastRecord? = transaction {
        ProjectForecasts.selectAll().where {
            (ProjectForecasts.projectId eq projectId) and (ProjectForecasts.month eq month)
        }.firstOrNull()?.let { map(it) }
    }

    /**
     * Write one line, or clear it.
     *
     * A line that says nothing — no plan, no invoice, and not claimed to be invoiced — is not
     * stored at all, which is what keeps a year of untouched months from filling the ledger with
     * zeroes and lets the grid's blank cells mean genuinely blank.
     */
    fun put(record: ForecastRecord) = transaction {
        val key = (ProjectForecasts.projectId eq record.projectId) and (ProjectForecasts.month eq record.month)
        if (isBlankLine(record.forecast, record.invoiced, record.status)) {
            ProjectForecasts.deleteWhere { key }
            return@transaction
        }
        if (ProjectForecasts.selectAll().where { key }.any()) {
            ProjectForecasts.update({ key }) {
                it[forecastAmount] = record.forecast
                it[invoicedAmount] = record.invoiced
                it[invoicedMonth] = record.invoicedMonth
                it[invoiceStatus] = record.status
            }
        } else {
            ProjectForecasts.insert {
                it[projectId] = record.projectId
                it[month] = record.month
                it[forecastAmount] = record.forecast
                it[invoicedAmount] = record.invoiced
                it[invoicedMonth] = record.invoicedMonth
                it[invoiceStatus] = record.status
            }
        }
    }

    fun projectExists(projectId: Int): Boolean = transaction {
        Projects.selectAll().where { Projects.id eq projectId }.any()
    }

    private fun map(r: ResultRow) = ForecastRecord(
        projectId = r[ProjectForecasts.projectId],
        month = r[ProjectForecasts.month],
        forecast = r[ProjectForecasts.forecastAmount],
        invoiced = r[ProjectForecasts.invoicedAmount],
        invoicedMonth = r[ProjectForecasts.invoicedMonth],
        status = r[ProjectForecasts.invoiceStatus].ifBlank { NOT_INVOICED },
    )
}

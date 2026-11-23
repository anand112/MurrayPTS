package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.service.ForecastBoard
import mn.murray.pts.service.ForecastDraft
import mn.murray.pts.service.ForecastField
import mn.murray.pts.service.ForecastFilter
import mn.murray.pts.service.ForecastGroup
import mn.murray.pts.service.ForecastLine
import mn.murray.pts.service.ForecastResult
import mn.murray.pts.service.ForecastService

import io.ktor.http.Parameters
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.SELECT
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.col
import kotlinx.html.colGroup
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.tfoot
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The Forecasts page (V8): one line per project per month, read as a sheet.
 *
 * The layout is the company's own forecast sheet, and the arithmetic behind it is
 * [ForecastService]'s: **revenue = invoiced in previous months + this month's forecast +
 * remaining to next months**. Only two of those four are typed — the forecast and the invoice —
 * and the page prints the other two as derived, which is why the derived cells are plain text and
 * the typed cells are fields.
 *
 * Three things about how the page updates, because they are the difference between a sheet and a
 * form that keeps throwing you back to the top of it:
 *
 *  * the search box and the two pickers are one GET form onto this route, and everything under
 *    them is a **live region** (`app.js`): a filter change is fetched and swapped in place, so the
 *    half-typed word, the caret and the scroll position all survive.
 *  * the month controls are `<button name="month">`s that live **inside** the region, so the
 *    response that changes the grid also re-points them at the next and previous month. Their
 *    values are absolute, not "one step back": reloading the address bar lands on the month it
 *    names, and a stale relative control would step twice.
 *  * each row is a form of its own, posting to `/app/forecasts/save` and swapping **only that
 *    row** plus the totals line beneath its table. Typing an amount must not move the other ninety
 *    rows, and a refusal must appear under the cell that caused it with the words still in the box.
 *
 * [refused] is the row a save rejected, if any: its typed values and its field-keyed messages come
 * back through the same renderer, so the row that was refused is re-drawn as the reader left it.
 */
fun DIV.forecastsContent(
    month: String,
    board: ForecastBoard,
    filter: ForecastFilter,
    canManage: Boolean,
    refused: ForecastResult? = null,
) {
    forecastToolbar(filter, month)
    div("board-results") {
        id = "fc-board"
        attributes["data-live-region"] = "true"
        // The tabs are inside the region because they count what the search leaves: a pill that says
        // four and opens on two is the same lie the Projects board refuses to tell, and the counts only
        // move with the rows they count.
        forecastTabs(month, filter, board)
        forecastMonthStrip(month, board)
        if (board.isEmpty) {
            forecastEmpty(filter, month)
        } else {
            // Neither half is printed as an empty container: a sheet of eleven headers over nothing,
            // or a "Completed projects" heading whose pill reads 0, is a page describing a row that is
            // not there — the same rule the board's lanes follow. Here that applies per panel too, so a
            // category with nothing the search can find does not stand over an empty table.
            board.groups.forEach { group -> forecastPanel(month, filter, group, canManage, refused) }
        }
    }
}

/**
 * All, then one tab per category, each with the count the sheet would leave if it were clicked.
 *
 * This is the Projects board's control, drawn the same way on purpose: same classes, same `?type=`
 * address, a tab that keeps the search, the status picker and the month it stands in. The Forecasts
 * sheet used to carry a **Type** picker instead, and a picker that narrows a single table to a
 * category says less than a tab that says how much of each category there is to look at.
 */
private fun DIV.forecastTabs(month: String, filter: ForecastFilter, board: ForecastBoard) {
    div("filter-tabs") {
        a(classes = if (filter.type.isEmpty()) "ftab active" else "ftab") {
            href = filter.hrefFor(month, null)
            +"All "
            span("pill") { +"${board.allCount}" }
        }
        board.tabs.forEach { tab ->
            a(classes = if (filter.type == tab.type) "ftab active" else "ftab") {
                href = filter.hrefFor(month, tab.type)
                // The lettered label the Projects board uses, not the bare category name: the two rows of
                // tabs sit on pages a reader moves between all day, and a tab called one thing on one page
                // and another on the next reads as two different filters.
                +typeLabel(tab.type)
                +" "
                span("pill") { +"${tab.count}" }
            }
        }
    }
}

/** One category: its chip and count above the money that belongs to it and to nobody else. */
private fun DIV.forecastPanel(
    month: String,
    filter: ForecastFilter,
    group: ForecastGroup,
    canManage: Boolean,
    refused: ForecastResult?,
) {
    div("card-panel") {
        div("group-head") {
            span("chip ${typeChipClass(group.type)}") { +group.type }
            span("pill") { +"${group.count}" }
        }
        if (group.open.isNotEmpty()) forecastGrid(month, filter, group, canManage, refused)
        forecastCompleted(month, filter, group, canManage, refused)
    }
}

/**
 * The search bar, the status picker and the colour key, in one GET form onto this page.
 *
 * It is *outside* the live region on purpose: the region is what gets swapped, and a toolbar that
 * swapped with it would take the caret away from the box being typed in. The month controls are the
 * part that has to change with the grid, so they sit inside the region and point back here with
 * `form="fcFilter"`.
 *
 * The open category is a **hidden field** rather than a picker, because the tabs that choose it are
 * links inside the region: searching or switching status from a panel has to stay inside that panel,
 * and a control the reader can see but the tabs already set would be a second answer to one question.
 * This is the Projects board's arrangement, picker for picker.
 */
private fun DIV.forecastToolbar(filter: ForecastFilter, month: String) {
    form(action = "/app/forecasts", method = FormMethod.get, classes = "board-toolbar fc-toolbar") {
        id = "fcFilter"
        if (filter.type.isNotEmpty()) input(type = InputType.hidden, name = "type") { value = filter.type }
        div("board-search") {
            input(type = InputType.text, name = "q") {
                attributes["inputmode"] = "search"
                attributes["enterkeyhint"] = "search"
                id = "forecastSearch"
                value = filter.text
                placeholder = "Search project, client…"
                autoComplete = "off"
                attributes["data-livefilter"] = "true"
                if (filter.text.isNotBlank()) autoFocus = true
            }
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"Search" }
        }
        label("board-key") {
            +"Status"
            select {
                name = "status"
                attributes["data-autosubmit"] = "true"
                filterOption("", "All statuses", filter.status)
                PROJECT_STATUSES.forEach { filterOption(it, it, filter.status) }
            }
        }
        if (filter.isActive) a(classes = "board-reset", href = "/app/forecasts?month=$month") { +"Clear" }
        div("fc-legend") {
            span("fc-key fc-entered") { +"Forecast entered" }
            span("fc-key fc-partial") { +"Partially invoiced" }
            span("fc-key fc-full") { +"Fully invoiced" }
        }
    }
}

private fun SELECT.filterOption(value: String, label: String, chosen: String) {
    option {
        this.value = value
        selected = value == chosen
        +label
    }
}

/**
 * The month the sheet is reading, between the two controls that change it.
 *
 * Inside the live region, so a filter change, a month change and a row save all re-point it: the ‹
 * and › buttons carry the *resolved* neighbour, which is only knowable at render time.
 */
private fun DIV.forecastMonthStrip(month: String, board: ForecastBoard) {
    div("fc-strip") {
        monthButton("‹", ForecastService.neighbourMonth(month, -1), "Previous month")
        span("fc-month") { +monthLabel(month) }
        span("task-meta") { +" · ${board.open.size} running · ${board.completed.size} completed" }
        monthButton("›", ForecastService.neighbourMonth(month, 1), "Next month")
        val today = currentMonthKey()
        if (month != today) {
            monthButton("This month", today, "Back to the current month")
        }
    }
}

private fun DIV.monthButton(glyph: String, target: String, hint: String) {
    button(type = ButtonType.submit, classes = "ghost-btn btn-sm fc-step") {
        // `form=` is what lets a control inside the swapped region submit the toolbar's GET form:
        // the search term and the two pickers ride along, so changing the month cannot silently
        // reset a filter the reader is standing on.
        attributes["form"] = "fcFilter"
        name = "month"
        value = target
        attributes["title"] = hint
        +glyph
    }
}

/** A sheet with nothing on it still says which of the two reasons this is. */
private fun DIV.forecastEmpty(filter: ForecastFilter, month: String) {
    div("card-panel") {
        if (filter.isActive) {
            val wanted = filter.text.takeIf { it.isNotBlank() }?.let { " “$it”" }.orEmpty()
            val where = filter.type.takeIf { it.isNotEmpty() }?.let { " in $it" }.orEmpty()
            val how = filter.status.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            span("task-meta") { +"Nothing here matches$wanted$where$how." }
            +" "
            a(classes = "row-link", href = "/app/forecasts?month=$month") { +"Show every project" }
        } else {
            span("task-meta") { +"No projects yet. A line here is one project's money for one month, so" }
            +" "
            a(classes = "row-link", href = "/app/projects") { +"create a project" }
            span("task-meta") { +" and it appears on this sheet." }
        }
    }
}

/** One category's running projects: the eleven columns the sheet is read by. */
private fun DIV.forecastGrid(
    month: String,
    filter: ForecastFilter,
    group: ForecastGroup,
    canManage: Boolean,
    refused: ForecastResult?,
) {
    val totalsId = fcTotalsId(group, done = false)
    table("grid fc-table") {
        colGroup { FC_COLUMNS.forEach { col(classes = it) } }
        thead {
            tr {
                th(classes = "f-type") { +"Type" }
                th(classes = "f-code") { +"Project #" }
                th(classes = "f-desc") { +"Description" }
                th(classes = "f-client") { +"Client" }
                th(classes = "f-rev") { +"Revenue" }
                th(classes = "f-prev") { +"Invoiced in previous months" }
                th(classes = "f-forecast") { +"Forecast (current month)" }
                th(classes = "f-remain") { +"Remainant to next months" }
                th(classes = "f-imonth") { +"Invoiced month" }
                th(classes = "f-invoiced") { +"Invoiced amount" }
                th(classes = "f-status") { +"Invoice status" }
            }
        }
        tbody { group.open.forEach { line -> forecastRow(line, month, filter, canManage, refused, totalsId) } }
        tfoot {
            tr("fc-totals") {
                id = totalsId
                td("fc-total-label") {
                    attributes["colspan"] = "6"
                    +"Forecast total:"
                }
                td("fc-total-num") { span("money") { +bareMoney(group.forecastTotal) } }
                td("fc-total-label") {
                    attributes["colspan"] = "2"
                    +"Actual invoiced total:"
                }
                td("fc-total-num") { span("money") { +bareMoney(group.invoicedTotal) } }
                td { }
            }
        }
    }
}

/**
 * The finished projects, on the same ledger and a narrower sheet.
 *
 * A completed project has no forward plan to read, so the two columns that carry one — this
 * month's forecast and what is left for later months — are gone, and what replaces them is the
 * figure that matters at the end of a job: everything it has ever been invoiced for, across every
 * line in the ledger.
 */
private fun DIV.forecastCompleted(
    month: String,
    filter: ForecastFilter,
    group: ForecastGroup,
    canManage: Boolean,
    refused: ForecastResult?,
) {
    if (group.completed.isEmpty()) return
    val totalsId = fcTotalsId(group, done = true)
    div("fc-completed-head") {
        h3 { +"Completed projects — invoiced" }
        span("pill") { +"${group.completed.size}" }
    }
    table("grid fc-done-table") {
        colGroup { FC_DONE_COLUMNS.forEach { col(classes = it) } }
        thead {
            tr {
                th(classes = "d-type") { +"Type" }
                th(classes = "d-code") { +"Project #" }
                th(classes = "d-desc") { +"Description" }
                th(classes = "d-client") { +"Client" }
                th(classes = "d-rev") { +"Revenue" }
                th(classes = "d-total") { +"Total invoiced" }
                th(classes = "d-imonth") { +"Invoiced month" }
                th(classes = "d-invoiced") { +"Invoiced amount" }
            }
        }
        tbody { group.completed.forEach { line -> forecastDoneRow(line, month, filter, canManage, refused, totalsId) } }
        tfoot {
            tr("fc-totals") {
                id = totalsId
                td("fc-total-label") {
                    attributes["colspan"] = "5"
                    +"Invoiced against these projects:"
                }
                td("fc-total-num") { span("money") { +bareMoney(group.completedInvoicedTotal) } }
                td { }
                td { }
            }
        }
    }
}

/**
 * One running project: four figures of its own, two typed cells, one derived, one stated.
 *
 * The row carries the id the row's own form swaps (`#fc-row-<id>`) and the tint that states the
 * invoice status, and every writer in it is `data-autosubmit` — leaving a cell is the save, which
 * is what a sheet does and a form does not.
 */
private fun TBODY.forecastRow(
    line: ForecastLine,
    month: String,
    filter: ForecastFilter,
    canManage: Boolean,
    refused: ForecastResult?,
    totalsId: String,
) {
    val pid = line.project.id
    val writes = RowWrites(line, canManage, refused)
    tr(rowClass(line)) {
        id = "fc-row-$pid"
        identityCells(line, "f")
        td("f-rev") { span("money") { +formatMoney(line.project.currency, line.project.revenue) } }
        td("f-prev") { span("money dim") { +bareMoney(line.previous) } }
        td("f-forecast") { forecastCell(writes) }
        td("f-remain") { span("money dim") { +bareMoney(line.remaining) } }
        td("f-imonth") { invoiceMonthCell(writes) }
        td("f-invoiced") { invoiceAmountCell(writes) }
        td("f-status") {
            statusCell(writes)
            forecastLineForm(pid, month, filter, canManage, "fc-row-$pid", totalsId)
        }
    }
}

/** One completed project: the same line, read by what it has totalled rather than by what is left. */
private fun TBODY.forecastDoneRow(
    line: ForecastLine,
    month: String,
    filter: ForecastFilter,
    canManage: Boolean,
    refused: ForecastResult?,
    totalsId: String,
) {
    val pid = line.project.id
    val writes = RowWrites(line, canManage, refused)
    tr(rowClass(line)) {
        id = "fc-row-$pid"
        identityCells(line, "d")
        td("d-rev") { span("money") { +formatMoney(line.project.currency, line.project.revenue) } }
        td("d-total") {
            val covered = line.project.revenue > 0 && line.totalInvoiced >= line.project.revenue
            span(if (covered) "money fc-covered" else "money") { +bareMoney(line.totalInvoiced) }
        }
        td("d-imonth") { invoiceMonthCell(writes) }
        td("d-invoiced") {
            invoiceAmountCell(writes)
            forecastLineForm(pid, month, filter, canManage, "fc-row-$pid", totalsId)
        }
    }
}

/**
 * The row's classes: its colour, or none at all.
 *
 * [mn.murray.pts.service.ForecastLine.tint] is empty for a line that holds nothing, and joining
 * rather than interpolating keeps that row's markup `class="fc-row"` instead of `class="fc-row "`
 * — a test reads the latter as a tinted row whose tint went missing.
 */
private fun rowClass(line: ForecastLine): String =
    listOf("fc-row", line.tint).filter { it.isNotEmpty() }.joinToString(" ")

/** Type letter, number, description, client — the four columns the two sheets have in common. */
private fun TR.identityCells(line: ForecastLine, sheet: String) {
    td("$sheet-type") { span("fc-letter") { +typeLetter(line.project.type) } }
    td("$sheet-code") {
        a(href = "/app/projects/${line.project.id}", classes = "row-link") {
            span("code") { +line.project.code }
        }
    }
    td("$sheet-desc") { span("pname") { +line.project.name } }
    td("$sheet-client") { +line.project.client.ifEmpty { "—" } }
}

/**
 * Whether this row is writable, and — when a save of *this* row was refused — what was typed into
 * it and what the sheet said back.
 *
 * It is per row rather than per page because a refusal belongs to one line: the message prints under
 * the cell that caused it, and every other row on the sheet is drawn from the database as usual.
 */
private class RowWrites(val project: ForecastLine, val canManage: Boolean, refused: ForecastResult?) {
    val pid: Int = project.project.id
    private val draft: ForecastDraft? = refused?.values?.takeIf { it.projectId == pid }
    private val errors: Map<ForecastField, String> = if (draft == null) emptyMap() else refused!!.errors

    /** The message under one cell, if this row was the one refused and this cell caused it. */
    fun message(field: ForecastField): String? = errors[field]

    /** This month's plan as the box should read it: the typed characters after a refusal, the figure otherwise. */
    fun forecastText(): String = draft?.forecastRaw?.takeIf { it.isNotEmpty() }
        ?: shown(project.record.forecast)

    fun invoicedText(): String = draft?.invoicedRaw?.takeIf { it.isNotEmpty() }
        ?: shown(project.record.invoiced)

    /**
     * A blank line opens as an empty box rather than a prefilled `0`: the sheet's blank cells are
     * meant to read as blank, and a zero the reader has to delete before typing their own figure is
     * a cell that fights them. A row that says *something* still prints its real zeroes — on a money
     * grid a `0` beside a plan is a statement, and the totals below the table add the same figures
     * the read-only columns print.
     */
    private fun shown(value: Double): String =
        if (value == 0.0 && project.isBlank) "" else bareMoney(value)

    // A refused month that is not a month at all is dropped: `<input type="month">` would reject the
    // value as it parsed the markup and print an empty box, which loses more than it keeps.
    fun invoiceMonth(): String = draft?.invoicedMonth?.takeIf { isValidMonthKey(it) }
        ?: project.record.shownInvoiceMonth

    fun status(): String = draft?.status?.takeIf { it in INVOICE_STATUSES } ?: project.record.status
}

/** This month's plan, as a figure for a reader who may not write it and a box for one who may. */
private fun TD.forecastCell(w: RowWrites) {
    if (!w.canManage) {
        span("money") { +bareMoney(w.project.record.forecast) }
        return
    }
    amountField("fc-forecast-${w.pid}", "forecast", forecastFormId(w.pid), w.forecastText(), w.message(ForecastField.FORECAST))
}

private fun TD.invoiceMonthCell(w: RowWrites) {
    if (!w.canManage) {
        span("dim") { +monthLabel(w.project.record.shownInvoiceMonth) }
        return
    }
    monthField("fc-imonth-${w.pid}", forecastFormId(w.pid), w.invoiceMonth(), w.message(ForecastField.INVOICED_MONTH))
}

private fun TD.invoiceAmountCell(w: RowWrites) {
    if (!w.canManage) {
        span("money") { +bareMoney(w.project.record.invoiced) }
        return
    }
    amountField("fc-invoiced-${w.pid}", "invoiced", forecastFormId(w.pid), w.invoicedText(), w.message(ForecastField.INVOICED))
}

private fun TD.statusCell(w: RowWrites) {
    if (!w.canManage) {
        span("fc-state") { +w.project.record.status }
        return
    }
    statusField("fc-status-${w.pid}", forecastFormId(w.pid), w.status(), w.message(ForecastField.STATUS))
}

/**
 * The form a row's cells post to.
 *
 * It is rendered inside the row, carries only the keys that say *which line of which sheet* is being
 * written — the project, the month, and the search and pickers the reader is looking through — and is
 * named by every field in the row through `form="…"`. That is what lets one `<tr>` hold three
 * independent writers with no button among them: the browser associates a control with a form
 * anywhere in the document, so the row can stay a row and the form can still be a form.
 *
 * `month` is the line being written, not the month the invoice cell names. The two are different
 * questions — see [ForecastService.save] — and only the first is allowed to decide where the money
 * is counted. The `board_*` fields are the same trick the project create popup uses: a save answers
 * on the sheet the reader was standing on, so its totals line cannot change to the whole catalogue's
 * money because one row was typed into.
 */
private fun TD.forecastLineForm(
    projectId: Int,
    month: String,
    filter: ForecastFilter,
    canManage: Boolean,
    rowId: String,
    totalsId: String,
) {
    if (!canManage) return
    form(action = "/app/forecasts/save", method = FormMethod.post, classes = "fc-line-form") {
        id = forecastFormId(projectId)
        attributes["data-live-target"] = "#$rowId"
        attributes["data-live-extra"] = "#$totalsId"
        input(type = InputType.hidden, name = "project") { value = projectId.toString() }
        input(type = InputType.hidden, name = "month") { value = month }
        input(type = InputType.hidden, name = "board_q") { value = filter.text }
        input(type = InputType.hidden, name = "board_type") { value = filter.type }
        input(type = InputType.hidden, name = "board_status") { value = filter.status }
    }
}

private fun forecastFormId(projectId: Int): String = "fc-line-$projectId"

/**
 * The totals line a panel's rows swap.
 *
 * One id served the whole sheet while the sheet was one running table and one completed table. Panels
 * changed that: with a table per category, a single `#fc-totals` would appear nine times over, and a
 * row save would then repaint the first panel on the page instead of the line under the row that was
 * typed into — money moving where nobody touched it. The category's letter is what [ForecastBoard]
 * groups by, it is unique across [PROJECT_TYPES], and it needs no counter that would shift when a
 * category happens to hold nothing the search can find.
 */
private fun fcTotalsId(group: ForecastGroup, done: Boolean): String =
    if (done) "fc-totals-done-${typeLetter(group.type)}" else "fc-totals-${typeLetter(group.type)}"

private fun TD.amountField(id: String, name: String, formId: String, value: String, error: String?) {
    input(type = InputType.text, name = name) {
        this.id = id
        attributes["form"] = formId
        attributes["class"] = "fc-num"
        // The grouped figure the sheet reads rather than the raw store value: the box shows what
        // the column means, and the route accepts either spelling — and after a refusal it shows the
        // characters that were typed, which is the only way the message under it means anything.
        this.value = value
        attributes["inputmode"] = "decimal"
        autoComplete = "off"
        attributes["data-autosubmit"] = "true"
    }
    if (error != null) p("field-err") { +error }
}

private fun TD.monthField(id: String, formId: String, value: String, error: String?) {
    input(type = InputType.month, name = "invoicedmonth") {
        this.id = id
        attributes["form"] = formId
        attributes["class"] = "fc-month-field"
        this.value = value
        attributes["data-autosubmit"] = "true"
    }
    if (error != null) p("field-err") { +error }
}

private fun TD.statusField(id: String, formId: String, value: String, error: String?) {
    select {
        this.id = id
        attributes["form"] = formId
        name = "status"
        attributes["class"] = "fc-status-field"
        attributes["data-autosubmit"] = "true"
        INVOICE_STATUSES.forEach { option { this.value = it; selected = it == value; +it } }
    }
    if (error != null) p("field-err") { +error }
}

/**
 * Amounts print grouped, the way the sheet reads them. A line with nothing on it prints `0`, not
 * the em-dash the Projects board uses: on a money grid a dash and a real zero are two different
 * statements, and only one of them is a number the totals can add.
 */
/**
 * The sheet's other money cells stay bare grouped figures — the headings no longer name a currency (Stage 109
 * removed the *MNT* they used to claim), because two projects on one panel may be signed in different ones.
 * A **revenue** cell is the one money column signed with its project's currency (Stage 108's sign, Stage 109's
 * project choice), because that is the figure management typed in that unit.
 */
private fun bareMoney(value: Double): String = groupedAmount(value)

/** The eleven columns of the running sheet, in the order the header above prints them. */
private val FC_COLUMNS = listOf(
    "f-type", "f-code", "f-desc", "f-client", "f-rev", "f-prev",
    "f-forecast", "f-remain", "f-imonth", "f-invoiced", "f-status",
)

/** The eight columns of the completed sheet. */
private val FC_DONE_COLUMNS =
    listOf("d-type", "d-code", "d-desc", "d-client", "d-rev", "d-total", "d-imonth", "d-invoiced")

/**
 * The line a row's form posted, read exactly as submitted.
 *
 * The service takes a [ForecastDraft] rather than a `Parameters` so its rules can be tested without
 * a request, and the raw text is kept because a refusal has to hand the typed characters back.
 */
fun forecastDraftOf(form: Parameters): ForecastDraft = ForecastDraft(
    projectId = form["project"]?.trim()?.toIntOrNull() ?: 0,
    forecastRaw = form["forecast"].orEmpty().trim(),
    invoicedRaw = form["invoiced"].orEmpty().trim(),
    invoicedMonth = form["invoicedmonth"].orEmpty().trim(),
    status = form["status"].orEmpty().trim(),
)

/**
 * The sheet as the address bar states it — what a GET onto the page is reading.
 *
 * The category is [PROJECT_TYPES] or nothing: the tabs are links now, so `?type=` is part of an address
 * a reader can edit, bookmark or receive from someone else, and a name the sheet has no panel for would
 * cut the page down to an empty grid and still print the word in the "nothing matches" line. Dropping it
 * reads as All, which is what the Projects board's `?type=` does for the same reason.
 */
fun forecastFilterOf(query: Parameters): ForecastFilter = ForecastFilter(
    text = query["q"].orEmpty().trim(),
    type = query["type"].orEmpty().trim().takeIf { it in PROJECT_TYPES }.orEmpty(),
    status = query["status"].orEmpty().trim(),
)

/**
 * The same sheet as a row's form states it.
 *
 * The three toolbar keys arrive under `board_*` because the form's own fields are the line's — an
 * un-prefixed `status` would be the invoice state, and the two are not the same column.
 *
 * The category is checked the same way as a GET, and for the same reason with a different cause: the
 * hidden field is markup, and markup can be edited in a devtools panel. A row that posted a foreign
 * type must not be answered on a sheet that has no panel for it, because the totals line it swaps back
 * would not be the one under it.
 */
fun forecastFilterOfForm(form: Parameters): ForecastFilter = ForecastFilter(
    text = form["board_q"].orEmpty().trim(),
    type = form["board_type"].orEmpty().trim().takeIf { it in PROJECT_TYPES }.orEmpty(),
    status = form["board_status"].orEmpty().trim(),
)

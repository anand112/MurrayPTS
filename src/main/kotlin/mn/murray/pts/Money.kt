package mn.murray.pts

import java.text.DecimalFormat
import mn.murray.pts.domain.Currency

/**
 * **Stage 108 put a sign on revenue; Stage 109 lets the project choose which sign.**
 *
 * A project's money is written and shown in its own [Currency] (V20): tugrik unless the create form chose
 * USD or CNY. Every revenue figure — the board's column, the project header's box and the Viewer's read-only
 * cell, the forecast sheet's revenue cells — is signed with that project's symbol by [formatMoney]. Nothing
 * here converts an amount: the stored number is the number the reader typed, and the currency only says what
 * it is counted in.
 *
 * A **non-breaking space** sits between the symbol and the amount (U+00A0) so a narrow cell can never wrap
 * the sign onto a line of its own with its figure beneath it. [mn.murray.pts.service.ProjectService]'s
 * parsing strips the sign, that space, ordinary spaces and commas back off on the way in, so a value copied
 * out of one of these cells can be pasted straight into an input.
 */
internal fun formatMoney(currency: Currency, value: Double): String =
    currency.symbol + "\u00A0" + groupedAmount(value)

/**
 * A comma-grouped amount with no sign: what the money inputs hold, and what figures that are not revenue
 * (an invoice amount, a forecast, a month's remainder) still print. Negative amounts keep their minus —
 * a plan can overrun its revenue and the sheet says so rather than clamping the figure.
 */
internal fun groupedAmount(value: Double): String = DecimalFormat("#,##0").format(value)

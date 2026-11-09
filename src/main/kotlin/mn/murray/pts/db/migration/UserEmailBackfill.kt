package mn.murray.pts.db.migration

import mn.murray.pts.db.Users
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.AppSettingRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * One pass that puts the accounts already in the database under the same company-email rule that new ones get
 * (Stage 126).
 *
 * Stage 125 fixed the rule itself — a blank email becomes `firstname.surname@murrays.mn` — but a rule written at
 * create time says nothing about the rows that were written before it: those kept whatever their older rule had
 * produced (the singular `@murray.mn`, every word of a name joined with dots). Asking for the address format and
 * then showing a sheet where only tomorrow's hires wear it is the half-answer, so this pass recomputes every
 * account's address from its own name through [autoEmail] — the same single function the create paths call, which
 * is why this lives in Kotlin rather than as a `V*.sql` file: SQLite has no regex, so a SQL copy of the tokenizer
 * would be a second implementation of one rule, free to drift from the first.
 *
 * What it rewrites: any stored address that is not already exactly what the account's own name produces. That
 * includes an address somebody typed in the **Email** box, which the creation paths still keep untouched — the
 * difference is that this runs **once**, so a typed address written afterwards stays typed. What it leaves alone:
 * a name with no word in it, where the rule yields no address at all — an account is never blanked by the pass.
 *
 * It is guarded by a version marker in [AppSettingRepository] (the same non-secret switch store V13 created), so
 * a second boot is a read of one key and no row is touched. Bumping [RULE_VERSION] re-runs the pass for everybody
 * on the next boot, which is what would happen the day the format changed again.
 */
object UserEmailBackfill {

    /** The `app_settings` key recording which rule version this database has already been put through. */
    const val RULE_KEY = "email_rule_version"

    /** The rule whose address this pass writes. Bump alongside a change to [autoEmail]'s output. */
    const val RULE_VERSION = "1"

    /**
     * Recompute every account's address if this database has not seen [RULE_VERSION] yet.
     *
     * @return how many accounts were rewritten; `0` when the marker already matches, and `0` on a database whose
     *   rows already read exactly as the rule produces.
     */
    fun applyOnce(): Int {
        if (AppSettingRepository.get(RULE_KEY) == RULE_VERSION) return 0
        val changed = transaction {
            val rows = Users.selectAll().map { Triple(it[Users.employeeNumber], it[Users.name], it[Users.email]) }
            var rewritten = 0
            for ((employeeNumber, name, stored) in rows) {
                val address = autoEmail(name)
                if (address.isEmpty() || address == stored) continue
                Users.update({ Users.employeeNumber eq employeeNumber }) { it[Users.email] = address }
                rewritten++
            }
            rewritten
        }
        AppSettingRepository.put(RULE_KEY, RULE_VERSION)
        return changed
    }
}

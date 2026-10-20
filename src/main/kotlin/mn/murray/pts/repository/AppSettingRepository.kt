package mn.murray.pts.repository

import mn.murray.pts.db.AppSettings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Reads and writes the handful of app-wide switches a super admin owns from within the app (V13).
 *
 * The whole surface is two calls: [get] returns the stored text or null when the key was never written, and
 * [put] replaces a key's value in place (one row per key, so a re-write is an UPDATE). There is deliberately
 * no cache and no interpretation here — a missing key is not coerced to "false" by the repository, it is
 * simply null; deciding what null means is [mn.murray.pts.service.MailSwitch]'s job. Keeping the store dumb
 * means a second switch added later reuses this object unchanged and only adds a new reader.
 *
 * Secrets do not belong in this table. The SMTP password lives in `data/.env` and never passes through here.
 */
object AppSettingRepository {

    fun get(key: String): String? = transaction {
        AppSettings.selectAll().where { AppSettings.key eq key }.firstOrNull()?.get(AppSettings.value)
    }

    /** Write [value] for [key], creating the row the first time and overwriting it after. */
    fun put(key: String, value: String) {
        transaction {
            val exists = AppSettings.selectAll().where { AppSettings.key eq key }.count() > 0
            if (exists) {
                AppSettings.update({ AppSettings.key eq key }) { it[AppSettings.value] = value }
            } else {
                AppSettings.insert {
                    it[AppSettings.key] = key
                    it[AppSettings.value] = value
                }
            }
        }
    }

    fun remove(key: String): Int = transaction { AppSettings.deleteWhere { AppSettings.key eq key } }
}

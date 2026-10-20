package mn.murray.pts.service

import mn.murray.pts.repository.AppSettingRepository

/**
 * The in-app master switch for outbound mail: whether this installation is allowed to send email to people
 * at all (Stage 62).
 *
 * This is **not** the same thing as the `data/.env` mail keys. Those are the operator's deployment config —
 * the SMTP host, the dry-run flag, and the password, which is edited in the file and applied on a restart and
 * must never be typed into a web form. What this switch adds on top of them is a second, *separately-owned*
 * gate that only a super admin can flip from Settings and that survives a restart: even with a fully working
 * `.env` and dry-run off, **nothing leaves the machine until [enabled] is true**. It is stored in the V13
 * `app_settings` table, seeded empty, so the default is OFF and no migration or restart can turn the system
 * into a mailer on its own — it starts sending because an admin said so, from the same panel that reports the
 * delivery mode.
 *
 * [enabled] treats an absent key as false rather than defaulting it anywhere else, so this is the one place
 * that decides what "unset" means; [mn.murray.pts.service.Mailer] simply asks. Because every outbound path in
 * the app — the scheduled due-date sweep, the assignment notices, and the admin delivery tests — funnels
 * through that one mailer, gating it here covers all of them with a single read.
 */
object MailSwitch {

    /** The `app_settings` key this switch lives under. */
    const val KEY = "mail_send_enabled"

    /** True only when a super admin has explicitly turned sending on; unset or any other value is OFF. */
    fun enabled(): Boolean = AppSettingRepository.get(KEY) == "true"

    /**
     * Record an admin's decision to allow (or stop) live sending. Returns false and writes nothing when the
     * caller is not an admin — the right to decide whether the whole system may email anyone is the super
     * admin's alone, mirrored by the route that calls this, so a Manager's or Viewer's press cannot flip it.
     */
    fun setEnabled(on: Boolean, canAdmin: Boolean): Boolean {
        if (!canAdmin) return false
        AppSettingRepository.put(KEY, if (on) "true" else "false")
        return true
    }
}

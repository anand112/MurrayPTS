package mn.murray.pts.service

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import mn.murray.pts.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.UnsupportedEncodingException
import java.util.Properties

/**
 * What a single delivery attempt did. The due-date ledger stores this verbatim so an operator can
 * read the history of an alert straight out of SQLite — including the ones that never left the box.
 */
sealed class MailResult {
    /** SMTP accepted the message. */
    data class Sent(val messageId: String) : MailResult()

    /**
     * Deliberately not sent: dry run, no mail server configured, or no address on file. Logged at
     * INFO so the message content is still reviewable in `logs/pts.log`.
     */
    data class LoggedOnly(val reason: String) : MailResult()

    /** A real attempt failed (auth, TLS, DNS, timeout). Logged at WARN with the reason. */
    data class Failed(val error: String) : MailResult()
}

/**
 * The only outbound mail path in the app: plain SMTP via Jakarta Mail + the Angus provider.
 *
 * Kept deliberately thin and side-effect-free from the alert rules — [DueDateAlertService] decides
 * *who* hears about *what*, this class only knows how to hand a text message to a server. That split
 * is also what makes the rules testable without a mail server: the service runs in dry-run mode and
 * asserts on the rendered text.
 *
 * Configuration comes from `AppConfig` (`PTS_SMTP_*`); the password is never logged.
 */
object Mailer {

    private val log = LoggerFactory.getLogger(Mailer::class.java)

    /**
     * True when [send] would actually open a connection: the operator's `.env` is configured to send
     * (`PTS_SMTP_HOST` set, dry-run off) **and** a super admin has turned on the in-app mail switch
     * ([MailSwitch.enabled]). Either one alone is enough to keep every message log-only — the switch is the
     * second, admin-owned gate, so a working `.env` still cannot mail anyone until Settings says so.
     */
    val live: Boolean get() = AppConfig.smtpConfigured && MailSwitch.enabled()

    /**
     * Send one plain-text message. Never throws: every failure mode comes back as a [MailResult],
     * because a sweep that aborts on the first bad recipient would silently skip the rest of the
     * organisation.
     */
    fun send(to: String, subject: String, body: String): MailResult {
        if (to.isBlank()) return MailResult.LoggedOnly("no email address on file for this recipient")

        if (!live) {
            val reason = when {
                AppConfig.smtpDryRun -> "SMTP dry run"
                AppConfig.smtpHost.isBlank() -> "SMTP not configured (PTS_SMTP_HOST empty)"
                // The operator's .env could send, but the super admin's Settings switch has not said so yet.
                else -> "outbound mail is switched off on Settings (only a super admin can enable it)"
            }
            log.info("Mail (log only, {}): to={} subject={}", reason, to, subject)
            body.lines().forEach { log.info("Mail body | {}", it) }
            return MailResult.LoggedOnly(reason)
        }

        return try {
            val session = sessionFor(AppConfig)
            val message = MimeMessage(session).apply {
                setFrom(fromAddress())
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false))
                setSubject(subject, "UTF-8")
                setText(body, "UTF-8")
                setSentDate(java.util.Date.from(java.time.Instant.now()))
            }
            Transport.send(message)
            MailResult.Sent(message.messageID ?: "")
        } catch (e: Exception) {
            // e.toString(), not just getMessage(): Jakarta throws AuthenticationException with a
            // null message sometimes, and the class name is the only clue left.
            val reason = "${e.javaClass.simpleName}: ${e.message ?: e.toString()}"
            log.warn("Mail delivery to {} failed: {}", to, reason)
            MailResult.Failed(reason.take(500))
        }
    }

    /**
     * A fresh Session per call is fine — a day's sweep is a handful of messages and Session creation
     * is cheap; sharing one would just hide which run used which config.
     */
    private fun sessionFor(cfg: AppConfig): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
            when (cfg.smtpSecurity) {
                // Implicit TLS on connect (classic port 465).
                "ssl", "smtps", "imaps-like" -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                }
                // No TLS at all: only sane on a loopback/localhost relay, e.g. a container sidecar.
                "none" -> Unit
                // Anything else (including "starttls") upgrades a plain connection with STARTTLS.
                else -> put("mail.smtp.starttls.enable", "true")
            }
            if (cfg.smtpUsername.isNotBlank()) put("mail.smtp.auth", "true")
        }

        val authenticator = if (cfg.smtpUsername.isNotBlank()) {
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(cfg.smtpUsername, cfg.smtpPassword)
            }
        } else {
            null
        }
        return Session.getInstance(props, authenticator)
    }

    private fun fromAddress(): InternetAddress {
        val from = AppConfig.smtpFrom
        return try {
            // A display name makes the alert recognisable in a busy inbox; "PTS" is the app's own name.
            InternetAddress(from, "MURRAY PTS").also { it.validate() }
        } catch (e: UnsupportedEncodingException) {
            InternetAddress(from)
        }
    }
}

package mn.murray.pts.web.plugin

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import mn.murray.pts.config.AppConfig

/**
 * Adds baseline security response headers to every response.
 *
 * - `X-Content-Type-Options: nosniff` — stop MIME sniffing.
 * - `X-Frame-Options: DENY`           — block clickjacking via framing.
 * - `Referrer-Policy`                 — limit referrer leakage.
 * - `Permissions-Policy`              — disable unneeded browser features.
 * - `Content-Security-Policy`         — see note below.
 * - `Strict-Transport-Security`       — only in production (assumes TLS termination).
 *
 * CSP note: Stage 7 extracted all inline CSS/JS to /static files and replaced the inline
 * event-handler attributes with delegated listeners in app.js, so 'unsafe-inline' is gone
 * from script-src. The only remaining inline exception is style-src-attr 'unsafe-inline',
 * which permits presentation attributes such as the server-computed progress-bar widths
 * (style="width:42%") and calendar task-bar colours. Inline <style> and <script> *elements*
 * are blocked: no nonce/hash is needed because the app emits neither. The one inline script
 * tag left is <script type="application/json"> on the Team page — a data island, which CSP
 * does not treat as executable.
 */
val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "same-origin")
        call.response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; " +
                "style-src 'self'; style-src-attr 'unsafe-inline'; script-src 'self'; " +
                "object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
        )
        if (AppConfig.isProduction) {
            call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }
}

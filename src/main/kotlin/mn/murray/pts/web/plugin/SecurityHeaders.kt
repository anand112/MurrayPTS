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
 * CSP note: the legacy UI still renders inline <style>/<script> and inline event
 * handlers, so the policy currently allows 'unsafe-inline'. Stage 7 extracts those to
 * /static files, after which the 'unsafe-inline' allowances should be removed (and a
 * nonce/hash added for any remaining inline script). Tracked in ARCHITECTURE.md.
 */
val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "same-origin")
        call.response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; " +
                "base-uri 'self'; form-action 'self'",
        )
        if (AppConfig.isProduction) {
            call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }
}

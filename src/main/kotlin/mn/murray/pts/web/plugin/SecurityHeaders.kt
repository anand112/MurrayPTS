package mn.murray.pts.web.plugin

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import mn.murray.pts.config.AppConfig

/**
 * Is this request for a page the server draws out of the database for the reader standing in front of it?
 *
 * Every one of those answers is only true for the instant it was rendered: a card dragged on a board writes a
 * new status, and the copy of the Team sheet — or of any other page — that the browser kept from before the
 * drag is simply wrong. Left to itself Chrome does keep such a copy, because an HTML response with no
 * `Cache-Control` of its own is fair game for the back button and for reopening the tab, and the reader then
 * sees figures from the past until they press F5 (Stage 69). Telling the browser not to store the answer makes
 * every visit ask again, which is what makes the numbers on the screen the numbers in the database.
 *
 * Paths outside this set are the static assets under `/static` and the uploaded files under `/files`: they are
 * the same bytes for every reader, so caching them is a win and costs nothing in correctness.
 */
internal fun isServerDrawnPage(path: String): Boolean =
    path == "/" || path == "/login" || path == "/logout" ||
        path == "/app" || path.startsWith("/app/")

/**
 * Adds baseline security response headers to every response.
 *
 * - `X-Content-Type-Options: nosniff` — stop MIME sniffing.
 * - `X-Frame-Options: DENY`           — block clickjacking via framing.
 * - `Referrer-Policy`                 — limit referrer leakage.
 * - `Permissions-Policy`              — disable unneeded browser features.
 * - `Cache-Control: no-store`         — on the pages the server draws, see [isServerDrawnPage].
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
        if (isServerDrawnPage(call.request.path())) {
            call.response.header("Cache-Control", "no-store")
        }

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

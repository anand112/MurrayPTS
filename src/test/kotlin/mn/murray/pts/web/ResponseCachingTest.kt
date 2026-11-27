package mn.murray.pts.web

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mn.murray.pts.web.plugin.SecurityHeaders
import mn.murray.pts.web.plugin.isServerDrawnPage
import mn.murray.pts.web.plugin.isStaticAsset

/**
 * Server-drawn pages must never come back from the browser's cache: their figures belong to the instant they
 * were rendered. Unversioned static files may be stored only if the browser checks with the server before
 * reuse; Ktor's static-resource route currently answers that check with the current file rather than a 304.
 * These tests exercise real responses through the plugin and route, not just the source that decides policy.
 */
class ResponseCachingTest {

    @Test
    fun `every page the server draws for the reader is one the browser must ask again for`() {
        listOf(
            "/",                       // the sign-in page: the reader's own account decides what it says
            "/app",
            "/app/team",               // the sheet whose figures the user reported stale
            "/app/team/assign",        // the page a refused save answers with
            "/app/assigned-tasks",
            "/app/other-tasks",
            "/app/projects",
            "/app/projects/12",        // a board: its counts are the cards on it, right now
            "/app/calendar",
            "/app/settings",
            "/login",
            "/logout",
        ).forEach {
            assertTrue(isServerDrawnPage(it), "$it is drawn per reader, so it must not be cached")
            assertFalse(isStaticAsset(it), "$it was mistaken for a shared static file")
        }
    }

    @Test
    fun `static bytes stay cacheable only with mandatory revalidation`() {
        listOf(
            "/static/css/app.css",
            "/static/css/base.css",
            "/static/js/team.js",
            "/static/js/kanban.js",
            "/static/js/app.js",
            "/static/assets/mms-logo.png",
        ).forEach {
            assertFalse(isServerDrawnPage(it), "$it is the same for every reader")
            assertTrue(isStaticAsset(it), "$it must use the static revalidation policy")
        }
        listOf("/static", "/static/", "/static/assets/favicon.png").forEach {
            assertTrue(isStaticAsset(it), "$it is below the static route")
        }
        listOf("/favicon.ico", "/files/example.png", "/static-other/app.css").forEach {
            assertFalse(isStaticAsset(it), "$it is outside the static route")
        }
    }

    @Test
    fun `every shipped static file is served with no-cache`() = testApplication {
        application {
            install(SecurityHeaders)
            routing { staticResources("/static", "static") }
        }

        val required = listOf("/static/js/team.js", "/static/css/app.css")
        required.forEach { path ->
            assertTrue(File("src/main/resources$path").isFile, "the required test fixture $path is missing")
        }
        val root = File("src/main/resources/static")
        val assets = root.walkTopDown().filter { it.isFile }
            .map { "/static/${it.relativeTo(root).invariantSeparatorsPath}" }
            .sorted()
            .toList()
        assertTrue(assets.containsAll(required), "the fixture inventory omitted a required asset")

        assets.forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            val cacheControl = response.headers[HttpHeaders.CacheControl]
            assertEquals("no-cache", cacheControl, "$path must be revalidated before reuse")
            assertFalse(cacheControl.orEmpty().contains("max-age", ignoreCase = true), "$path is long-lived")
            assertFalse(cacheControl.orEmpty().contains("immutable", ignoreCase = true), "$path is immutable")
            assertSecurityHeaders(response)

            if (path in required) {
                val second = client.get(path)
                assertEquals(HttpStatusCode.OK, second.status, "$path is not reusable")
                assertEquals("no-cache", second.headers[HttpHeaders.CacheControl], "$path lost its policy")
            }
        }
    }

    @Test
    fun `server-drawn pages still send no-store and the unchanged security headers`() = testApplication {
        application {
            install(SecurityHeaders)
            routing {
                get("/app/team") { call.respondText("Team") }
            }
        }

        val response = client.get("/app/team")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertSecurityHeaders(response)
    }

    private fun assertSecurityHeaders(response: HttpResponse) {
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("same-origin", response.headers["Referrer-Policy"])
        assertEquals("geolocation=(), microphone=(), camera=()", response.headers["Permissions-Policy"])
        assertEquals(
            "default-src 'self'; img-src 'self' data:; style-src 'self'; " +
                "style-src-attr 'unsafe-inline'; script-src 'self'; object-src 'none'; " +
                "frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
            response.headers["Content-Security-Policy"],
        )
    }
}

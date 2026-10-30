package mn.murray.pts.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mn.murray.pts.web.plugin.isServerDrawnPage

/**
 * Stage 69 pinned the second half of the fix for "when a card is moved the Team count is not working".
 *
 * The write and the counting were always right; the browser was showing a copy of a page drawn before the
 * move. Two mechanisms do that and each needed its own answer: a page that sits in a tab keeps its old
 * figures until something asks again ([mn.murray.pts.teamScript] re-counts the sheet in place), and a page
 * the browser hands back from its own cache on the Back button never asks the server at all — which is what
 * `Cache-Control: no-store` on the server-drawn pages stops. These tests pin the split between the two kinds
 * of response, because getting it wrong in either direction is a bug: caching a page prints yesterday's
 * numbers, and refusing to cache a stylesheet costs a request per click for bytes that never change.
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
        }
    }

    @Test
    fun `bytes that are the same for every reader stay cacheable`() {
        listOf(
            "/static/css/app.css",
            "/static/css/base.css",
            "/static/js/team.js",
            "/static/js/kanban.js",
            "/static/js/app.js",
            "/static/assets/mms-logo.png",
            "/favicon.ico",
        ).forEach {
            assertFalse(isServerDrawnPage(it), "$it is the same for every reader and should keep its caching")
        }
    }

    /**
     * The header has to be *sent*, not merely decided: the plugin is where the two halves of Stage 69 meet, and
     * an edit that moves the four security lines around can silently drop the one that matters. Read the way the
     * render tests read the stylesheet and the page script — the file on disk is the thing that ships. Positions
     * rather than one big pattern, because what is being pinned is which block the call sits in, not how that
     * block happens to be indented or wrapped.
     */
    @Test
    fun `the security plugin is the one that sends it, and only for those pages`() {
        val source = File("src/main/kotlin/mn/murray/pts/web/plugin/SecurityHeaders.kt")
            .readText().replace("\r\n", "\n")
        val guard = source.indexOf("if (isServerDrawnPage(")
        assertTrue(guard >= 0, "the emission no longer sits behind the page test at all")
        val path = source.indexOf("call.request.path()", guard)
        val sent = source.indexOf("call.response.header(\"Cache-Control\", \"no-store\")", guard)
        assertTrue(path > guard, "the page test is not being run against the requested path")
        assertTrue(sent > guard, "no-store is not sent on the answer the page test guards")
        assertTrue(sent - guard < 160, "no-store is sent somewhere other than inside the block the page test opens")
        assertTrue(source.indexOf('}', sent) - sent < 80, "the block that sends no-store does not close after it")
    }
}

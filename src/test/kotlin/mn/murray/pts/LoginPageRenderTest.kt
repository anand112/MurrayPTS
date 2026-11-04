package mn.murray.pts

import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LoginPageRenderTest {
    @Test
    fun `the username box welcomes lowercase without changing password rules`() {
        val html = createHTML().html { loginPage(error = null) }
        val username = Regex("<input[^>]*name=\"employeeNumber\"[^>]*>").find(html)?.value
        assertNotNull(username, "the login form has no username box")
        assertTrue(username!!.contains("placeholder=\"e.g. emp0009\""))
        assertTrue(username.contains("autocomplete=\"username\""))
        assertTrue(username.contains("autocapitalize=\"none\""))
        assertTrue(username.contains("spellcheck=\"false\""))
        assertFalse(username.contains("pattern="), "browser validation must not demand capital letters")
        assertTrue(html.contains("Employee numbers are not case-sensitive. Passwords are case-sensitive."))
        assertTrue(html.contains("employee number in capitals (e.g. EMP0009)"))
        assertFalse(html.contains("choose a private one before it opens"), "the retired first-login gate is still promised")
        val password = Regex("<input[^>]*name=\"password\"[^>]*>").find(html)?.value
        assertNotNull(password)
        assertTrue(password!!.contains("type=\"password\""))
        assertTrue(password.contains("autocomplete=\"current-password\""))
    }

    @Test
    fun `the password box carries a show button that waits for the script`() {
        val html = createHTML().html { loginPage(error = null) }
        val toggle = Regex("<button[^>]*id=\"passwordToggle\"[^>]*>Show</button>").find(html)?.value
            ?: error("the password box has no show button")
        assertTrue(toggle.contains("type=\"button\""), "the show button would submit the form")
        assertTrue(toggle.contains("class=\"password-toggle\""), "the button is not styled by the login sheet")
        assertTrue(toggle.contains("aria-controls=\"password\""), "the button does not name the box it reveals")
        assertTrue(toggle.contains("aria-pressed=\"false\""), "the password starts hidden, or says nothing")
        assertTrue(toggle.contains("aria-label=\"Show password\""), "the button has no name")
        assertTrue(html.contains("class=\"password-wrap\""), "the button is not pinned inside the input's field")
        assertTrue(html.contains("src=\"/static/js/login.js\""), "the toggle has no script")
        val css = File("src/main/resources/static/css/base.css").readText()
        val block = css.substringAfter(".password-toggle {").substringBefore("}")
        assertTrue(block.contains("display: none;"), "the button would show before the script can work it")
        assertTrue(css.contains("html.js .password-toggle { display: block; }"), "the script cannot reveal the button")
        assertTrue(css.contains(".password-wrap input { padding-right: 74px; }"), "a long password types under the button")
        val js = File("src/main/resources/static/js/login.js").readText()
        listOf(
            "document.documentElement.classList.add('js')",
            "getElementById('password')",
            "getElementById('passwordToggle')",
            "field.type = showing ? 'password' : 'text'",
            "'Hide password'",
            "'Show password'",
            "setAttribute('aria-pressed'",
            "field.focus()",
        ).forEach { piece -> assertTrue(js.contains(piece), "login.js lost \"$piece\"") }
    }

    @Test
    fun `the tab is titled MPTS`() {
        val html = createHTML().html { loginPage(error = null) }
        val title = Regex("<title>(.*?)</title>").find(html)?.groupValues?.get(1)
        assertEquals("MPTS", title, "the tab should carry the short name, not the long system name")
    }

    @Test
    fun `the tab carries the brand icon, named before the stylesheet`() {
        val html = createHTML().html { loginPage(error = null) }
        val icon = Regex("<link[^>]*rel=\"icon\"[^>]*>").find(html)?.value
        assertNotNull(icon, "the head names no tab icon")
        assertTrue(icon!!.contains("type=\"image/png\""), "the icon is the derived PNG, not a browser-guessed path")
        assertTrue(icon.contains("href=\"/static/assets/favicon.png\""), "the icon's href is not the static asset")
        assertTrue(
            html.indexOf("rel=\"icon\"") < html.indexOf("rel=\"stylesheet\""),
            "the icon should be named before the stylesheets, as every other head in the app does",
        )
    }
}

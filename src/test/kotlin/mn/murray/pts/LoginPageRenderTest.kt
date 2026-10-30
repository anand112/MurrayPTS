package mn.murray.pts

import kotlinx.html.html
import kotlinx.html.stream.createHTML
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}

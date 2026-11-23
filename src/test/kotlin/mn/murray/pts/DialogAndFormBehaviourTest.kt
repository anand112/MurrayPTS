package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contract `dialog.js` and `forms.js` keep for every popup and form on an app page. Both are behaviour,
 * so this test pins the pieces a later edit could drop without anything else failing: that every page loads
 * them, that the popups share one close path instead of each script listening for Escape and the backdrop,
 * and that the stylesheet carries the scroll lock, the tokenized exit and the phone bottom sheet.
 */
class DialogAndFormBehaviourTest {
    private fun read(path: String) = File(path).readText()
    private val js = "src/main/resources/static/js"
    private val dialog get() = read("$js/dialog.js")
    private val forms get() = read("$js/forms.js")
    private val css get() = read("src/main/resources/static/css/app.css")

    @Test
    fun `every app page loads the shared dialog and form scripts after app js`() {
        val html = read("src/main/kotlin/mn/murray/pts/DashboardCommon.kt")
        val app = html.indexOf("script(src = \"/static/js/app.js\")")
        val d = html.indexOf("script(src = \"/static/js/dialog.js\")")
        val f = html.indexOf("script(src = \"/static/js/forms.js\")")
        assertTrue(app in 0 until d && d < f, "dialog.js and forms.js must load, in that order, after app.js")
    }

    @Test
    fun `dialog js traps focus, closes one way, locks scroll and hands focus back`() {
        for (piece in listOf(
            "e.key !== 'Tab'", "e.key === 'Escape'", "e.target === modal", "requestClose(",
            "pts-modal-open", "is-closing", "aria-modal", "back.focus(",
        )) assertTrue(dialog.contains(piece), "dialog.js lost \"$piece\"")
    }

    @Test
    fun `no popup script keeps its own Escape or backdrop listener`() {
        val scripts = File(js).listFiles()!!.filter { it.name.endsWith("-modal.js") || it.name == "access-control.js" }
        for (file in scripts) {
            val text = file.readText()
            assertFalse(text.contains("'Escape'"), "${file.name} still closes on Escape itself; dialog.js owns that")
            assertFalse(Regex("e\\.target === (modal|el\\(MODAL\\))").containsMatchIn(text),
                "${file.name} still closes on a backdrop click itself; dialog.js owns that")
        }
    }

    @Test
    fun `forms js shows inline checks, locks the sending button and confirms a save`() {
        for (piece in listOf(
            "addEventListener('invalid'", "data-client-err", "aria-invalid", "aria-describedby",
            "b.disabled = true", "pts:sent", "pageshow", "sessionStorage", "Saved.", "role', 'status'",
        )) assertTrue(forms.contains(piece), "forms.js lost \"$piece\"")
        assertTrue(read("$js/app.js").contains("new CustomEvent('pts:sent'"), "app.js no longer reports a landed send")
    }

    @Test
    fun `forms js never keeps a typed password`() {
        assertTrue(Regex("NO_KEEP = /\\^\\(password\\|").containsMatchIn(forms), "passwords must stay out of sessionStorage")
    }

    @Test
    fun `the stylesheet locks the page, exits on tokens and turns popups into bottom sheets at 520px`() {
        val text = css
        assertTrue("body.pts-modal-open { overflow: hidden; }" in text)
        assertTrue("animation: pts-overlay-out var(--dur-1) var(--ease-std) forwards" in text)
        assertTrue("animation: pts-card-out var(--dur-1) var(--ease-std) forwards" in text)
        val narrow = text.substring(text.indexOf("@media (max-width: 520px) {"))
        assertTrue(Regex("\\.modal > \\.modal-content \\{[^}]*bottom: 0;[^}]*animation-name: pts-sheet-in;").containsMatchIn(narrow),
            "the 520px block no longer makes popups a bottom sheet")
        assertTrue(".modal-actions .submit.danger-btn" in text, "a destructive action is no longer set apart")
        assertTrue("> label::after" in text && "[required]" in text, "required fields lost their visible mark")
    }
}

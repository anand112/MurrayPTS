package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

// This file contains only the login page view.
// Front-end assets are served as external static files (see src/main/resources/static):
//   - /static/css/base.css  — login card + shared design tokens
//   - /static/js/theme.js   — persisted theme/accent toggle
// The old inline PAGE_CSS/THEME_JS constants were removed in Stage 7; the login page
// now references these files via <link>/<script src> so no inline CSS/JS is emitted.

fun HTML.loginPage(error: String?) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title("MURRAY Project Tracking System (PTS)")
        unsafe {
            +("""<link rel="icon" type="image/png" href="/static/assets/favicon.png">""")
            +("""<link rel="stylesheet" href="/static/css/base.css">""")
        }
    }
    body {
        themeToggle()
        div("card") {
            img(src = "/static/assets/mms-logo.png", alt = "MURRAY Mining Services Mongolia") {
                attributes["class"] = "logo-img"
            }
            h1 { +"MURRAY Project Tracking System (PTS)" }
            p("subtitle") { +"Sign in with your employee account" }
            form {
                action = "/login"
                method = FormMethod.post
                if (error != null) {
                    div("error") { +error }
                }
                div("field") {
                    label {
                        htmlFor = "employeeNumber"
                        +"Employee number"
                    }
                    input(type = InputType.text, name = "employeeNumber") {
                        id = "employeeNumber"
                        placeholder = "e.g. emp0009"
                        attributes["autocomplete"] = "username"
                        attributes["autocapitalize"] = "none"
                        attributes["spellcheck"] = "false"
                    }
                }
                div("field") {
                    label {
                        htmlFor = "password"
                        +"Password"
                    }
                    input(type = InputType.password, name = "password") {
                        id = "password"
                        placeholder = "Password"
                        attributes["autocomplete"] = "current-password"
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Log in" }
            }
            p("note") {
                // Two separate text nodes: `+"A" + "B"` would apply binary plus to the result of
                // the first unary plus (Unit), which does not compile.
                +"Employee numbers are not case-sensitive. Passwords are case-sensitive. "
                +"Your starting password, if none was set, is your employee number in capitals (e.g. EMP0009)."
            }
        }
        themeScript()
    }
}

internal fun kotlinx.html.BODY.themeToggle() {
    button(type = ButtonType.button, classes = "theme-toggle") {
        id = "themeToggle"
        attributes["aria-label"] = "Toggle color theme"
    }
}

internal fun kotlinx.html.BODY.themeScript() {
    script(src = "/static/js/theme.js") {}
}

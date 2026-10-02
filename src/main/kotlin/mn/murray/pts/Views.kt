package mn.murray.pts

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
import kotlinx.html.title
import kotlinx.html.unsafe

// This file now contains only the login page view.
// CSS and JavaScript have been extracted to resources:
// - src/main/resources/styles.css
// - src/main/resources/theme.js
// - src/main/resources/logo.json
// Note: To use external CSS/JS files, Ktor static content serving needs to be configured.
// Currently, the constants are kept empty to maintain the inline serving approach.

fun HTML.loginPage(error: String?) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title("MURRAY Project Tracking System (PTS)")
        unsafe { +("<style>$PAGE_CSS</style>") }
    }
    body {
        themeToggle()
        div("card") {
            img(src = "/static/mms-logo.png", alt = "MURRAY Mining Services Mongolia") {
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
                        placeholder = "e.g. EMP0009"
                        attributes["autocomplete"] = "username"
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
                +"Demo system — every account's starting password is its employee number (e.g. EMP0009 / EMP0009)."
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
    unsafe { +("<script>$THEME_JS</script>") }
}

// CSS, Logo SVG, and Theme JavaScript are kept inline for the current Ktor HTML serving approach
// External versions are available in src/main/resources/ for future static file serving

internal const val PAGE_CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
:root {
    --card-bg: #f8fafc;
    --title: #0f172a;
    --muted: #64748b;
    --label: #5b6b7f;
    --input-bg: #eef1f4;
    --input-border: #dfe4ea;
    --input-text: #0f172a;
    --btn-bg: #33500b;
    --btn-bg-hover: #40650e;
    --toggle-bg: #e4e7eb;
    --note: #9aa5b1;
}
html[data-theme="dark"] {
    --card-bg: #17202b;
    --title: #f1f5f9;
    --muted: #94a3b8;
    --label: #9fb0c3;
    --input-bg: #0e141c;
    --input-border: #2c3a4b;
    --input-text: #e2e8f0;
    --btn-bg: #5a8a12;
    --btn-bg-hover: #6ba316;
    --toggle-bg: #243040;
    --note: #7c8a9b;
}
body {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    font-family: "Segoe UI", system-ui, -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif;
    background: radial-gradient(120% 140% at 38% 38%, #4b7108 0%, #33490a 40%, #1b2a06 72%, #101a04 100%);
}
html[data-theme="dark"] body {
    background: radial-gradient(120% 140% at 38% 38%, #22300a 0%, #161f07 45%, #0a0f04 100%);
}
.theme-toggle {
    position: fixed;
    top: 14px;
    right: 14px;
    width: 36px;
    height: 30px;
    border: none;
    border-radius: 8px;
    background: var(--toggle-bg);
    cursor: pointer;
    font-size: 15px;
    line-height: 1;
}
.card {
    width: 100%;
    max-width: 500px;
    background: var(--card-bg);
    border-radius: 14px;
    padding: 44px 48px 36px;
    box-shadow: 0 24px 60px rgba(0, 0, 0, .45);
    text-align: center;
}

.logo { width: 200px; height: auto; display: block; margin: 0 auto 18px; }
.logo-img { width: 280px; max-width: 100%; height: auto; display: block; margin: 0 auto 18px; }
h1 { font-size: 21px; font-weight: 800; color: var(--title); margin-bottom: 8px; }
.subtitle { font-size: 13.5px; color: var(--muted); margin-bottom: 30px; }
form { text-align: left; }
.field { margin-bottom: 18px; }
label {
    display: block;
    margin-bottom: 7px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: .07em;
    text-transform: uppercase;
    color: var(--label);
}
input {
    width: 100%;
    padding: 12px 14px;
    font-size: 14px;
    font-family: inherit;
    color: var(--input-text);
    background: var(--input-bg);
    border: 1px solid var(--input-border);
    border-radius: 8px;
    outline: none;
}
input:focus { border-color: #6b9c1e; box-shadow: 0 0 0 3px rgba(107, 156, 30, .25); }
input::placeholder { color: #9aa5b1; }
button.submit, a.submit {
    display: block;
    width: 100%;
    margin-top: 8px;
    padding: 13px;
    border: none;
    border-radius: 8px;
    background: var(--btn-bg);
    color: #fff;
    font-size: 15px;
    font-weight: 700;
    font-family: inherit;
    text-align: center;
    text-decoration: none;
    cursor: pointer;
}
button.submit:hover, a.submit:hover { background: var(--btn-bg-hover); }
.error {
    margin-bottom: 16px;
    padding: 10px 12px;
    border: 1px solid #f5c6c0;
    border-radius: 8px;
    background: #fdecea;
    color: #b3261e;
    font-size: 13px;
}
.note { margin-top: 22px; font-size: 11.5px; line-height: 1.6; color: var(--note); }
"""

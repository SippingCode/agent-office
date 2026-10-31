package com.agentoffice.ui

import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * The office page. It embeds the very same `office.js` and `office.css` as the VS Code extension
 * (copied from ../media at build time), plus a small shim that replaces `acquireVsCodeApi()`.
 * Everything is inline: no network, no external resource.
 */
object OfficeHtml {

    private fun resource(name: String): String =
        OfficeHtml::class.java.getResourceAsStream("/webview/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing resource /webview/$name")

    private fun hex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    /** CSS custom properties that `office.css` expects from VS Code, filled from the IDE theme. */
    fun themeVariables(): Map<String, String> {
        val font = JBUI.Fonts.label()
        val background = UIUtil.getPanelBackground()
        return linkedMapOf(
            "--vscode-font-family" to "'${font.family.replace("'", "")}', system-ui, sans-serif",
            "--vscode-font-size" to "${font.size}px",
            "--vscode-foreground" to hex(UIUtil.getLabelForeground()),
            "--vscode-descriptionForeground" to hex(UIUtil.getContextHelpForeground()),
            "--vscode-panel-background" to hex(background),
            "--vscode-editor-background" to hex(background),
        )
    }

    private fun themeCss(): String =
        ":root {" + themeVariables().entries.joinToString(" ") { "${it.key}: ${it.value};" } + "}"

    /** JavaScript that re-applies the theme variables on an already loaded page. */
    fun themeScript(): String =
        themeVariables().entries.joinToString("") { (k, v) ->
            "document.documentElement.style.setProperty('${k}', ${jsString(v)});"
        }

    fun jsString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("</", "<\\/") + "'"

    /**
     * @param sendToIde JavaScript statement that forwards the `msg` variable (a JSON string) to the IDE,
     *                  typically [JBCefJSQuery.inject] applied to `"msg"`.
     */
    fun page(sendToIde: String): String {
        val css = resource("office.css")
        val js = resource("office.js").replace("</script", "<\\/script")
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Agent Office</title>
  <style>${themeCss()}</style>
  <style>$css</style>
  <style>html, body { overflow-x: hidden; } body { padding-bottom: 16px; }</style>
</head>
<body>
  <header id="banner" class="banner banner--unknown" role="status" aria-live="polite">
    <span class="banner__dot" aria-hidden="true"></span>
    <span id="banner-text">Reading agent status…</span>
  </header>
  <main class="stage">
    <canvas id="office" width="368" height="208" aria-hidden="true"></canvas>
  </main>
  <ul id="roster" class="roster" aria-label="Agent desks"></ul>
  <footer id="footer" class="footer"></footer>
  <script>
    // Shim: the office script was written for a VS Code Webview. Messages go to the IDE through JCEF.
    window.acquireVsCodeApi = function () {
      return {
        postMessage: function (message) {
          var msg = JSON.stringify(message);
          $sendToIde
        },
        getState: function () { return undefined; },
        setState: function () {}
      };
    };
    // The IDE pushes snapshots with window.postMessage(data, '*'); office.js listens to 'message' events.
  </script>
  <script>$js</script>
</body>
</html>"""
    }
}

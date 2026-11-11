package org.rg.reporter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rg.reporter.model.ReportModels.ReportData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the single-file index.html: UI assets and report data are inlined. */
final class HtmlReportWriter {

    private static final String UI_DIR = "/modern-reporter/ui/";
    // Runs in <head> so the saved theme is applied before first paint.
    private static final String THEME_BOOT =
            "try{var t=JSON.parse(localStorage.getItem('pw-report:theme'));"
                    + "if(t!=='dark'&&t!=='light'){t=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}"
                    + "document.documentElement.setAttribute('data-theme',t);}catch(e){}";

    private HtmlReportWriter() {
    }

    static String build(ReportData data, ObjectMapper mapper) {
        String css = asset("styles.css", "style");
        String js = asset("app.js", "script");
        return """
                <!DOCTYPE html>
                <html lang="en" data-theme="light">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark">
                <title>%s</title>
                <link rel="icon" href="data:,">
                <script>%s</script>
                <style>%s</style>
                </head>
                <body>
                <div class="app" id="app">
                  <aside class="sidebar" aria-label="Navigation">
                    <div class="brand">
                      <div class="logo" data-icon="logo"></div>
                      <div class="brand-text"><div class="brand-title" id="brand-title"></div><div class="brand-sub" id="brand-sub"></div></div>
                    </div>
                    <nav class="nav" id="nav"></nav>
                    <div class="sb-foot" id="sb-foot"></div>
                  </aside>
                  <div class="main">
                    <header class="topbar">
                      <button type="button" class="ibtn menu-btn" data-action="toggle-sidebar" data-icon="menu" aria-label="Menu"></button>
                      <label class="search" data-icon="search"><input type="search" id="search" placeholder="Search tests, files, tags, errors…" autocomplete="off" spellcheck="false" aria-label="Search"><kbd>/</kbd></label>
                      <div class="filters" id="filters"></div>
                      <select class="select" id="tag-filter" aria-label="Filter by tag"></select>
                      <div class="topbar-actions">
                        <button type="button" class="ibtn" data-action="expand-all" data-icon="expand" data-tip="Expand all" aria-label="Expand all"></button>
                        <button type="button" class="ibtn" data-action="collapse-all" data-icon="collapse" data-tip="Collapse all" aria-label="Collapse all"></button>
                        <button type="button" class="ibtn" data-action="export-csv" data-icon="download" data-tip="Export filtered results (CSV)" aria-label="Export CSV"></button>
                        <button type="button" class="ibtn" id="theme-btn" data-action="toggle-theme" data-tip="Toggle theme (t)" aria-label="Toggle theme"></button>
                      </div>
                    </header>
                    <div class="banner" id="banner" hidden></div>
                    <main class="content" id="content"></main>
                  </div>
                  <aside class="drawer" id="drawer" aria-label="Test details">
                    <div class="drawer-resize" id="drawer-resize" title="Drag to resize"></div>
                    <div class="drawer-inner" id="drawer-inner"></div>
                  </aside>
                </div>
                <div class="lightbox" id="lightbox" data-action="close-lightbox" hidden>
                  <div class="lb-bar"><span id="lb-caption"></span><span class="grow"></span><button type="button" class="lb-btn" data-action="close-lightbox" data-icon="close" aria-label="Close"></button></div>
                  <button type="button" class="lb-btn lb-prev" id="lb-prev" data-action="lb-prev" data-icon="left" aria-label="Previous image"></button>
                  <img class="lb-img" id="lb-img" alt="" data-action="noop">
                  <button type="button" class="lb-btn lb-next" id="lb-next" data-action="lb-next" data-icon="chevron" aria-label="Next image"></button>
                </div>
                <div class="tooltip" id="tooltip" role="tooltip" hidden></div>
                <div class="toasts" id="toasts" aria-live="polite"></div>
                <script type="application/json" id="report-data">%s</script>
                <script>%s</script>
                </body>
                </html>
                """.formatted(escapeHtml(data.title), THEME_BOOT, css, serializeForScript(data, mapper), js);
    }

    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** JSON that is safe to embed inside a script element. */
    private static String serializeForScript(ReportData data, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(data)
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("&", "\\u0026")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise report data", e);
        }
    }

    private static String asset(String name, String closingTag) {
        try (InputStream in = HtmlReportWriter.class.getResourceAsStream(UI_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("Report UI asset missing from classpath: " + UI_DIR + name);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Keep the asset from terminating its own inline <style>/<script> element.
            return Pattern.compile("</" + closingTag, Pattern.CASE_INSENSITIVE).matcher(content)
                    .replaceAll(Matcher.quoteReplacement("<\\/" + closingTag));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

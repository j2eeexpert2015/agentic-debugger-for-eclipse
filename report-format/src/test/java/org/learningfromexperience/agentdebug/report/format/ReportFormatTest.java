package org.learningfromexperience.agentdebug.report.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for {@link ReportFormat} (RF-U1 to RF-U4, OPEN-36 delivery 3). RF-U1 and RF-U2 are the former
 * report tests RC-U4 and RC-U6, moved here with the code, unchanged. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ReportFormatTest {

    @Test
    @DisplayName("RF-U1 HTML escaping of report text, backticks shown as code")
    void rfU1() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39; &#8364;", ReportFormat.html("a <b> & \"c\" 'd' €"));
        assertEquals("", ReportFormat.html(null));
        // Backticks in the agent's conclusion become code; text without a closing backtick stays as it is.
        assertEquals("line 23: <code>int rate = percent / 100</code> is integer division",
                ReportFormat.codeSpans(ReportFormat.html("line 23: `int rate = percent / 100` is integer division")));
        assertEquals("a ` b", ReportFormat.codeSpans("a ` b"));
    }

    @Test
    @DisplayName("RF-U2 the agent's Markdown analysis is rendered: headings, paragraphs, lists, tables, code, bold; text stays escaped")
    void rfU2() {
        String analysis = String.join("\n",
                "## Root cause",
                "Line 23 computes `int rate = percent / 100`, which is **integer division**.",
                "So the discount is never applied.",
                "",
                "- percent = 10",
                "- rate = 0",
                "",
                "1. Stop at line 24",
                "2. Read the locals",
                "",
                "| Variable | Value |",
                "|---|---|",
                "| `rate` | **0** |",
                "",
                "```java",
                "int rate = percent / 100; // <0>",
                "```",
                "<script>alert(1)</script>");
        String html = ReportFormat.markdown(analysis);
        assertEquals("<div class=\"ad-md\">"
                + "<div class=\"ad-h\">Root cause</div>"
                + "<p>Line 23 computes <code>int rate = percent / 100</code>, which is <strong>integer division</strong>. "
                + "So the discount is never applied.</p>"
                + "<ul><li>percent = 10</li><li>rate = 0</li></ul>"
                + "<ol><li>Stop at line 24</li><li>Read the locals</li></ol>"
                + "<table><tr><th>Variable</th><th>Value</th></tr><tr><td><code>rate</code></td><td><strong>0</strong></td></tr></table>"
                + "<code class=\"ad-block\">int rate = percent / 100; // &lt;0&gt;</code>"
                + "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
                + "</div>", html);
        assertEquals("<div class=\"ad-md\"><p>Plain text only.</p></div>", ReportFormat.markdown("Plain text only."));
        assertEquals("<div class=\"ad-md\"></div>", ReportFormat.markdown(null));
    }

    @Test
    @DisplayName("RF-U3 one colour per step kind and per status, as the report had them")
    void rfU3() {
        assertEquals(List.of("LAUNCH", "BREAKPOINT", "STOP", "STEP", "INSPECT", "HTTP", "EXCEPTION", "CLEANUP", "OTHER"),
                ReportFormat.KINDS);
        // Exactly the rules the report's stylesheet had before the move, in the same order.
        assertEquals(".card:has(.ad-k-launch){--k:#0d6efd}.card:has(.ad-k-breakpoint){--k:#6f42c1}.card:has(.ad-k-stop){--k:#fd7e14}"
                + ".card:has(.ad-k-step){--k:#0aa2c0}.card:has(.ad-k-inspect){--k:#20a07a}.card:has(.ad-k-http){--k:#d63384}"
                + ".card:has(.ad-k-exception){--k:#dc3545}.card:has(.ad-k-cleanup){--k:#198754}.card:has(.ad-k-other){--k:#6c757d}",
                ReportFormat.kindCss(".card:has(.%s){--k:%s}"));
        assertEquals("#6f42c1", ReportFormat.kindColour("breakpoint"));
        assertEquals(ReportFormat.NEUTRAL, ReportFormat.kindColour("SOMETHING_NEW"));
        assertEquals(ReportFormat.NEUTRAL, ReportFormat.kindColour(null));
        assertEquals("ad-k-other", ReportFormat.kindClass(null));
        assertEquals(ReportFormat.RUNNING, ReportFormat.statusColour("RUNNING"));
        assertEquals(ReportFormat.OK, ReportFormat.statusColour("OK"));
        assertEquals(ReportFormat.REFUSED, ReportFormat.statusColour("FAILED"), "a refused tool call is amber, not red");
        assertEquals(ReportFormat.NEUTRAL, ReportFormat.statusColour("UNKNOWN"));
        assertTrue(ReportFormat.MARKDOWN_CSS.startsWith(".ad-md code{"));
    }

    @Test
    @DisplayName("RF-U4 step details are split into reader lines and technical lines")
    void rfU4() {
        String sep = ReportFormat.TECHNICAL_DETAILS;
        assertEquals(new ReportFormat.Details(List.of("a", "b"), List.of("tool: x", "raw")),
                ReportFormat.split(List.of("a", "b", sep, "tool: x", "raw")));
        assertEquals(new ReportFormat.Details(List.of("a"), List.of()), ReportFormat.split(List.of("a")));
        assertEquals(new ReportFormat.Details(List.of("a"), List.of()), ReportFormat.split(List.of("a", sep)),
                "a separator with nothing after it is dropped");
        assertEquals(new ReportFormat.Details(List.of(), List.of()), ReportFormat.split(null));
        assertEquals("Additional Details", ReportFormat.MORE_DETAILS_LABEL, "the label of the collapsed part, report and view alike");
    }
}

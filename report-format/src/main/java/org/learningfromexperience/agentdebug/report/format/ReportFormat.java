package org.learningfromexperience.agentdebug.report.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Presentation pieces shared by the debugging session report (report-chaintest) and the Agent Activity view
 * (eclipse-plugin), so both show the same thing the same way (OPEN-36, requirement B1):
 * <ul>
 *   <li>{@link #html(String)}: HTML escaping;</li>
 *   <li>{@link #markdown(String)} and {@link #MARKDOWN_CSS}: the agent's Markdown analysis as HTML;</li>
 *   <li>{@link #kindColour(String)}, {@link #kindCss(String)}: one colour per step kind;</li>
 *   <li>{@link #statusColour(String)}: one colour per step status (running, OK, refused, unknown);</li>
 *   <li>{@link #split(List)}: step details split into reader lines and technical details.</li>
 * </ul>
 * Step kinds and statuses are passed by name ({@code "BREAKPOINT"}, {@code "OK"}), because this module depends on
 * nothing: the names are those of {@code ReportEvent.StepKind} and {@code ReportEvent.StepStatus} in report-model,
 * which a test in report-chaintest keeps in line with {@link #KINDS} and {@link #TECHNICAL_DETAILS}.
 *
 * @author Ayan Dutta
 */
public final class ReportFormat {

    /** The line that separates reader lines from technical lines in a step's details; same as in report-model. */
    public static final String TECHNICAL_DETAILS = "--- technical details ---";

    /** Colour per step kind, in the order of {@code ReportEvent.StepKind}. */
    private static final Map<String, String> KIND_COLOURS = new LinkedHashMap<>();

    static {
        KIND_COLOURS.put("LAUNCH", "#0d6efd");
        KIND_COLOURS.put("BREAKPOINT", "#6f42c1");
        KIND_COLOURS.put("STOP", "#fd7e14");
        KIND_COLOURS.put("STEP", "#0aa2c0");
        KIND_COLOURS.put("INSPECT", "#20a07a");
        KIND_COLOURS.put("HTTP", "#d63384");
        KIND_COLOURS.put("EXCEPTION", "#dc3545");
        KIND_COLOURS.put("CLEANUP", "#198754");
        KIND_COLOURS.put("OTHER", "#6c757d");
    }

    /** The step kinds with a colour, in order. */
    public static final List<String> KINDS = List.copyOf(KIND_COLOURS.keySet());

    /** Running now (blue), done (green), refused tool call (amber: the agent retried, not an application error). */
    public static final String RUNNING = "#0d6efd", OK = "#198754", REFUSED = "#e08a00", ERROR = "#dc3545",
            NEUTRAL = "#6c757d";

    /** Styles for the HTML that {@link #markdown(String)} produces. */
    public static final String MARKDOWN_CSS =
            ".ad-md code{color:#a3005b;background:rgba(0,0,0,.06);padding:0 .25rem;border-radius:.25rem}"
            + ".ad-md code.ad-block{display:block;white-space:pre-wrap;color:inherit;padding:.5rem;margin:.25rem 0 .5rem}"
            + ".ad-md p,.ad-md ul,.ad-md ol,.ad-md table{margin:0 0 .6rem}.ad-md .ad-h{margin:.6rem 0 .3rem;font-weight:600;color:#146c43}"
            + ".ad-md table{border-collapse:collapse}.ad-md td,.ad-md th{border:1px solid #c8d1cc;padding:.15rem .6rem}"
            + ".ad-md th{background:rgba(25,135,84,.12)}";

    private ReportFormat() {
    }

    /** The colour of a step kind; grey for an unknown or missing kind. */
    public static String kindColour(String kind) {
        return kind == null ? NEUTRAL : KIND_COLOURS.getOrDefault(kind.toUpperCase(Locale.ROOT), NEUTRAL);
    }

    /** CSS class of a step kind: {@code ad-k-breakpoint}. */
    public static String kindClass(String kind) {
        return "ad-k-" + (kind == null ? "other" : kind.toLowerCase(Locale.ROOT));
    }

    /**
     * One CSS rule per step kind from a pattern with {@code %s} for the kind's class and {@code %s} for its colour:
     * {@code kindCss(".card:has(.%s){--k:%s}")} gives {@code .card:has(.ad-k-launch){--k:#0d6efd}...}.
     */
    public static String kindCss(String pattern) {
        StringBuilder css = new StringBuilder();
        KIND_COLOURS.forEach((kind, colour) -> css.append(String.format(Locale.ROOT, pattern, kindClass(kind), colour)));
        return css.toString();
    }

    /**
     * The colour of a step status: {@code RUNNING} blue, {@code OK} green, {@code FAILED} amber (a refused tool call),
     * {@code ERROR} red; anything else ({@code INFO}, {@code UNKNOWN}) grey.
     */
    public static String statusColour(String status) {
        if (status == null) return NEUTRAL;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "RUNNING" -> RUNNING;
            case "OK" -> OK;
            case "FAILED" -> REFUSED;
            case "ERROR" -> ERROR;
            default -> NEUTRAL;
        };
    }

    /** A step's details: lines for the reader, and the technical lines (tool, arguments, raw result) after them. */
    public record Details(List<String> reader, List<String> technical) {
    }

    /**
     * Splits a step's details at {@link #TECHNICAL_DETAILS}: the lines before it are for the reader, the lines after
     * it are technical. Without the separator every line is for the reader; a separator with nothing after it is
     * dropped.
     */
    public static Details split(List<String> details) {
        List<String> all = details == null ? List.of() : details;
        int at = all.indexOf(TECHNICAL_DETAILS);
        if (at < 0) return new Details(List.copyOf(all), List.of());
        return new Details(List.copyOf(all.subList(0, at)), List.copyOf(all.subList(at + 1, all.size())));
    }

    /**
     * The agent's analysis as HTML. Agents write Markdown, so the common parts are rendered: headings, paragraphs,
     * bullet and numbered lists, tables, fenced code blocks, {@code **bold**} and {@code `code`}. Everything else is
     * shown as text. All text is escaped first ({@link #html(String)}); only the tags produced here are HTML. The
     * result has no line breaks between blocks, because the report puts it inside a {@code <pre>} element.
     */
    public static String markdown(String text) {
        StringBuilder out = new StringBuilder("<div class=\"ad-md\">");
        List<String> paragraph = new ArrayList<>();
        String list = null;            // "ul" or "ol" while a list is open
        boolean table = false, tableHead = false, code = false;
        StringBuilder codeText = new StringBuilder();
        for (String raw : (text == null ? "" : text).replace("\r", "").split("\n", -1)) {
            String line = raw.strip();
            if (code) {
                if (line.startsWith("```")) {
                    out.append("<code class=\"ad-block\">").append(html(codeText.toString().stripTrailing())).append("</code>");
                    codeText.setLength(0);
                    code = false;
                } else {
                    codeText.append(raw).append('\n');
                }
                continue;
            }
            boolean isTable = line.startsWith("|") && line.endsWith("|") && line.length() > 1;
            boolean isBullet = line.matches("[-*] .*");
            boolean isNumbered = line.matches("\\d+[.)] .*");
            boolean isHeading = line.matches("#{1,6} .*");
            boolean isFence = line.startsWith("```");
            if (!paragraph.isEmpty() && (line.isEmpty() || isTable || isBullet || isNumbered || isHeading || isFence)) {
                out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
                paragraph.clear();
            }
            if (list != null && !(list.equals("ul") ? isBullet : isNumbered)) {
                out.append("</").append(list).append(">");
                list = null;
            }
            if (table && !isTable) {
                out.append("</table>");
                table = false;
            }
            if (line.isEmpty()) continue;
            if (isFence) {
                code = true;
            } else if (isHeading) {
                out.append("<div class=\"ad-h\">").append(inline(line.replaceFirst("#+ ", ""))).append("</div>");
            } else if (isBullet || isNumbered) {
                String kind = isBullet ? "ul" : "ol";
                if (list == null) {
                    out.append("<").append(kind).append(">");
                    list = kind;
                }
                out.append("<li>").append(inline(line.replaceFirst(isBullet ? "[-*] " : "\\d+[.)] ", ""))).append("</li>");
            } else if (isTable) {
                if (line.matches("\\|[\\s:|-]+\\|")) continue;      // the |---|---| line under the header
                if (!table) {
                    out.append("<table>");
                    table = true;
                    tableHead = true;
                }
                String cell = tableHead ? "th" : "td";
                out.append("<tr>");
                for (String value : line.substring(1, line.length() - 1).split("\\|", -1))
                    out.append("<").append(cell).append(">").append(inline(value.strip())).append("</").append(cell).append(">");
                out.append("</tr>");
                tableHead = false;
            } else {
                paragraph.add(line);
            }
        }
        if (code) out.append("<code class=\"ad-block\">").append(html(codeText.toString().stripTrailing())).append("</code>");
        if (!paragraph.isEmpty()) out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
        if (list != null) out.append("</").append(list).append(">");
        if (table) out.append("</table>");
        return out.append("</div>").toString();
    }

    /** Escaped text with {@code `code`} and {@code **bold**}. */
    private static String inline(String text) {
        return codeSpans(html(text)).replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
    }

    /** Text the agent marked as code with backticks ({@code `int rate = percent / 100`}) is shown as code. */
    public static String codeSpans(String escaped) {
        return escaped.replaceAll("`([^`\n]+)`", "<code>$1</code>");
    }

    /**
     * Escapes HTML characters, and writes every non-ASCII character as a numeric entity, so the text is safe in a
     * page of any encoding (ChainTest writes its page in the platform's default encoding).
     */
    public static String html(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c > 126) out.append("&#").append(c).append(';');
                    else out.appendCodePoint(c);
                }
            }
        });
        return out.toString();
    }
}

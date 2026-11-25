package org.learningfromexperience.agentdebug.report.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * The program flow of the HTML report (OPEN-30 R4, phase 1): where the program went, in order, built only from what
 * the tools recorded. Plain Java: unit-tested without a workbench.
 *
 * <p><b>Nodes.</b> The steps are grouped into nodes, one per place the program was seen:
 * <ul>
 *   <li><b>Preparation</b>: breakpoints set and the launch, before the first HTTP exchange or stop;</li>
 *   <li><b>HTTP request</b> and <b>HTTP response</b>: one node each;</li>
 *   <li><b>Stop</b>: one per stop (breakpoint, step end, exception, pause); the agent's calls while the program stood
 *       there (variables read, objects expanded, expressions evaluated, the resume or step) belong to it, so side
 *       trips no longer fill the page;</li>
 *   <li><b>No stop</b>: the program ended, or the wait ended, without a stop; also added when breakpoints were set but
 *       nothing ever stopped;</li>
 *   <li><b>Never hit</b>: a breakpoint that was set but never stopped the program (OPEN-37);</li>
 *   <li><b>Cleanup</b>; and <b>From the agent</b>, the agent's analysis, the only node that is not recorded data.</li>
 * </ul>
 * A refused call stays with the node it was made at and is marked there.
 *
 * <p><b>Per node</b> the report shows what was recorded there: for a stop the location, the call chain, the thread,
 * the frame's variables (values that changed since the previous stop marked with their previous value), expanded
 * objects folded, the expressions evaluated with their results, the movement from the previous stop in the same
 * method ("line 24 → 25"), and the calls as chips (tool and how often), with every call's full details folded.
 *
 * <p><b>Phase 2.</b> {@link Node#purpose()} (why the agent stopped here) and {@link Node#finding()} (what it learned
 * here) are the places for the agent's own statements, given at the finish (see docs/design/flow-report.md). In
 * phase 1 they are always null; the page already renders them, tagged FROM THE AGENT, when present.
 *
 * <p>Every text from the agent or the program is escaped.
 *
 * @author Ayan Dutta
 */
final class FlowPage {

    /** What a node stands for. */
    enum Kind { PREP, REQUEST, RESPONSE, STOP, NOSTOP, NEVER, CLEAN, AGENT }

    /** One call, or several identical ones, at a node: the tool, what it was about (path, action), how often. */
    record Call(String tool, String about, int count, boolean refused) {
        String label() {
            return tool + (about == null ? "" : " " + about) + (count > 1 ? " ×" + count : "");
        }
    }

    /** An expression the agent evaluated and its result (or why it failed). */
    record Expression(String expression, String result, boolean failed) {
    }

    /** An object the agent expanded at a stop: its path, how often, and the fields read (in order, without repeats). */
    record Expansion(String path, int count, List<ActivityPage.Variable> fields) {
    }

    /**
     * One node of the flow. {@code steps} are the indexes into {@code inv.steps()}; {@code where}, {@code frames},
     * {@code thread}, {@code variables}, {@code expansions}, {@code expressions} and {@code movement} are set for
     * stops; {@code lines} are the reader lines shown for other nodes; {@code purpose} and {@code finding} are the
     * agent's statements (phase 2; null in phase 1).
     */
    record Node(Kind kind, String name, String sub, List<Integer> steps, String where, String frames, String thread,
                List<ActivityPage.Variable> variables, List<Expansion> expansions, List<Expression> expressions,
                List<Call> calls, String movement, List<String> lines, boolean breakpointHit, String purpose,
                String finding) {
        int refused() {
            int n = 0;
            for (Call c : calls) if (c.refused()) n += c.count();
            return n;
        }
    }

    /** "Set breakpoint: OrderService, line 24" (also conditional; tracepoints never stop, so they are left out). */
    private static final Pattern BREAKPOINT = Pattern.compile("^Set (?:conditional )?breakpoint: (.+?), line (\\d+)");
    /** "OrderService.quote:24": group 1 the class, 2 the method, 3 the line. */
    private static final Pattern FRAME = Pattern.compile("^(.+)\\.([^.:]+):(\\d+)$");

    private FlowPage() {
    }

    // ---- grouping ---------------------------------------------------------------------------------------------

    /** The nodes of an investigation, in the order the program went through them. */
    static List<Node> nodes(ActivityStore.Investigation inv) {
        List<ActivityStore.Step> steps = inv.steps();
        List<List<Integer>> groups = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ActivityStore.Step step = steps.get(i);
            Kind current = kinds.isEmpty() ? null : kinds.get(kinds.size() - 1);
            Kind starts = starts(step, current);
            if (starts != null) {
                kinds.add(starts);
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(i);
        }
        List<Node> nodes = new ArrayList<>();
        Node previousStop = null;
        for (int g = 0; g < groups.size(); g++) {
            Node node = node(inv, kinds.get(g), groups.get(g), previousStop);
            if (node.kind() == Kind.STOP) previousStop = node;
            nodes.add(node);
        }
        // Breakpoints set but never hit, and "no stop" when nothing stopped at all: before the cleanup.
        int at = nodes.size();
        while (at > 0 && nodes.get(at - 1).kind() == Kind.CLEAN) at--;
        // Only once the investigation is over: while it runs, a breakpoint not hit yet may still be hit.
        if (inv.finished()) nodes.addAll(at, missed(inv, nodes));
        if (inv.conclusion() != null && !inv.conclusion().isBlank())
            nodes.add(new Node(Kind.AGENT, "From the agent", firstLine(inv.conclusion()), List.of(), null, null, null,
                    List.of(), List.of(), List.of(), List.of(), null, List.of(), false, null, null));
        return nodes;
    }

    /** The kind of node a step starts, or null when it belongs to the current node. */
    private static Kind starts(ActivityStore.Step step, Kind current) {
        String kind = step.kind() == null ? "" : step.kind();
        String title = step.title() == null ? "" : step.title();
        boolean ok = !"FAILED".equals(step.status()) && !step.running();
        if (ActivityPage.isStop(step)) return Kind.STOP;
        if ("STOP".equals(kind) && ok) return Kind.NOSTOP;
        if ("HTTP".equals(kind) && ok) return title.startsWith("Received") ? Kind.RESPONSE : Kind.REQUEST;
        if ("CLEANUP".equals(kind) && current != Kind.CLEAN) return Kind.CLEAN;
        return current == null ? Kind.PREP : null;
    }

    private static Node node(ActivityStore.Investigation inv, Kind kind, List<Integer> indexes, Node previousStop) {
        List<ActivityStore.Step> steps = new ArrayList<>();
        for (int i : indexes) steps.add(inv.steps().get(i));
        ActivityStore.Step first = steps.get(0);
        List<Call> calls = calls(steps);
        List<String> lines = new ArrayList<>();
        return switch (kind) {
            case STOP -> stopNode(first, indexes, steps, calls, previousStop);
            case REQUEST -> {
                for (String line : reader(first)) if (!line.startsWith("Request id: ")) lines.add(line);
                String body = valueOf(lines, "Body sent: ");
                yield new Node(kind, after(first.title(), ": "), body == null ? "" : body, indexes, null, null, null,
                        List.of(), List.of(), List.of(), calls, null, lines, false, null, null);
            }
            case RESPONSE -> {
                lines.addAll(reader(first));
                String body = valueOf(lines, "Body: ");
                String status = after(first.title(), ": ");
                yield new Node(kind, "HTTP " + status + (body == null ? "" : " " + body),
                        "the response to the request", indexes, null, null, null, List.of(), List.of(), List.of(),
                        calls, null, lines, false, null, null);
            }
            case CLEAN -> {
                for (ActivityStore.Step s : steps) lines.addAll(reader(s));
                List<String> shown = new ArrayList<>();
                for (String line : lines)
                    if (line.startsWith("Removed own") || line.startsWith("Kept own") || line.startsWith("JVM ended")
                            || line.startsWith("Warning")) shown.add(line);
                String name = first.title() != null && first.title().startsWith("Cleaned up automatically")
                        ? "Cleaned up automatically" : "Cleaned up";
                yield new Node(kind, name, String.join(" · ", shown), indexes, null, null, null, List.of(), List.of(),
                        List.of(), calls, null, lines, false, null, null);
            }
            case NOSTOP -> {
                lines.addAll(reader(first));
                yield new Node(kind, first.title(), "the program did not stop at any breakpoint here", indexes, null,
                        null, null, List.of(), List.of(), List.of(), calls, null, lines, false, null, null);
            }
            default -> {
                List<String> done = new ArrayList<>();
                for (ActivityStore.Step s : steps) {
                    if ("FAILED".equals(s.status()) || s.title() == null) continue;
                    Matcher bp = BREAKPOINT.matcher(s.title());
                    if (bp.find()) done.add("breakpoint " + bp.group(1) + ":" + bp.group(2));
                    else if (s.title().startsWith("Started application in debug mode: "))
                        done.add(after(s.title(), ": ") + " started in debug mode");
                    else done.add(s.title());
                }
                yield new Node(Kind.PREP, "Preparation", String.join(" · ", new LinkedHashSet<>(done)), indexes, null,
                        null, null, List.of(), List.of(), List.of(), calls, null, List.of(), false, null, null);
            }
        };
    }

    private static Node stopNode(ActivityStore.Step stop, List<Integer> indexes, List<ActivityStore.Step> steps,
                                 List<Call> calls, Node previousStop) {
        String frames = null, thread = null;
        for (String line : reader(stop)) {
            if (line.startsWith("Frames: ")) frames = line.substring("Frames: ".length());
            if (line.startsWith("Thread: ")) thread = line.substring("Thread: ".length());
        }
        // The call chain from a later "Read call stack" at this stop, when the stop itself had none.
        for (int i = 1; i < steps.size() && frames == null; i++) {
            List<String> chain = new ArrayList<>();
            for (String line : reader(steps.get(i))) {
                Matcher m = FRAME_LINE.matcher(line);
                if (m.matches()) chain.add(m.group(1));
            }
            if (!chain.isEmpty()) frames = String.join("  <  ", chain);
        }
        String where = frames != null ? frames.split("\\s+<\\s+")[0].strip() : where(stop.title());
        // The frame's variables: the latest table at this stop (a later read replaces the stop's own).
        List<ActivityPage.Variable> table = List.of();
        for (ActivityStore.Step s : steps) {
            List<ActivityPage.Variable> t = ActivityPage.frameVariables(s);
            if (!t.isEmpty()) table = t;
        }
        Map<String, String> before = new LinkedHashMap<>();
        if (previousStop != null) for (ActivityPage.Variable v : previousStop.variables()) before.put(v.name(), v.value());
        List<ActivityPage.Variable> variables = new ArrayList<>();
        for (ActivityPage.Variable v : table) {
            String was = before.get(v.name());
            variables.add(new ActivityPage.Variable(v.name(), v.type(), v.value(),
                    was != null && !was.equals(v.value()) ? was : null));
        }
        // Expanded objects, folded per path; expressions with their results.
        Map<String, List<ActivityPage.Variable>> fields = new LinkedHashMap<>();
        Map<String, Integer> times = new LinkedHashMap<>();
        List<Expression> expressions = new ArrayList<>();
        for (ActivityStore.Step s : steps) {
            String title = s.title() == null ? "" : s.title();
            if (s.running()) continue;   // no result yet; the live view shows it as the running call
            if (title.startsWith("Expanded variable: ") && !"FAILED".equals(s.status())) {
                String path = after(title, ": ");
                times.merge(path, 1, Integer::sum);
                List<ActivityPage.Variable> list = fields.computeIfAbsent(path, p -> new ArrayList<>());
                Set<String> seen = new LinkedHashSet<>();
                for (ActivityPage.Variable f : list) seen.add(f.name() + "\n" + f.value());
                for (ActivityPage.Variable f : ActivityPage.variables(s))
                    if (seen.add(f.name() + "\n" + f.value())) list.add(f);
            } else if (title.startsWith("Evaluated expression: ")) {
                boolean failed = "FAILED".equals(s.status());
                expressions.add(new Expression(after(title, ": "), result(s, failed), failed));
            }
        }
        List<Expansion> expansions = new ArrayList<>();
        for (var e : fields.entrySet()) expansions.add(new Expansion(e.getKey(), times.get(e.getKey()), List.copyOf(e.getValue())));
        String movement = null;
        if (previousStop != null && previousStop.where() != null && where != null) {
            Matcher a = FRAME.matcher(previousStop.where()), b = FRAME.matcher(where);
            if (a.matches() && b.matches() && a.group(1).equals(b.group(1)) && a.group(2).equals(b.group(2)))
                movement = "line " + a.group(3) + " → " + b.group(3);
        }
        String[] chain = frames == null ? new String[0] : frames.split("\\s+<\\s+");
        List<String> sub = new ArrayList<>();
        if (movement != null) sub.add("same method, " + movement);
        else if (chain.length > 1 && !chain[1].startsWith("(")) sub.add("called from " + chain[1].strip());
        if (thread != null) sub.add("thread " + thread.replaceFirst("\\s*\\(index \\d+\\)$", ""));
        return new Node(Kind.STOP, where, String.join(" · ", sub), indexes, where, frames, thread, List.copyOf(variables),
                List.copyOf(expansions), List.copyOf(expressions), calls, movement, List.of(),
                stop.title().startsWith("Stopped at breakpoint"), null, null);
    }

    /** Frame lines of a "Read call stack" step: "  OrderService.quote:24". */
    private static final Pattern FRAME_LINE = Pattern.compile("^\\s+([\\w$.<>]+\\.[\\w$<>]+:\\d+)\\s*$");

    /** "Stopped at breakpoint: OrderService.quote, line 24" -> "OrderService.quote:24"; else the title. */
    private static String where(String title) {
        Matcher m = Pattern.compile(": (\\S+), line (\\d+)$").matcher(title == null ? "" : title);
        return m.find() ? m.group(1) + ":" + m.group(2) : title;
    }

    /** Breakpoints that never stopped the program, and "No stop was reached" when there were breakpoints but no stop. */
    private static List<Node> missed(ActivityStore.Investigation inv, List<Node> nodes) {
        Set<String> hit = new LinkedHashSet<>();
        boolean stopped = false, noStopNode = false;
        for (Node n : nodes) {
            if (n.kind() == Kind.NOSTOP) noStopNode = true;
            if (n.kind() != Kind.STOP) continue;
            stopped = true;
            Matcher m = FRAME.matcher(n.where() == null ? "" : n.where());
            if (m.matches()) hit.add(simple(m.group(1)) + ":" + m.group(3));
        }
        Map<String, Integer> set = new LinkedHashMap<>();
        for (int i = 0; i < inv.steps().size(); i++) {
            ActivityStore.Step s = inv.steps().get(i);
            if (!"BREAKPOINT".equals(s.kind()) || "FAILED".equals(s.status()) || s.title() == null) continue;
            Matcher m = BREAKPOINT.matcher(s.title());
            if (m.find()) set.putIfAbsent(simple(m.group(1)) + ":" + m.group(2), i);
        }
        List<Node> out = new ArrayList<>();
        if (!set.isEmpty() && !stopped && !noStopNode)
            out.add(new Node(Kind.NOSTOP, "No stop was reached", "breakpoints were set, but the program never stopped",
                    List.of(), null, null, null, List.of(), List.of(), List.of(), List.of(), null, List.of(), false,
                    null, null));
        for (var e : set.entrySet()) {
            if (hit.contains(e.getKey())) continue;
            out.add(new Node(Kind.NEVER, e.getKey(), "breakpoint set, never hit", List.of(e.getValue()), null, null,
                    null, List.of(), List.of(), List.of(), List.of(), null, List.of(), false, null, null));
        }
        return out;
    }

    /** The calls of a node: tool and what it was about, repeats counted, refused calls kept apart. */
    static List<Call> calls(List<ActivityStore.Step> steps) {
        Map<String, Call> calls = new LinkedHashMap<>();
        for (ActivityStore.Step s : steps) {
            List<String> technical = ReportFormat.split(s.details()).technical();
            String tool = valueOf(technical, "Tool: ");
            if (tool == null) continue;
            String about = valueOf(technical, "path: ");
            if (about == null) about = valueOf(technical, "action: ");
            boolean refused = "FAILED".equals(s.status());
            String key = tool + "\n" + about + "\n" + refused;
            Call c = calls.get(key);
            calls.put(key, new Call(tool, about, c == null ? 1 : c.count() + 1, refused));
        }
        return List.copyOf(calls.values());
    }

    /** The result of an evaluation: "value=0" written as "0"; for a refused one, why. */
    private static String result(ActivityStore.Step s, boolean failed) {
        ReportFormat.Details d = ReportFormat.split(s.details());
        if (failed) {
            for (String line : d.reader()) if (!line.isBlank()) return line.strip();
        }
        String result = valueOf(d.technical(), "Result: ");
        if (result == null) for (String line : d.reader()) if (!line.isBlank()) return line.strip();
        if (result == null) return "";
        return result.startsWith("value=") ? result.substring("value=".length()) : result;
    }

    private static List<String> reader(ActivityStore.Step s) {
        List<String> out = new ArrayList<>();
        for (String line : ReportFormat.split(s.details()).reader()) if (!line.isBlank()) out.add(line);
        return out;
    }

    private static String valueOf(List<String> lines, String prefix) {
        for (String line : lines) if (line.startsWith(prefix)) return line.substring(prefix.length()).strip();
        return null;
    }

    private static String after(String text, String sep) {
        if (text == null) return "";
        int at = text.indexOf(sep);
        return at < 0 ? text : text.substring(at + sep.length()).strip();
    }

    /** org.example.Outer$Inner -> Outer$Inner. */
    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    /**
     * One line of the agent's Markdown as plain text for its node: the first line under a "Root cause" heading when
     * there is one, otherwise the first line.
     */
    static String firstLine(String markdown) {
        String[] lines = markdown.split("\\R");
        int from = 0;
        for (int i = 0; i < lines.length; i++)
            if (lines[i].matches("^#+\\s*(?i:root cause).*")) {
                from = i + 1;
                break;
            }
        for (int i = from; i < lines.length; i++) {
            String plain = lines[i].replaceAll("^#+\\s*", "").replaceAll("[*`_]", "").strip();
            if (!plain.isEmpty()) return plain.length() > 140 ? plain.substring(0, 140).stripTrailing() + "…" : plain;
        }
        return "";
    }

    // ---- rendering --------------------------------------------------------------------------------------------

    /** Styles of the flow, added to the report's own. */
    static final String CSS = """
            body.report{font-size:14px;color:#111827;background:#f1f5f9}
            body.report #pin{background:#f1f5f9}
            body.report #head h1{font-size:19px;color:#0b2545}
            body.report .req{font-size:13.5px;color:#1f2937}
            body.report .info{font-size:12.5px;color:#374151}
            body.report .banner{font-size:14px}
            body.report .prog{font-size:12.5px;color:#1f2937}
            body.report #bar .rep{color:#1f2937;font-size:12.5px}
            .flegend{display:flex;flex-wrap:wrap;gap:8px 12px;font-size:13px;color:#1f2937;margin:8px 0 0;align-items:center}
            .ftag{display:inline-block;font-size:11px;font-weight:700;border-radius:10px;padding:1px 8px;letter-spacing:.3px;
            white-space:nowrap}
            .ftag.obs{background:#dcfce7;color:#14532d;border:1.5px solid #22c55e}
            .ftag.agent{background:#ede9fe;color:#4c1d95;border:1.5px solid #8b5cf6}
            .ftag.bp{background:#fee2e2;color:#7f1d1d;border:1.5px solid #ef4444}
            .ftag.never{background:#e5e7eb;color:#1f2937;border:1.5px solid #6b7280}
            .ftag.ref{background:#fef3c7;color:#78350f;border:1.5px solid #f59e0b}
            .fviews{display:flex;gap:6px;margin:10px 0 0}
            body:not(.js) .fviews{display:none}
            .fviews button{font:inherit;font-size:13px;font-weight:600;padding:4px 14px;border:1.5px solid #94a3b8;
            border-radius:7px;background:#fff;color:#111827;cursor:pointer}
            .fviews button.on{background:#1d4ed8;color:#fff;border-color:#1d4ed8}
            main.fl{display:grid;grid-template-columns:minmax(320px,40%) 1fr;gap:16px;margin:10px 0 0;align-items:start}
            .fflow{display:flex;flex-direction:column}
            .fflow::before{content:"EXECUTION FLOW";font-size:12px;font-weight:700;letter-spacing:1px;color:#1e3a8a;
            margin:0 0 6px 2px}
            .fnode{display:block;background:#fff;border:1.5px solid #cbd5e1;border-left:6px solid var(--k);border-radius:9px;
            padding:8px 12px;color:#111827;text-decoration:none;position:relative;transition:background .2s,box-shadow .2s;
            box-shadow:0 1px 2px rgba(15,23,42,.08)}
            .fnode:hover{border-color:#3b82f6;border-left-color:var(--k)}
            .fnode .nm{font:700 14px Consolas,'Cascadia Mono',monospace;color:#0f172a;display:block;overflow-wrap:normal}
            .fnode .sub{font-size:13px;color:#1f2937;display:block;margin-top:1px}
            .fnode .tags{float:right;display:flex;flex-wrap:wrap;justify-content:flex-end;gap:4px;margin:0 0 2px 10px}
            .fnode::after{content:"";display:block;clear:both}
            .fnode .side{display:block;font-size:12px;color:#1f2937;margin-top:4px}
            .fnode .side span{background:#e0e7ff;color:#1e3a8a;border-radius:5px;padding:0 7px;margin-right:4px;
            display:inline-block;font-weight:600}
            .fnode.never{border-style:dashed;border-left-style:solid;background:#f8fafc}
            .fnode.agent{border-style:dashed;border-left-style:solid;background:#faf5ff}
            .fnode.nostop{background:#fffbeb}
            .farrow{height:16px;margin-left:24px;border-left:3px solid #94a3b8}
            .farrow.dash{border-left-style:dashed}
            body.js .fnode.later{background:#f8fafc;border-color:#e2e8f0;box-shadow:none}
            body.js .fnode.later .nm{color:#334155}
            body.js .fnode.cur{background:#eff6ff;border-color:#2563eb;border-left-color:var(--k);
            box-shadow:0 0 0 3px rgba(37,99,235,.25)}
            body.js .farrow.lit{border-left-color:#2563eb}
            .fdetail{background:#fff;border:1.5px solid #cbd5e1;border-radius:10px;padding:12px 18px;
            box-shadow:0 1px 3px rgba(15,23,42,.08)}
            body.js .fdetail{position:sticky;top:100px;max-height:calc(100vh - 160px);overflow:auto}
            .fd{padding:2px 0 10px}
            body:not(.js) .fd+.fd{border-top:2px solid #cbd5e1;padding-top:10px}
            body.js .fd{display:none}
            body.js .fd.cur{display:block}
            .fd .fhead{display:flex;justify-content:space-between;align-items:center;gap:8px;color:#1f2937;font-size:13px;
            font-weight:600}
            .fd h2{font-size:18px;margin:4px 0 3px;color:#0b2545}
            .fd .fwhere{font:13px Consolas,'Cascadia Mono',monospace;color:#0e7490;margin-bottom:6px}
            .fd .fwhere details.more{display:inline}
            .fd .fwhere details.more summary{display:inline;cursor:pointer;text-decoration:underline dotted}
            .fd .fwhere details.more[open] summary{display:none}
            .fsec{margin-top:12px}
            .fsec h3{font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#6d28d9;margin:0 0 5px}
            .fd table.vars{font-size:13.5px}
            .fd table.vars th{color:#1f2937;border-bottom:1.5px solid #cbd5e1;padding:3px 10px 3px 0}
            .fd table.vars td{color:#0f172a;padding:3px 10px 3px 0;border-bottom:1px solid #f1f5f9}
            .fd table.vars td:first-child{color:#1d4ed8;font-weight:600}
            .fd table.vars tr.changed td{background:#fef08a}
            .fd .note{color:#374151;font-size:12.5px}
            .fchip{display:inline-block;font:12.5px Consolas,'Cascadia Mono',monospace;background:#ede9fe;
            border:1.5px solid #a78bfa;color:#3b0764;border-radius:6px;padding:1px 8px;margin:2px 4px 2px 0}
            .fchip.ref{background:#fef3c7;border-color:#f59e0b;color:#78350f}
            .fexpr{background:#f8fafc;border:1.5px solid #cbd5e1;border-radius:7px;padding:5px 10px;margin:4px 0;
            font:13px Consolas,'Cascadia Mono',monospace;color:#0f172a}
            .fexpr .res{display:block;color:#15803d;font-weight:700}
            .fexpr.failed .res{color:#b45309}
            .ffold{font-size:13px;color:#1f2937;margin-top:5px}
            .ffold>summary{cursor:pointer;color:#1d4ed8;font-weight:600}
            .ffold .fcall{margin:6px 0 0 10px;border-left:3px solid #cbd5e1;padding-left:8px}
            .ffold .fcall .ct{font-weight:700;color:#0f172a}
            .ffold pre{white-space:pre-wrap;margin:2px 0;font:12.5px/1.45 Consolas,'Cascadia Mono',monospace;color:#0f172a}
            .ffold details.tech summary{cursor:pointer;color:#374151}
            .fagent{border-left:4px solid #7c3aed;background:#f5f3ff;padding:8px 12px;border-radius:0 8px 8px 0;color:#111827}
            .fobs{border-left:4px solid #22c55e;background:#f0fdf4;border-radius:0 8px 8px 0;padding:6px 12px;
            font:13px/1.5 Consolas,'Cascadia Mono',monospace;color:#0f172a;white-space:pre-wrap}
            .fnote{font-size:12.5px;color:#374151;margin-top:12px;border-top:1px dashed #cbd5e1;padding-top:6px}
            #fctl{display:none}
            body.js #fctl{display:flex;position:fixed;bottom:0;left:0;right:0;z-index:6;background:#fff;
            border-top:1.5px solid #cbd5e1;gap:8px;align-items:center;padding:8px 14px;box-shadow:0 -2px 6px rgba(15,23,42,.06)}
            body.js{padding-bottom:56px}
            #fctl button{font:inherit;font-size:13.5px;font-weight:600;padding:4px 15px;border:1.5px solid #94a3b8;
            border-radius:7px;background:#fff;color:#111827;cursor:pointer}
            #fctl button.p{background:#2563eb;color:#fff;border-color:#2563eb}
            #fctl .fn{font-size:13px;color:#111827;font-weight:600;white-space:nowrap;min-width:90px}
            #fctl .fprog{flex:1;height:7px;background:#e2e8f0;border-radius:9px;overflow:hidden;min-width:40px}
            #fctl .fprog div{height:100%;width:0;background:#2563eb;transition:width .25s}
            #fctl .fdots{display:flex;gap:6px;flex-wrap:wrap}
            #fctl .fdots a{width:11px;height:11px;border-radius:50%;background:#94a3b8;display:inline-block}
            #fctl .fdots a.on{background:#2563eb;transform:scale(1.3)}
            #fctl .fdots a.bp{outline:2px solid #ef4444;outline-offset:1px}
            #fctl .fhint{font-size:12px;color:#374151;white-space:nowrap}
            body.js.fall main.fl,body.js.fall #fctl,body.js.fall .flegend{display:none}
            body.js:not(.fall) #all{display:none}
            #all>h2.fallh{margin-top:16px}
            body.js #all>h2.fallh{display:none}
            body.js:not(.fall) #bar .xa{display:none}
            """;

    /**
     * The dark theme of the report, after the IntelliJ investigation page the owner showed (25 Nov): dark navy
     * background, light text, coloured accents, glowing current node, arrows with heads. On {@code body.report.dark};
     * also in the live Agent Activity window (the window's Dark / Light button, kept in the Eclipse preferences).
     * Covers the flow and the step list in both.
     */
    static final String DARK_CSS = """
            body.dark{--bg:#0b1020;--panel:#121933;--panel2:#182042;--line:#2a3566;--text:#e7ebff;--muted:#9aa5d1;
            --blue:#5aa2ff;--green:#3ddc97;--amber:#ffc857;--red:#ff5c7a;--violet:#b58cff;--cyan:#4fe3e3;
            background:radial-gradient(1200px 600px at 20% -10%,#17214a 0%,#0b1020 60%) fixed,#0b1020;color:var(--text);
            font:15px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;min-height:100vh;padding:10px 22px 14px}
            body.dark #pin{background:#0b1020;box-shadow:0 2px 10px rgba(0,0,0,.35);margin:-10px -22px 8px;
            padding:8px 22px}
            body.dark #bar .rep{color:var(--muted);font-size:13px}
            body.dark button,body.dark .fviews button,body.dark #fctl button{background:var(--panel2);
            color:var(--text);border:1px solid var(--line);border-radius:10px;font-weight:600;transition:all .2s}
            body.dark #bar button{background:var(--panel2);color:var(--text);border:1px solid var(--line);
            border-radius:8px;padding:3px 11px;font-size:12.5px}
            body.dark button:hover,body.dark #bar button:hover{border-color:var(--blue);background:#1f2c63}
            body.report:not(.js) #ftheme{display:none}
            body.dark .fviews button{padding:7px 16px;font-size:14px}
            body.dark .fviews button.on,body.dark #fctl button.p{background:var(--blue);color:#07122b;
            border-color:var(--blue)}
            body.dark .banner.done{background:rgba(61,220,151,.13);color:var(--green);border:1px solid rgba(61,220,151,.4)}
            body.dark .banner.ended{background:rgba(255,200,87,.12);color:var(--amber);border:1px solid rgba(255,200,87,.4)}
            body.dark .banner.running{background:rgba(90,162,255,.14);color:var(--blue);animation:none}
            body.dark .prog,body.dark .prog b{color:var(--muted)}
            body.dark .prog b{color:var(--text)}
            body.dark #head h1{font-size:22px;color:var(--text);letter-spacing:.2px;margin:4px 0 4px}
            body.dark .req{color:var(--muted);font-size:14px}
            body.dark .req .lbl,body.dark .req .more{color:var(--blue)}
            body.dark .req .full{background:var(--panel2);border-left-color:var(--blue);color:var(--text)}
            body.dark .info{color:var(--muted);font-size:13px}
            body.dark .flegend{color:var(--text);font-size:12.5px}
            body.dark .ftag{border-radius:999px;padding:2px 9px;font-weight:600;font-size:11.5px}
            body.dark .ftag.obs{background:rgba(61,220,151,.15);color:var(--green);border:1px solid rgba(61,220,151,.45)}
            body.dark .ftag.agent{background:rgba(181,140,255,.15);color:var(--violet);border:1px solid rgba(181,140,255,.5)}
            body.dark .ftag.bp{background:rgba(255,92,122,.14);color:var(--red);border:1px solid rgba(255,92,122,.5)}
            body.dark .ftag.never{background:rgba(154,165,209,.12);color:var(--muted);border:1px solid rgba(154,165,209,.45)}
            body.dark .ftag.ref{background:rgba(255,200,87,.13);color:var(--amber);border:1px solid rgba(255,200,87,.45)}
            body.dark main.fl{grid-template-columns:minmax(380px,1.05fr) minmax(380px,1fr);gap:18px}
            body.dark .fflow,body.dark .fdetail{background:var(--panel);border:1px solid var(--line);
            border-radius:14px;padding:16px;box-shadow:none}
            body.dark .fflow::before{content:"Execution flow";font-size:13px;text-transform:uppercase;
            letter-spacing:1.2px;color:var(--muted);margin:0 0 10px}
            body.dark .fnode{background:var(--panel2);border:1.5px solid var(--line);border-radius:11px;color:var(--text);
            padding:9px 12px;box-shadow:none;transition:all .45s ease}
            body.dark .fnode.hit{border-color:rgba(255,92,122,.55)}
            body.dark .fnode .nm{color:var(--text);font:650 14px ui-monospace,Consolas,monospace}
            body.dark .fnode .sub{color:var(--muted);font-size:12px}
            body.dark .fnode .side span{background:rgba(90,162,255,.13);color:#bcd6ff;font-weight:500}
            body.dark .fnode.never,body.dark .fnode.agent{border-style:dashed;background:var(--panel2)}
            body.dark .fnode.nostop{border-color:rgba(255,200,87,.5)}
            body.dark .fnode:hover{border-color:var(--blue)}
            body.js.dark .fnode{opacity:.55}
            body.js.dark .fnode.visited{opacity:.85}
            body.js.dark .fnode.later{background:var(--panel2);border-color:var(--line)}
            body.js.dark .fnode.later .nm{color:var(--text)}
            body.js.dark .fnode.cur{opacity:1;transform:scale(1.025);border-color:var(--blue);background:#1c2a5c;
            box-shadow:0 0 0 3px rgba(90,162,255,.22),0 8px 28px rgba(90,162,255,.25)}
            body.js.dark .fnode.cur.hit{border-color:var(--red);
            box-shadow:0 0 0 3px rgba(255,92,122,.25),0 8px 28px rgba(255,92,122,.28)}
            body.dark .farrow{height:22px;margin:0;border:0;display:flex;justify-content:center;position:relative}
            body.dark .farrow::before{content:"";width:2px;background:var(--line);height:100%;transition:background .4s}
            body.dark .farrow::after{content:"";position:absolute;bottom:0;border:5px solid transparent;
            border-top:7px solid var(--line);transition:border-top-color .4s}
            body.dark .farrow.lit::before{background:var(--blue)}
            body.dark .farrow.lit::after{border-top-color:var(--blue)}
            body.dark .farrow.dash::before{background:repeating-linear-gradient(var(--line) 0 4px,transparent 4px 8px)}
            body.dark .farrow.dash.lit::before{background:repeating-linear-gradient(var(--violet) 0 4px,transparent 4px 8px)}
            body.dark .fd .fhead{color:var(--muted);font-weight:400;font-size:13px}
            body.dark .fd h2{font-size:19px;font-weight:700;color:var(--text);margin:6px 0 2px}
            body.dark .fd .fwhere{color:var(--cyan);font:13px ui-monospace,Consolas,monospace;margin-bottom:10px}
            body.dark .fsec h3{color:var(--violet);font-size:12px;letter-spacing:1px}
            body.dark .fd table.vars{font-size:13.5px}
            body.dark .fd table.vars th{color:var(--muted);border-bottom:1px solid var(--line)}
            body.dark .fd table.vars td{color:var(--text);border-bottom:1px solid var(--line);
            font-family:ui-monospace,Consolas,monospace;padding:4px 8px 4px 0}
            body.dark .fd table.vars td:first-child{color:var(--blue);font-weight:500}
            body.dark .fd table.vars tr.changed td{background:rgba(255,200,87,.14)}
            body.dark table.vars .was{color:var(--amber)}
            body.dark .fd .note,body.dark .fnote{color:var(--muted);border-top-color:var(--line)}
            body.dark .fchip{background:rgba(181,140,255,.14);border:1px solid rgba(181,140,255,.45);color:#d9c6ff;
            border-radius:7px;padding:2px 9px;font-size:12.5px}
            body.dark .fchip.ref{background:rgba(255,200,87,.12);border-color:rgba(255,200,87,.45);color:var(--amber)}
            body.dark .fexpr{background:#0d1330;border:1px solid var(--line);color:var(--text);border-radius:8px;
            padding:7px 10px}
            body.dark .fexpr .res{color:var(--green);font-weight:500;margin-top:3px}
            body.dark .fexpr.failed .res{color:var(--amber)}
            body.dark .fobs{border-left:3px solid var(--green);background:rgba(61,220,151,.07);color:var(--text);
            border-radius:0 9px 9px 0}
            body.dark .fagent{border-left:3px solid var(--violet);background:rgba(181,140,255,.08);color:var(--text);
            border-radius:0 9px 9px 0}
            body.dark .ffold{color:var(--text)}
            body.dark .ffold>summary{color:var(--blue)}
            body.dark .ffold .fcall{border-left-color:var(--line)}
            body.dark .ffold .fcall .ct{color:var(--text)}
            body.dark .ffold pre,body.dark li.s pre{color:var(--text)}
            body.dark .ffold details.tech summary,body.dark li.s .tech summary,
            body.dark li.s .tech pre{color:var(--muted)}
            body.dark .ad-md .ad-h{color:var(--green)}
            body.dark .ad-md code{color:#ffb3c6;background:#0d1330}
            body.dark .ad-md td,body.dark .ad-md th{border-color:var(--line)}
            body.dark .ad-md th{background:rgba(61,220,151,.1)}
            body.js.dark #fctl{background:linear-gradient(0deg,#0b1020 70%,rgba(11,16,32,.85));border-top:1px solid var(--line);
            padding:10px 22px;box-shadow:none}
            body.dark #fctl button{padding:8px 17px;font-size:14px}
            body.dark #fctl .fn,body.dark #fctl .fhint{color:var(--muted)}
            body.dark #fctl .fprog{height:8px;background:var(--panel2);border:1px solid var(--line)}
            body.dark #fctl .fprog div{background:linear-gradient(90deg,var(--blue),var(--violet))}
            body.dark #fctl .fdots a{background:var(--line)}
            body.dark #fctl .fdots a.on{background:var(--blue)}
            body.dark #fctl .fdots a.bp{outline-color:var(--red)}
            body.dark h2{color:var(--text)}
            body.dark li.s{background:var(--panel);color:var(--text)}
            body.dark li.s .n,body.dark li.s .ms{color:var(--muted)}
            body.dark li.s.refused{background:rgba(255,200,87,.07)}
            body.dark #stop:not(:empty){background:var(--panel);border-left-color:var(--amber)}
            body.dark #stop h2{color:var(--amber)}
            body.dark #stop .where,body.dark #stop .frames{color:var(--muted)}
            body.dark table.vars th{color:var(--muted);border-bottom-color:var(--line)}
            body.dark .analysis{background:rgba(61,220,151,.07);border-left-color:var(--green);color:var(--text)}
            body.dark .warn{background:rgba(255,200,87,.1);color:var(--amber);border-left-color:var(--amber)}
            body.dark .note{color:var(--muted)}
            .fd.fade{animation:fl-fade .45s ease}
            @keyframes fl-fade{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
                        body.dark #bar,body.dark #bar label{color:var(--text)}
            body.dark:not(.report){padding:8px 10px 14px}
            body.dark:not(.report) #pin{margin:-8px -10px 6px;padding:6px 10px}
            body.dark:not(.report) a.src{color:var(--blue)}
            body.dark #jump{background:var(--blue);color:#07122b}
            body.dark #vbar button.on{background:var(--blue);color:#07122b;border-color:var(--blue)}
            body.dark details.fl1[open]>summary.fnode{background:#1c2a5c;border-color:var(--blue);border-left-color:var(--k);
            opacity:1}
            body.dark details.fl1 .fdl{background:var(--panel);border-color:var(--blue)}
            body.dark .fnode .frun{color:var(--red)}
            body.dark .fnode.busy{animation:fl-breathe-dark 1.2s ease-in-out infinite}
            @keyframes fl-breathe-dark{0%,100%{background:#2a1a3a}50%{background:#3d1d36}}
            body.dark .flive{background:none;border:0;padding:0}
            body.dark li.s.run{animation:none;background:rgba(255,92,122,.14)}
            body.dark li.s.run .t{color:var(--red)}
            body.dark #toast{color:var(--green)}
            body.dark .req .full{background:var(--panel2)}
            body.dark .banner.running .what{color:var(--blue)}
""";

    /** The script of the flow: one node at a time, Previous, Next, Replay, keys; the views Flow and All steps. */
    static final String SCRIPT = """
            var fl={cur:0,n:0,timer:null};
            // Theme: dark (default) or light; the reader's choice is kept in this browser when it allows it.
            function flTheme(dark){if(dark===undefined)dark=!document.body.classList.contains('dark');
              document.body.classList.toggle('dark',dark);var b=document.getElementById('ftheme');
              if(b)b.textContent=dark?'Light theme':'Dark theme';
              try{localStorage.setItem('adReportTheme',dark?'dark':'light');}catch(x){}return false;}
            function flInit(){document.body.classList.add('js');
              try{var t=localStorage.getItem('adReportTheme');if(t)flTheme(t==='dark');}catch(x){}fl.n=document.querySelectorAll('.fd').length;
              if(fl.n>0)flShow(0);
              document.addEventListener('keydown',function(e){if(e.target&&/INPUT|TEXTAREA/.test(e.target.tagName))return;
                if(document.body.classList.contains('fall'))return;
                if(e.key==='ArrowRight'){flStop();flShow(fl.cur+1);e.preventDefault();}
                else if(e.key==='ArrowLeft'){flStop();flShow(fl.cur-1);e.preventDefault();}
                else if(e.key==='r'||e.key==='R')flReplay();});}
            function flShow(i){if(fl.n===0)return false;i=Math.max(0,Math.min(fl.n-1,i));fl.cur=i;
              var ds=document.querySelectorAll('.fd'),ns=document.querySelectorAll('a.fnode'),
                as=document.querySelectorAll('.farrow'),dots=document.querySelectorAll('#fctl .fdots a');
              for(var k=0;k<ds.length;k++)ds[k].classList.toggle('cur',k===i);
              for(var k=0;k<ns.length;k++){ns[k].classList.toggle('cur',k===i);ns[k].classList.toggle('later',k>i);
                ns[k].classList.toggle('visited',k<i);}
              if(ds[i]){ds[i].classList.remove('fade');void ds[i].offsetWidth;ds[i].classList.add('fade');}
              for(var k=0;k<as.length;k++)as[k].classList.toggle('lit',k<i);
              for(var k=0;k<dots.length;k++)dots[k].classList.toggle('on',k===i);
              document.getElementById('fnum').textContent='Step '+(i+1)+' of '+fl.n;
              document.getElementById('fbar').style.width=(fl.n>1?100*i/(fl.n-1):100)+'%';
              var d=document.querySelector('.fdetail');if(d)d.scrollTop=0;
              if(ns[i]&&ns[i].scrollIntoView){var r=ns[i].getBoundingClientRect();
                if(r.top<90||r.bottom>window.innerHeight-60)ns[i].scrollIntoView({block:'center',behavior:'smooth'});}
              return false;}
            function flStop(){if(fl.timer){clearInterval(fl.timer);fl.timer=null;}
              var b=document.getElementById('freplay');if(b)b.textContent='\\u27F2 Replay';}
            function flReplay(){if(fl.timer){flStop();return false;}flShow(0);
              document.getElementById('freplay').textContent='\\u25A0 Stop';
              fl.timer=setInterval(function(){if(fl.cur>=fl.n-1){flStop();return;}flShow(fl.cur+1);},1600);return false;}
            function flView(all){flStop();document.body.classList.toggle('fall',all);
              document.getElementById('vflow').classList.toggle('on',!all);
              document.getElementById('vall').classList.toggle('on',all);window.scrollTo(0,0);return false;}
            """;

    /**
     * The flow part of the report: the legend, the views (Flow, All steps), the nodes on the left with a detail
     * section per node on the right, and the controls. Without the script every detail section is shown, one below
     * the other, and a node is a link to its section; with it one at a time.
     */
    static String render(ActivityStore.Investigation inv) {
        List<Node> nodes = nodes(inv);
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"flegend\"><span class=\"ftag obs\">OBSERVED</span> recorded by the tools from the "
                + "debugger or the HTTP exchange <span class=\"ftag agent\">FROM THE AGENT</span> the agent's own "
                + "statement, not checked by the tool <span class=\"ftag bp\">BREAKPOINT HIT</span></div>");
        out.append("<div class=\"fviews\"><button id=\"vflow\" class=\"on\" onclick=\"return flView(false)\" title=\"Where "
                + "the program went, one place at a time\">Program flow</button><button id=\"vall\" onclick=\"return "
                + "flView(true)\" title=\"Every tool call in order, as in the Agent Activity view\">All steps</button></div>");
        if (nodes.isEmpty()) {
            out.append("<main class=\"fl\"><div class=\"fflow\"><div class=\"note\">No steps were recorded.</div></div>"
                    + "<div class=\"fdetail\"></div></main>");
            return out.append(controls(nodes)).toString();
        }
        out.append("<main class=\"fl\"><div class=\"fflow\">");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0)
                out.append("<div class=\"farrow").append(n.kind() == Kind.AGENT || n.kind() == Kind.NEVER ? " dash" : "")
                        .append("\"></div>");
            out.append(nodeBox(n, i));
        }
        out.append("</div><div class=\"fdetail\">");
        int stops = 0, stopNo = 0;
        for (Node n : nodes) if (n.kind() == Kind.STOP) stops++;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.kind() == Kind.STOP) stopNo++;
            out.append(detail(inv, n, i, nodes.size(), stopNo, stops));
        }
        out.append("</div></main>");
        return out.append(controls(nodes)).toString();
    }

    private static String nodeBox(Node n, int i) {
        return "<a class=\"" + nodeClass(n) + "\" style=\"--k:" + colour(n.kind()) + "\" href=\"#fd" + i
                + "\" onclick=\"if(window.flShow){flStop();return flShow(" + i + ");}\">" + boxContent(n, null) + "</a>";
    }

    private static String nodeClass(Node n) {
        return "fnode " + n.kind().name().toLowerCase(java.util.Locale.ROOT) + (n.breakpointHit() ? " hit" : "");
    }

    /** The inside of a node's box; {@code running}: the title of the call the agent is making there now, or null. */
    private static String boxContent(Node n, String running) {
        StringBuilder out = new StringBuilder()
                .append("<span class=\"tags\">").append(tags(n)).append("</span>")
                .append("<span class=\"nm\">").append(html(n.name())).append("</span>");
        // No source links in a node: it is a link itself, and links cannot be nested.
        if (n.sub() != null && !n.sub().isBlank())
            out.append("<span class=\"sub\">").append(html(n.sub())).append("</span>");
        List<String> side = new ArrayList<>();
        if (!n.variables().isEmpty()) side.add(n.variables().size() + (n.variables().size() == 1 ? " variable" : " variables"));
        long changed = n.variables().stream().filter(ActivityPage.Variable::changed).count();
        if (changed > 0) side.add(changed + " changed");
        for (Expansion e : n.expansions()) side.add("expanded " + e.path() + (e.count() > 1 ? " ×" + e.count() : ""));
        if (!n.expressions().isEmpty())
            side.add(n.expressions().size() + (n.expressions().size() == 1 ? " expression" : " expressions"));
        String next = lastAction(n);
        if (next != null) side.add(next);
        if (n.refused() > 0) side.add(n.refused() + " refused");
        if (!side.isEmpty()) {
            out.append("<span class=\"side\">");
            for (String s : side) out.append("<span>").append(html(s)).append("</span>");
            out.append("</span>");
        }
        if (running != null)
            out.append("<span class=\"frun\"><span class=\"live\"></span>").append(html(running)).append("</span>");
        return out.toString();
    }

    /**
     * The flow in the live Agent Activity view (one column, narrow window): each node a box that opens on a click to
     * show what is known there, the newest node open; the call the agent is making now pulses in its node. Open and
     * closed boxes are kept across updates by the page's script ({@code data-i}, the user's click wins).
     */
    static String live(ActivityStore.Investigation inv) {
        List<Node> nodes = nodes(inv);
        if (nodes.isEmpty()) return "";
        List<ActivityStore.Step> steps = inv.steps();
        int runningAt = !inv.finished() && !steps.isEmpty() && steps.get(steps.size() - 1).running() ? steps.size() - 1 : -1;
        int open = nodes.size() - 1;
        StringBuilder out = new StringBuilder("<div class=\"fflow flive\">");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0)
                out.append("<div class=\"farrow").append(n.kind() == Kind.AGENT || n.kind() == Kind.NEVER ? " dash" : "")
                        .append("\"></div>");
            String running = runningAt >= 0 && n.steps().contains(runningAt) ? steps.get(runningAt).title() : null;
            out.append("<details class=\"fl1\" data-i=\"f").append(i).append("\" data-d=\"").append(i == open ? "1\" open" : "0\"")
                    .append("><summary class=\"").append(nodeClass(n)).append(running != null ? " busy" : "")
                    .append("\" style=\"--k:").append(colour(n.kind())).append("\">").append(boxContent(n, running))
                    .append("</summary><div class=\"fd fdl\">");
            body(inv, n, out);
            out.append("</div></details>");
        }
        return out.append("</div>").toString();
    }

    /** Styles of the live flow, added to the view's own and {@link #CSS}. */
    static final String LIVE_CSS = """
            #vbar{display:inline-flex;gap:0;margin-right:4px}
            #vbar button{border-radius:0}
            #vbar button:first-child{border-radius:5px 0 0 5px}
            #vbar button:last-child{border-radius:0 5px 5px 0;margin-left:-1px}
            #vbar button.on{background:#1d4ed8;color:#fff;border-color:#1d4ed8}
            body:not(.vsteps) #steps,body:not(.vsteps) #stop,body:not(.vsteps) #analysis{display:none}
            body.vsteps #flow{display:none}
            body:not(.vsteps) #bar .xa{display:none}
            #flow:not(:empty){margin-top:8px}
            .flive::before{display:none}
            details.fl1>summary{list-style:none;cursor:pointer}
            details.fl1>summary::-webkit-details-marker{display:none}
            details.fl1>summary.fnode{padding:7px 10px}
            details.fl1>summary.fnode .nm{font-size:13px}
            details.fl1>summary.fnode .sub{font-size:12px}
            details.fl1[open]>summary.fnode{border-color:#2563eb;border-left-color:var(--k);border-radius:9px 9px 0 0;
            background:#eff6ff}
            details.fl1 .fdl{background:#fff;border:1.5px solid #2563eb;border-top:0;border-left:6px solid #2563eb;
            border-radius:0 0 9px 9px;padding:4px 12px 10px}
            details.fl1 .fdl .fsec{margin-top:8px}
            details.fl1 .fdl table.vars{font-size:12.5px}
            .fnode .frun{display:flex;align-items:center;gap:6px;margin-top:4px;font-size:12px;font-weight:600;color:#b91c1c}
            .fnode.busy{animation:ad-breathe-red 1.2s ease-in-out infinite}
            """;

    /** How the program left the stop: "resumed", "stepped over", ...; null when it did not leave by a call. */
    private static String lastAction(Node n) {
        if (n.kind() != Kind.STOP) return null;
        for (int c = n.calls().size() - 1; c >= 0; c--) {
            Call call = n.calls().get(c);
            if (!"control_debug_session".equals(call.tool()) || call.refused() || call.about() == null) continue;
            return switch (call.about()) {
                case "RESUME" -> "resumed";
                case "STEP_OVER" -> "stepped over";
                case "STEP_INTO" -> "stepped into";
                case "STEP_RETURN" -> "stepped out";
                case "STOP" -> "terminated";
                default -> call.about().toLowerCase(java.util.Locale.ROOT);
            };
        }
        return null;
    }

    private static String tags(Node n) {
        StringBuilder out = new StringBuilder();
        if (n.breakpointHit()) out.append("<span class=\"ftag bp\">BREAKPOINT HIT</span>");
        if (n.kind() == Kind.NEVER) out.append("<span class=\"ftag never\">NEVER HIT</span>");
        if (n.refused() > 0) out.append("<span class=\"ftag ref\">REFUSED CALL</span>");
        out.append(n.kind() == Kind.AGENT ? "<span class=\"ftag agent\">FROM THE AGENT</span>"
                : "<span class=\"ftag obs\">OBSERVED</span>");
        return out.toString();
    }

    private static String colour(Kind kind) {
        return switch (kind) {
            case PREP -> ReportFormat.kindColour("BREAKPOINT");
            case REQUEST, RESPONSE -> ReportFormat.kindColour("HTTP");
            case STOP -> ReportFormat.kindColour("STOP");
            case NOSTOP -> "#f59e0b";
            case NEVER -> "#9ca3af";
            case CLEAN -> ReportFormat.kindColour("CLEANUP");
            case AGENT -> "#7c3aed";
        };
    }

    private static String detail(ActivityStore.Investigation inv, Node n, int i, int total, int stopNo, int stops) {
        StringBuilder out = new StringBuilder("<section class=\"fd\" id=\"fd").append(i).append("\" data-n=\"").append(i)
                .append("\"><div class=\"fhead\"><span>Step ").append(i + 1).append(" of ").append(total)
                .append("</span><span>").append(tags(n)).append("</span></div><h2>");
        String heading = switch (n.kind()) {
            case STOP -> "Stop " + stopNo + " of " + stops + ": " + n.name();
            case REQUEST -> "HTTP request: " + n.name();
            case RESPONSE -> "HTTP response: " + n.name().substring("HTTP ".length());
            case NEVER -> "Never hit: breakpoint " + n.name();
            default -> n.name();
        };
        out.append(ActivityPage.links(html(heading))).append("</h2>");
        body(inv, n, out);
        return out.append("</section>").toString();
    }

    /** What is known at a node: the sections below its heading, the same in the report and in the live view. */
    private static void body(ActivityStore.Investigation inv, Node n, StringBuilder out) {
        switch (n.kind()) {
            case STOP -> stopDetail(n, out);
            case NEVER -> out.append("<div class=\"fsec\"><div class=\"fobs\">").append(ActivityPage.links(html(
                    "The breakpoint at " + n.name() + " was set (step " + (inv.droppedSteps() + n.steps().get(0) + 1)
                            + ") but the program never stopped there in this session: the line was not reached by"
                            + " what ran, or another branch was taken."))).append("</div></div>");
            case AGENT -> out.append("<div class=\"fsec\"><h3>The agent's analysis</h3><div class=\"fagent\">")
                    .append(ActivityPage.links(ReportFormat.markdown(inv.conclusion())))
                    .append("</div></div><div class=\"fnote\">This is what the agent wrote at the finish. The tool"
                            + " did not check it; the recorded facts are in the steps before.</div>");
            case PREP -> {
                out.append("<div class=\"fsec\"><h3>What was prepared</h3><div class=\"fobs\">");
                for (int s : n.steps()) {
                    ActivityStore.Step step = inv.steps().get(s);
                    out.append(ActivityPage.links(html(step.title() == null ? "" : step.title())))
                            .append("FAILED".equals(step.status()) ? " <span class=\"ftag ref\">REFUSED</span>" : "")
                            .append('\n');
                }
                out.append("</div></div>");
            }
            default -> {
                if (n.steps().isEmpty()) out.append("<div class=\"fsec\"><div class=\"fobs\">").append(html(n.sub()))
                        .append("</div></div>");
                if (!n.lines().isEmpty()) out.append("<div class=\"fsec\"><h3>Recorded</h3><div class=\"fobs\">")
                        .append(ActivityPage.links(html(String.join("\n", n.lines())))).append("</div></div>");
            }
        }
        if (n.purpose() != null)
            out.append("<div class=\"fsec\"><h3>Why the agent stopped here <span class=\"ftag agent\">FROM THE AGENT</span>"
                    + "</h3><div class=\"fagent\">").append(html(n.purpose())).append("</div></div>");
        if (n.finding() != null)
            out.append("<div class=\"fsec\"><h3>What the agent learned here <span class=\"ftag agent\">FROM THE AGENT"
                    + "</span></h3><div class=\"fagent\">").append(html(n.finding())).append("</div></div>");
        if (n.kind() != Kind.NEVER && n.kind() != Kind.AGENT) calls(inv, n, out);
    }

    private static void stopDetail(Node n, StringBuilder out) {
        if (n.frames() != null || n.thread() != null) {
            out.append("<div class=\"fwhere\">");
            if (n.frames() != null) out.append(ActivityPage.frames(n.frames()));
            if (n.thread() != null) out.append(n.frames() == null ? "" : " · ").append("thread ").append(html(n.thread()));
            out.append("</div>");
        }
        if (n.movement() != null)
            out.append("<div class=\"fsec\"><h3>Moved</h3><div class=\"fobs\">").append(html(
                    "In the same method as the previous stop: " + n.movement())).append("</div></div>");
        if (!n.variables().isEmpty()) {
            out.append("<div class=\"fsec\"><h3>Variables at this stop</h3><table class=\"vars\"><tr><th>Variable</th>"
                    + "<th>Type</th><th>Value</th></tr>");
            for (ActivityPage.Variable v : n.variables()) {
                out.append(v.changed() ? "<tr class=\"changed\">" : "<tr>").append("<td>").append(html(v.name()))
                        .append("</td><td>").append(html(v.type())).append("</td><td>").append(html(v.value()));
                if (v.changed()) out.append(" <span class=\"was\">(was ").append(html(v.previous())).append(")</span>");
                out.append("</td></tr>");
            }
            out.append("</table>");
            if (n.variables().stream().anyMatch(ActivityPage.Variable::changed))
                out.append("<div class=\"note\">Highlighted: changed since the previous stop.</div>");
            out.append("</div>");
        }
        for (Expansion e : n.expansions()) {
            out.append("<div class=\"fsec\"><details class=\"ffold\"><summary>Fields of <b>").append(html(e.path()))
                    .append("</b> (expanded ").append(e.count() == 1 ? "once" : e.count() + " times").append(", ")
                    .append(e.fields().size()).append(e.fields().size() == 1 ? " field" : " fields")
                    .append(")</summary><table class=\"vars\"><tr><th>Field</th><th>Type</th><th>Value</th></tr>");
            for (ActivityPage.Variable f : e.fields())
                out.append("<tr><td>").append(html(f.name())).append("</td><td>").append(html(f.type())).append("</td><td>")
                        .append(html(f.value())).append("</td></tr>");
            out.append("</table></details></div>");
        }
        if (!n.expressions().isEmpty()) {
            out.append("<div class=\"fsec\"><h3>Expressions evaluated</h3>");
            for (Expression e : n.expressions())
                out.append("<div class=\"fexpr").append(e.failed() ? " failed" : "").append("\">").append(html(e.expression()))
                        .append("<span class=\"res\">").append(e.failed() ? "refused: " : "→ ").append(html(e.result()))
                        .append("</span></div>");
            out.append("</div>");
        }
    }

    /** The calls as chips, and every call with its details folded below them. */
    private static void calls(ActivityStore.Investigation inv, Node n, StringBuilder out) {
        if (n.steps().isEmpty()) return;
        out.append("<div class=\"fsec\"><h3>").append(n.kind() == Kind.STOP ? "Calls at this stop" : "Calls").append("</h3>");
        for (Call c : n.calls())
            out.append("<span class=\"fchip").append(c.refused() ? " ref" : "").append("\" title=\"")
                    .append(c.refused() ? "Refused" : "Succeeded").append("\">").append(html(c.label()))
                    .append(c.refused() ? " (refused)" : "").append("</span>");
        out.append("<details class=\"ffold\"><summary>Show the calls with their arguments and results</summary>");
        for (int s : n.steps()) {
            ActivityStore.Step step = inv.steps().get(s);
            out.append("<div class=\"fcall\"><div class=\"ct\">").append(inv.droppedSteps() + s + 1).append(". ")
                    .append(ActivityPage.links(html(step.title() == null ? "" : step.title())))
                    .append("FAILED".equals(step.status()) ? " <span class=\"ftag ref\">REFUSED</span>" : "")
                    .append("</div>").append(ActivityPage.stepBody(step)).append("</div>");
        }
        out.append("</details></div>");
    }

    private static String controls(List<Node> nodes) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++)
            dots.append("<a href=\"#fd").append(i).append("\"").append(nodes.get(i).breakpointHit() ? " class=\"bp\"" : "")
                    .append(" title=\"").append(html(nodes.get(i).name())).append("\" onclick=\"flStop();return flShow(")
                    .append(i).append(")\"></a>");
        return "<div id=\"fctl\"><button onclick=\"flStop();return flShow(fl.cur-1)\" title=\"Previous place (←)\">← Previous"
                + "</button><button class=\"p\" onclick=\"flStop();return flShow(fl.cur+1)\" title=\"Next place (→)\">Next →"
                + "</button><button id=\"freplay\" onclick=\"return flReplay()\" title=\"Go through the flow from the start (R)\">"
                + "&#x27F2; Replay</button><span class=\"fn\" id=\"fnum\"></span><div class=\"fprog\"><div id=\"fbar\"></div>"
                + "</div><span class=\"fdots\">" + dots + "</span><span class=\"fhint\">click a node · keys ← → R</span></div>";
    }

    private static String html(String text) {
        return ReportFormat.html(text == null ? "" : text);
    }
}

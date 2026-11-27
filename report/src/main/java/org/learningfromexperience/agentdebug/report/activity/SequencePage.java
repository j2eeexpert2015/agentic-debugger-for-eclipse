package org.learningfromexperience.agentdebug.report.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * OPEN-65 (Ayan 28 Nov, mockup v4): the Sequence view of Agent Activity, next to Flow and Steps. Built only from
 * what is recorded: the stops ({@link FlowPage#nodes}, the same nodes as Flow), the steps with their start and end
 * times, and the agent's analysis at the finish. Design: docs/design/sequence-view.md.
 *
 * <ul>
 *   <li><b>Diagram:</b> one column per class the program stopped in (first appearance first), one box per stop, time
 *       down. A line joins consecutive stops: a <i>call</i> when the previous stop's method is in this stop's call
 *       stack (observed), a <i>step</i> when it is the same method on another line (observed), <i>back in the
 *       caller</i> when this stop's method was in the previous stop's call stack (inferred), else <i>next stop</i>.
 *       Each line carries the agent's thinking time before it (the gaps between one call's end and the next call's
 *       start). At the finish, the stop at the agent's root cause location is marked (the agent's statement).</li>
 *   <li><b>Selected:</b> the details of a stop (exactly what its Flow card shows), of a line (from, to, kind, the
 *       command the agent sent, thinking time, result, the calls in between) or of a class (its stops). The page
 *       script shows one of these blocks; all are rendered, so a selection survives live updates.</li>
 *   <li><b>Timeline:</b> every step with its time since the start, and the agent's thinking pauses of 3 s or more.</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
final class SequencePage {

    /** Thinking pauses shorter than this are not shown in the timeline (seconds). */
    static final int THINK_SHOWN_SECONDS = 3;
    private static final Pattern FRAME = Pattern.compile("^(.+)\\.([^.:]+):(\\d+)$");

    private SequencePage() {
    }

    /** How the program got from one stop to the next. */
    enum Move { ENTRY, CALL, STEP, RETURN, NEXT }

    record Stop(int number, FlowPage.Node node, String cls, String method, int line, int lane) { }

    record Edge(int to, Move move, String label, String command, long thinkingMs, List<Integer> steps) { }

    record Model(List<String> lanes, List<Stop> stops, List<Edge> edges, int rootStop) { }

    /** The diagram's data; no HTML. */
    static Model model(ActivityStore.Investigation inv) {
        List<FlowPage.Node> nodes = FlowPage.nodes(inv);
        List<String> lanes = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        for (FlowPage.Node n : nodes) {
            if (n.kind() != FlowPage.Kind.STOP || n.where() == null) continue;
            Matcher m = FRAME.matcher(n.where());
            if (!m.matches()) continue;
            String cls = simple(m.group(1));
            if (!lanes.contains(cls)) lanes.add(cls);
            stops.add(new Stop(stops.size() + 1, n, cls, m.group(2), Integer.parseInt(m.group(3)), lanes.indexOf(cls)));
        }
        List<ActivityStore.Step> steps = inv.steps();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            int first = s.node().steps().isEmpty() ? 0 : s.node().steps().get(0);
            // From the previous stop's own step (the calls made there, the command that moved on) to this stop's step.
            int from = i == 0 ? -1 : first(stops.get(i - 1).node());
            List<Integer> between = new ArrayList<>();
            for (int k = from + 1; k < first && k < steps.size(); k++) if (k >= 0) between.add(k);
            String command = null;
            for (int k : between) {
                String t = steps.get(k).title();
                if (t != null && "STEP".equals(steps.get(k).kind())) command = t;
            }
            long thinking = 0;
            List<Integer> gaps = new ArrayList<>(between);
            if (first < steps.size()) gaps.add(first);
            for (int k : gaps) {
                if (k <= 0 || k <= from) continue;
                long gap = epoch(steps.get(k).startedAt()) - epoch(steps.get(k - 1).finishedAt());
                if (epoch(steps.get(k).startedAt()) > 0 && epoch(steps.get(k - 1).finishedAt()) > 0 && gap > 0) thinking += gap;
            }
            Move move;
            String label;
            if (i == 0) {
                move = Move.ENTRY;
                label = "first stop";
            } else {
                Stop p = stops.get(i - 1);
                if (p.cls().equals(s.cls()) && p.method().equals(s.method())) {
                    move = Move.STEP;
                    label = "line " + p.line() + " → " + s.line();
                } else if (inStack(s.node().frames(), p.cls(), p.method())) {
                    move = Move.CALL;
                    label = "calls " + s.method() + "()";
                } else if (inStack(p.node().frames(), s.cls(), s.method())) {
                    move = Move.RETURN;
                    label = "back in " + s.cls() + "." + s.method();
                } else {
                    move = Move.NEXT;
                    label = "next stop";
                }
            }
            edges.add(new Edge(i, move, label, command, thinking, List.copyOf(between)));
        }
        return new Model(List.copyOf(lanes), List.copyOf(stops), List.copyOf(edges), rootStop(inv, stops));
    }

    /** The view's HTML for the live Agent Activity page (empty before the first step). */
    static String live(ActivityStore.Investigation inv) {
        if (inv == null) return "";
        Model m = model(inv);
        StringBuilder out = new StringBuilder("<div class=\"sq\"><div class=\"sqp sqd-flow\"><div class=\"sqh\"><b>Where the program"
                + " stopped</b><small>classes across · time down · only real stops</small></div>");
        if (m.stops().isEmpty()) {
            out.append("<div class=\"sqempty\">No stop yet. The boxes appear when the program stops at a breakpoint;"
                    + " until then the timeline shows what the agent does.</div>");
        } else {
            out.append(svg(inv, m));
            out.append("<div class=\"sqleg\"><span><i></i>call (caller in the stack)</span><span><i class=\"s\"></i>step,"
                    + " same method</span><span><i class=\"r\"></i>back in the caller (inferred)</span>"
                    + "<span class=\"th\">thinking N s = the agent's time between calls</span></div>");
        }
        out.append("</div><div class=\"sqp sqd-sel\"><div class=\"sqh\"><b id=\"sqselt\">Selected</b><small>click a box, a line"
                + " or a class</small></div><div class=\"sqblocks\">");
        blocks(inv, m, out);
        out.append("</div></div><div class=\"sqp sqd-tl\"><div class=\"sqh\"><b>Timeline</b><small>every step and"
                + " thinking pause</small></div><div class=\"sqtl\">");
        timeline(inv, m, out);
        out.append("</div></div></div>");
        return out.toString();
    }

    // ---- diagram ---------------------------------------------------------------------------------------------

    private static String svg(ActivityStore.Investigation inv, Model m) {
        int lanes = m.lanes().size();
        int width = Math.max(420, lanes * 180);
        double laneW = (width - 20.0) / lanes;
        int top = 52, row = 80, bw = (int) Math.min(150, laneW - 14);
        int height = top + 30 + m.stops().size() * row;
        int current = inv.finished() ? -1 : current(inv, m);
        StringBuilder s = new StringBuilder("<svg class=\"sqsvg\" viewBox=\"0 0 " + width + " " + height + "\" role=\"img\""
                + " aria-label=\"Stops by class\"><defs>"
                + "<filter id=\"sqglow\" x=\"-30%\" y=\"-30%\" width=\"160%\" height=\"160%\"><feGaussianBlur stdDeviation=\"3\""
                + " result=\"b\"/><feFlood flood-color=\"#06b6d4\" flood-opacity=\".75\"/><feComposite in2=\"b\" operator=\"in\"/>"
                + "<feMerge><feMergeNode/><feMergeNode in=\"SourceGraphic\"/></feMerge></filter>"
                + "<marker id=\"sqa\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"9\" markerHeight=\"9\" markerUnits=\"userSpaceOnUse\" orient=\"auto\">"
                + "<path d=\"M0 0L10 5L0 10Z\" class=\"sqmk\"/></marker>"
                + "<marker id=\"sqar\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"9\" markerHeight=\"9\" markerUnits=\"userSpaceOnUse\" orient=\"auto\">"
                + "<path d=\"M0 0L10 5L0 10Z\" class=\"sqmkr\"/></marker></defs>");
        int currentLane = current >= 0 ? m.stops().get(current).lane() : -1;
        for (int l = 0; l < lanes; l++) {
            double x = x(l, laneW);
            s.append("<g class=\"sqlane").append(l == currentLane ? " cur" : "").append("\" data-k=\"c").append(l)
                    .append("\" onclick=\"return adSq('c").append(l).append("')\"><rect x=\"").append(fmt(x - laneW / 2 + 4))
                    .append("\" y=\"6\" width=\"").append(fmt(laneW - 8)).append("\" height=\"30\" rx=\"7\"/><text x=\"")
                    .append(fmt(x)).append("\" y=\"25\" text-anchor=\"middle\">").append(html(m.lanes().get(l)))
                    .append("</text></g><path class=\"sqguide\" d=\"M").append(fmt(x)).append(" 36V").append(height - 4)
                    .append("\"/>");
        }
        for (Edge e : m.edges()) {
            Stop to = m.stops().get(e.to());
            double xb = x(to.lane(), laneW);
            int yb = top + 30 + e.to() * row;
            String cls = switch (e.move()) {
                case STEP -> "step";
                case RETURN -> "ret";
                case ENTRY -> "entry";
                default -> "call";
            };
            String path;
            double lx, ly;
            if (e.move() == Move.ENTRY) {
                path = "M" + fmt(xb) + " " + (top - 6) + "V" + (yb - 16);
                lx = xb + 8;
                ly = yb - 22;
            } else {
                Stop from = m.stops().get(e.to() - 1);
                double xa = x(from.lane(), laneW);
                int ya = top + 30 + (e.to() - 1) * row;
                if (from.lane() == to.lane()) {
                    path = "M" + fmt(xb) + " " + (ya + 18) + "V" + (yb - 16);
                    lx = xb + 8;
                    ly = (ya + yb) / 2.0 + 2;
                } else {
                    int mid = ya + 30;
                    int dir = xb > xa ? 1 : -1;
                    path = "M" + fmt(xa + dir * bw / 2.0) + " " + ya + "H" + fmt(xa + dir * (bw / 2.0 + 8)) + "V" + mid
                            + "H" + fmt(xb) + "V" + (yb - 16);
                    lx = Math.min(xa, xb) + bw / 2.0 + 14;
                    ly = mid + 13;
                }
            }
            boolean active = e.to() == current;
            s.append("<g class=\"sqedge ").append(cls).append(active ? " active" : "").append("\" data-k=\"e").append(e.to())
                    .append("\" onclick=\"return adSq('e").append(e.to()).append("')\"><path class=\"ln\" d=\"").append(path)
                    .append("\" marker-end=\"url(#").append(e.move() == Move.RETURN ? "sqar" : "sqa").append(")\"/>")
                    .append("<path class=\"hit\" d=\"").append(path).append("\"/>")
                    .append(label(width, lx, ly, e.label(), "lb"));
            if (e.thinkingMs() >= THINK_SHOWN_SECONDS * 1000L)
                s.append(label(width, lx, ly + 12, "thinking " + ActivityPage.duration(e.thinkingMs() / 1000 * 1000), "th"));
            s.append("</g>");
        }
        for (Stop st : m.stops()) {
            double x = x(st.lane(), laneW);
            int y = top + 30 + (st.number() - 1) * row;
            boolean root = inv.finished() && st.number() - 1 == m.rootStop();
            String changed = newValues(m, st);
            int h = changed.isEmpty() ? 32 : 42;
            s.append("<g class=\"sqbox").append(root ? " root" : st.number() - 1 == current ? " cur" : "")
                    .append("\" data-k=\"s").append(st.number() - 1).append("\" onclick=\"return adSq('s")
                    .append(st.number() - 1).append("')\"><rect x=\"").append(fmt(x - bw / 2.0)).append("\" y=\"")
                    .append(y - 16).append("\" width=\"").append(bw).append("\" height=\"").append(h).append("\" rx=\"6\"/>")
                    .append("<text class=\"n\" x=\"").append(fmt(x - bw / 2.0 + 6)).append("\" y=\"").append(y - 3).append("\">")
                    .append(String.format("%02d", st.number())).append(root ? "  ROOT CAUSE" : "").append("</text>")
                    .append("<text class=\"l\" x=\"").append(fmt(x + bw / 2.0 - 6)).append("\" y=\"").append(y - 3)
                    .append("\" text-anchor=\"end\">line ").append(st.line()).append("</text>")
                    .append("<text x=\"").append(fmt(x - bw / 2.0 + 6)).append("\" y=\"").append(y + 10).append("\">")
                    .append(html(st.method())).append("()</text>");
            if (!changed.isEmpty())
                s.append("<text class=\"c\" x=\"").append(fmt(x - bw / 2.0 + 6)).append("\" y=\"").append(y + 22).append("\">")
                        .append(html(cut(changed, 26))).append("</text>");
            s.append("</g>");
        }
        return s.append("</svg>").toString();
    }

    /** A line label, right-aligned when it would run past the diagram's right edge. */
    private static String label(int width, double x, double y, String text, String cls) {
        boolean right = x + text.length() * 5.4 > width - 4;
        return "<text class=\"" + cls + "\" x=\"" + fmt(right ? width - 4 : x) + "\" y=\"" + fmt(y) + "\""
                + (right ? " text-anchor=\"end\"" : "") + ">" + html(text) + "</text>";
    }

    // ---- selected blocks -------------------------------------------------------------------------------------

    private static void blocks(ActivityStore.Investigation inv, Model m, StringBuilder out) {
        for (Stop st : m.stops()) {
            boolean root = inv.finished() && st.number() - 1 == m.rootStop();
            out.append("<div class=\"sqb\" data-b=\"s").append(st.number() - 1).append("\" data-t=\"Stop ").append(st.number())
                    .append(" of ").append(m.stops().size()).append("\"><h3>").append(html(st.node().where()))
                    .append(root ? " <span class=\"ftag root\">ROOT CAUSE</span> <span class=\"ftag agent\">FROM THE AGENT</span>"
                            : "").append("</h3><div class=\"fd\">");
            FlowPage.body(inv, st.node(), out);
            out.append("</div></div>");
        }
        List<ActivityStore.Step> steps = inv.steps();
        for (Edge e : m.edges()) {
            Stop to = m.stops().get(e.to());
            String from = e.to() == 0 ? "the start" : m.stops().get(e.to() - 1).node().where();
            boolean inferred = e.move() == Move.RETURN;
            boolean unknown = e.move() == Move.NEXT;
            out.append("<div class=\"sqb\" data-b=\"e").append(e.to()).append("\" data-t=\"Move to stop ").append(e.to() + 1)
                    .append("\"><h3>").append(html(from + " → " + to.node().where())).append("</h3><div class=\"fsec\"><h3>")
                    .append(html(moveName(e.move()))).append(" <span class=\"ftag ")
                    .append(inferred ? "inf\">INFERRED" : unknown ? "never\">NOT KNOWN" : "obs\">OBSERVED").append("</span></h3>")
                    .append("<div class=\"fobs\">").append(html(moveText(e.move()))).append("</div></div>");
            if (e.command() != null)
                out.append("<div class=\"fsec\"><h3>Command the agent sent</h3><div class=\"fobs\">").append(ActivityPage.links(html(e.command())))
                        .append("</div></div>");
            if (e.thinkingMs() >= 1000)
                out.append("<div class=\"fsec\"><h3>Before it</h3><div class=\"fobs\">The agent was thinking ")
                        .append(html(ActivityPage.duration(e.thinkingMs() / 1000 * 1000))).append(" in total.</div></div>");
            if (!e.steps().isEmpty()) {
                out.append("<div class=\"fsec\"><h3>Steps in between <span class=\"ftag obs\">OBSERVED</span></h3><div class=\"fobs\">");
                for (int k : e.steps())
                    out.append(ActivityPage.links(html(steps.get(k).title() == null ? "" : steps.get(k).title())))
                            .append("FAILED".equals(steps.get(k).status()) ? " <span class=\"ftag ref\">REFUSED</span>" : "")
                            .append('\n');
                out.append("</div></div>");
            }
            out.append("</div>");
        }
        for (int l = 0; l < m.lanes().size(); l++) {
            out.append("<div class=\"sqb\" data-b=\"c").append(l).append("\" data-t=\"").append(html(m.lanes().get(l)))
                    .append("\"><h3>").append(html(m.lanes().get(l))).append("</h3><table class=\"vars\"><tr><th>#</th><th>Method</th>"
                            + "<th>Line</th><th>New values</th></tr>");
            for (Stop st : m.stops()) {
                if (st.lane() != l) continue;
                out.append("<tr onclick=\"return adSq('s").append(st.number() - 1).append("')\" style=\"cursor:pointer\"><td>")
                        .append(st.number()).append("</td><td>").append(html(st.method())).append("()</td><td>").append(st.line())
                        .append("</td><td class=\"chg\">").append(html(newValues(m, st))).append("</td></tr>");
            }
            out.append("</table></div>");
        }
        for (int k = 0; k < steps.size(); k++) {
            ActivityStore.Step step = steps.get(k);
            ReportFormat.Details d = ReportFormat.split(step.details());
            out.append("<div class=\"sqb\" data-b=\"t").append(k).append("\" data-t=\"Step ").append(inv.droppedSteps() + k + 1)
                    .append("\"><h3>").append(ActivityPage.links(html(step.title() == null ? "" : step.title())))
                    .append("FAILED".equals(step.status()) ? " <span class=\"ftag ref\">REFUSED</span>" : "")
                    .append(step.running() ? " <span class=\"ftag bp\">RUNNING</span>" : "").append("</h3>");
            if (!d.reader().isEmpty())
                out.append("<div class=\"fsec\"><div class=\"fobs\">").append(ActivityPage.links(html(String.join("\n", d.reader()))))
                        .append("</div></div>");
            if (!d.technical().isEmpty())
                out.append("<details class=\"ffold\"><summary>").append(ReportFormat.MORE_DETAILS_LABEL).append("</summary><div class=\"fobs\">")
                        .append(html(String.join("\n", d.technical()))).append("</div></details>");
            out.append("</div>");
        }
    }

    // ---- timeline --------------------------------------------------------------------------------------------

    private static void timeline(ActivityStore.Investigation inv, Model m, StringBuilder out) {
        List<ActivityStore.Step> steps = inv.steps();
        long start = epoch(inv.startedAt());
        Map<Integer, Integer> stopOfStep = new LinkedHashMap<>();
        for (Stop st : m.stops()) if (!st.node().steps().isEmpty()) stopOfStep.put(st.node().steps().get(0), st.number() - 1);
        for (int k = 0; k < steps.size(); k++) {
            ActivityStore.Step step = steps.get(k);
            if (k > 0) {
                long gap = epoch(step.startedAt()) - epoch(steps.get(k - 1).finishedAt());
                if (epoch(step.startedAt()) > 0 && epoch(steps.get(k - 1).finishedAt()) > 0 && gap >= THINK_SHOWN_SECONDS * 1000L)
                    out.append("<div class=\"sqev think\"><span class=\"t\"></span><span class=\"k\"></span><span>Agent thinking · ")
                            .append(html(ActivityPage.duration(gap / 1000 * 1000))).append("</span></div>");
            }
            Integer stop = stopOfStep.get(k);
            String key = stop != null ? "s" + stop : "t" + k;
            long at = epoch(step.startedAt());
            String kind = stop != null ? "stop" : "FAILED".equals(step.status()) ? "bad" : "CLEANUP".equals(step.kind()) ? "end" : "x";
            boolean last = k == steps.size() - 1 && !inv.finished();
            out.append("<div class=\"sqev ").append(kind).append(last ? " now" : "").append("\" data-k=\"").append(key)
                    .append("\" onclick=\"return adSq('").append(key).append("')\"><span class=\"t\">")
                    .append(start > 0 && at >= start ? clock(at - start) : "").append("</span><span class=\"k\"></span><span>")
                    .append(html(step.title() == null ? "" : step.title()))
                    .append("FAILED".equals(step.status()) ? " <span class=\"ftag ref\">REFUSED</span>" : "")
                    .append("</span></div>");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** The stop the program is at now: the newest, while the program is paused. -1 otherwise. */
    private static int current(ActivityStore.Investigation inv, Model m) {
        if (m.stops().isEmpty() || !ActivityStore.PROGRAM_PAUSED.equals(inv.program())) return -1;
        return m.stops().size() - 1;
    }

    /** The stop at the agent's root cause location (the first one), or -1. */
    private static int rootStop(ActivityStore.Investigation inv, List<Stop> stops) {
        FlowPage.Analysis a = FlowPage.analysis(inv);
        if (a.rootFile() == null || a.rootLines() == null) return -1;
        Matcher line = Pattern.compile("\\d+").matcher(a.rootLines());
        if (!line.find()) return -1;
        String given = a.rootFile() + ", line " + line.group();
        for (Stop st : stops) if (FlowPage.sameLocation(given, st.node().where())) return st.number() - 1;
        return -1;
    }

    private static boolean inStack(String frames, String cls, String method) {
        if (frames == null) return false;
        String[] chain = frames.split("\\s+<\\s+");
        for (int i = 1; i < chain.length; i++) {
            Matcher m = FRAME.matcher(chain[i].strip());
            if (m.matches() && simple(m.group(1)).equals(cls) && m.group(2).equals(method)) return true;
        }
        return false;
    }

    /**
     * What is new at a stop compared with the previous stop in the same method: values that changed, and variables
     * that were not there yet (a local just assigned). Nothing for the first stop in a method (all would be "new").
     */
    static String newValues(Model m, Stop st) {
        Stop before = null;
        for (Stop s : m.stops())
            if (s.number() < st.number() && s.cls().equals(st.cls()) && s.method().equals(st.method())) before = s;
        if (before == null) return "";
        Map<String, String> was = new LinkedHashMap<>();
        for (ActivityPage.Variable v : before.node().variables()) was.put(v.name(), v.value());
        List<String> out = new ArrayList<>();
        for (ActivityPage.Variable v : st.node().variables())
            if (!v.value().equals(was.get(v.name()))) out.add(v.name() + " = " + v.value());
        return out.isEmpty() ? "" : "new: " + String.join(", ", out);
    }

    private static int first(FlowPage.Node n) {
        return n.steps().isEmpty() ? -1 : n.steps().get(0);
    }

    private static String moveName(Move move) {
        return switch (move) {
            case ENTRY -> "The first stop";
            case CALL -> "A call";
            case STEP -> "A step in the same method";
            case RETURN -> "Back in the caller";
            case NEXT -> "The next stop";
        };
    }

    private static String moveText(Move move) {
        return switch (move) {
            case ENTRY -> "The program reached its first breakpoint here.";
            case CALL -> "The method of this stop was called by the method of the previous stop: the previous stop's"
                    + " method is in this stop's call stack.";
            case STEP -> "The program stopped on another line of the same method as the previous stop.";
            case RETURN -> "The program stopped in the method that had called the previous stop's method. Inferred: the"
                    + " previous stop's call stack named this method as its caller.";
            case NEXT -> "The program went on to the next breakpoint; how the two places are connected was not recorded"
                    + " (no call stack names the other).";
        };
    }

    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static double x(int lane, double laneW) {
        return 10 + laneW * lane + laneW / 2;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static long epoch(String iso) {
        try {
            return iso == null ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException unparsable) {
            return 0;
        }
    }

    private static String html(String text) {
        return ReportFormat.html(text);
    }

    /** Styles of the Sequence view (light and dark). */
    static final String CSS = """
            #vbar #vseq.on{background:#1d4ed8;color:#fff;border-color:#1d4ed8}
            body:not(.vseq) #seq{display:none}
            body.vseq #flow,body.vseq #steps,body.vseq #stop{display:none!important}
            body.vseq #analysis{display:block!important}
            .sq{display:grid;grid-template-columns:minmax(380px,2.2fr) minmax(240px,1fr) minmax(220px,.95fr);gap:10px;align-items:start;margin-top:8px}
            @media (max-width:820px){.sq{grid-template-columns:1fr}}
            .sqp{border:1px solid #94a3b8;border-radius:8px;min-width:0;background:#fff}
            .sqh{padding:6px 10px;border-bottom:1px solid #94a3b8;display:flex;justify-content:space-between;gap:8px;align-items:baseline}
            .sqh b{font-size:13px;color:#0b1220}.sqh small{font-size:11px;color:#1e293b}
            .sqempty{padding:14px;color:#0b1220}
            .sqsvg{display:block;width:100%;height:auto}
            .sqlane{cursor:pointer}.sqlane rect{fill:#e8eef7;stroke:#64748b}.sqlane text{font-size:12px;font-weight:700;fill:#0b1220}
            .sqlane.cur rect{fill:#cffafe;stroke:#06b6d4;stroke-width:2.6;filter:url(#sqglow);animation:sq-lane 1.3s ease-in-out infinite}
            .sqlane.sel rect{stroke:#5b21b6;stroke-width:2.8}
            @keyframes sq-lane{50%{stroke-width:4;opacity:.8}}
            .sqguide{stroke:#64748b;stroke-dasharray:3 6}
            .sqedge{cursor:pointer}.sqedge .ln{fill:none;stroke:#334155;stroke-width:1.9}.sqedge .hit{fill:none;stroke:transparent;stroke-width:14}
            .sqedge.step .ln{stroke-dasharray:5 4}.sqedge.entry .ln{stroke-dasharray:2 3}
            .sqedge.ret .ln{stroke:#5b21b6;stroke-dasharray:2 4}
            .sqedge.active .ln{stroke:#06b6d4;stroke-width:2.8;stroke-dasharray:7 5;animation:sq-dash .7s linear infinite;filter:url(#sqglow)}
            .sqedge.sel .ln{stroke:#5b21b6;stroke-width:3.2}
            @keyframes sq-dash{to{stroke-dashoffset:-24}}
            .sqedge .lb{font-size:9.5px;fill:#1e293b}.sqedge.ret .lb{fill:#5b21b6}.sqedge .th{font-size:9.5px;fill:#9a3412;font-style:italic}
            .sqmk{fill:#334155}.sqmkr{fill:#5b21b6}
            .sqbox{cursor:pointer}.sqbox rect{fill:#fff;stroke:#14532d;stroke-width:1.6}
            .sqbox text{font-size:11px;fill:#0b1220}.sqbox .n{font-size:9.5px;font-weight:800;fill:#0e7490}
            .sqbox .l{font-size:10px;fill:#1e293b}.sqbox .c{font-size:9.5px;fill:#92400e;font-weight:700}
            .sqbox.cur rect{stroke:#06b6d4;stroke-width:2.6;filter:url(#sqglow)}.sqbox.cur{animation:sq-pulse 1.3s ease-in-out infinite}
            .sqbox.root rect{stroke:#991b1b;stroke-width:2.4;fill:#fee2e2}.sqbox.root .n{fill:#991b1b}
            .sqbox.sel rect{stroke:#5b21b6!important;stroke-width:2.8}
            @keyframes sq-pulse{50%{opacity:.82}}
            .sqleg{display:flex;flex-wrap:wrap;gap:4px 14px;padding:6px 10px;border-top:1px solid #94a3b8;font-size:10.5px;color:#1e293b}
            .sqleg i{display:inline-block;width:22px;border-top:2px solid #334155;vertical-align:middle;margin-right:5px}
            .sqleg i.s{border-top-style:dashed}.sqleg i.r{border-top:2px dotted #5b21b6}.sqleg .th{color:#9a3412}
            .sqblocks{padding:8px 10px}.sqb{display:none}.sqb.on{display:block}
            .sqb h3{font-size:13.5px;margin:2px 0 6px;font-family:Consolas,'Cascadia Mono',monospace;color:#0b1220;overflow-wrap:anywhere}
            .sqb td.chg{color:#92400e;font-weight:700}
            .sqtl{max-height:560px;overflow:auto;padding:4px 6px}
            .sqev{display:flex;gap:7px;padding:6px 5px;border-bottom:1px solid #cbd5e1;cursor:pointer;align-items:baseline;border-radius:6px;color:#0b1220;font-size:12px}
            .sqev .t{color:#1e293b;font-variant-numeric:tabular-nums;font-size:10.5px;flex:none;width:32px}
            .sqev .k{flex:none;width:8px;height:8px;border-radius:50%;background:#64748b;align-self:center}
            .sqev.stop .k{background:#0e7490}.sqev.bad .k{background:#d97706}.sqev.end .k{background:#15803d}
            .sqev.think{cursor:default;font-style:italic;color:#9a3412;padding:3px 5px}.sqev.think .k{background:transparent;border:1.5px dashed #c2410c}
            .sqev.now{background:#ecfeff;box-shadow:0 0 0 2px #06b6d4 inset,0 0 14px rgba(6,182,212,.4);animation:sq-row 1.6s ease-in-out infinite}
            .sqev.now .k{background:#dc2626;animation:sq-pulse .9s ease-in-out infinite}
            .sqev.sel{box-shadow:0 0 0 2px #5b21b6 inset}
            @keyframes sq-row{50%{box-shadow:0 0 0 2px #06b6d4 inset,0 0 22px rgba(6,182,212,.6)}}
            .ftag.inf{background:transparent;color:#92400e;border:1px solid #fcd34d;font-size:9.5px;font-weight:600;padding:0 6px}
            .ftag.root{background:#991b1b;color:#fff;border:1px solid #991b1b}
            body.dark .sqp{background:#141c2a;border-color:#33435a}body.dark .sqh{border-color:#33435a}
            body.dark .sqh b,body.dark .sqempty,body.dark .sqb h3,body.dark .sqev{color:#f1f5fb}
            body.dark .sqh small,body.dark .sqleg,body.dark .sqev .t{color:#c3cfdf}
            body.dark .sqlane rect{fill:#1b2638;stroke:#64748b}body.dark .sqlane text,body.dark .sqbox text{fill:#f1f5fb}
            body.dark .sqlane.cur rect{fill:rgba(34,211,238,.18);stroke:#22d3ee}
            body.dark .sqguide{stroke:#475569}
            body.dark .sqedge .ln{stroke:#94a3b8}body.dark .sqedge .lb,body.dark .sqbox .l{fill:#c3cfdf}
            body.dark .sqedge.ret .ln{stroke:#c4a1ff}body.dark .sqedge.ret .lb{fill:#c4a1ff}body.dark .sqmkr{fill:#c4a1ff}
            body.dark .sqedge .th,body.dark .sqleg .th{fill:#fdba74;color:#fdba74}
            body.dark .sqmk{fill:#94a3b8}
            body.dark .sqbox rect{fill:#141c2a;stroke:#4ade80}body.dark .sqbox .n{fill:#22d3ee}body.dark .sqbox .c{fill:#fbbf24}
            body.dark .sqbox.root rect{fill:rgba(255,107,129,.18);stroke:#ff6b81}
            body.dark .sqev{border-color:#33435a}body.dark .sqev.think{color:#fdba74}
            body.dark .sqev.now{background:rgba(34,211,238,.12)}
            body.dark .sqleg{border-color:#33435a}
            """;

    /** The page's script for the Sequence view: one selection (a stop, a line, a class or a step), kept across updates. */
    static final String SCRIPT = """
            function adSq(k){ad.sq=k;adSqApply();var s=document.querySelector('.sqd-sel');
              if(s&&window.innerWidth<820)s.scrollIntoView({block:'start',behavior:'smooth'});return false;}
            function adSqApply(){var root=document.getElementById('seq');if(!root)return;
              var k=ad.sq,blocks=root.querySelectorAll('.sqb');
              if(!k||!root.querySelector('.sqb[data-b="'+k+'"]')){var cur=root.querySelector('.sqbox.cur,.sqev.now');
                var boxes=root.querySelectorAll('.sqbox');k=cur?cur.getAttribute('data-k'):boxes.length?boxes[boxes.length-1].getAttribute('data-k'):null;
                var rows=root.querySelectorAll('.sqev[data-k]');if(!k&&rows.length)k=rows[rows.length-1].getAttribute('data-k');}
              blocks.forEach(function(b){b.classList.toggle('on',b.getAttribute('data-b')===k);});
              var on=root.querySelector('.sqb.on');var t=document.getElementById('sqselt');if(t)t.textContent=on?on.getAttribute('data-t'):'Selected';
              root.querySelectorAll('[data-k]').forEach(function(e){e.classList.toggle('sel',!!ad.sq&&e.getAttribute('data-k')===ad.sq);});
              var tl=root.querySelector('.sqtl');if(tl&&!ad.sq&&ad.follow&&!ad.paused)tl.scrollTop=tl.scrollHeight;}
            """;
}

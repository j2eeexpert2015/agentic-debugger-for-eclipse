import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
public final class InstallAgentHandoff {
  record Change(Path file,String before,String after) {}
  static String one(String s,String old,String replacement) {
    if (!s.contains(old) || s.indexOf(old)!=s.lastIndexOf(old)) throw new IllegalStateException("Missing or ambiguous anchor: "+old);
    return s.replace(old,replacement);
  }
  public static void main(String[] args) throws Exception {
    if(args.length>1 || (args.length==1 && !args[0].equals("--apply"))) throw new IllegalArgumentException("Usage: java maintenance/InstallAgentHandoff.java [--apply]");
    boolean apply=args.length==1;
    Path root=Path.of("").toAbsolutePath().normalize();
    if(!Files.isRegularFile(root.resolve("pom.xml")) || !Files.isRegularFile(root.resolve("docs/current-state.md"))) throw new IllegalStateException("Run from repo root after extracting ZIP");
    List<Change> changes=new ArrayList<>();
    for(String rel:List.of("AGENTS.md","README.md","docs/development-workflow.md")) {
      Path file=root.resolve(rel); String orig=Files.readString(file,StandardCharsets.UTF_8), s=orig;
      String nl=orig.contains("\r\n")?"\r\n":"\n";
      if(rel.equals("AGENTS.md") && !s.contains("## New-agent orientation: reading order")) {
        String x="## New-agent orientation: reading order\n\nBefore changing code, check the current branch/revision and read the relevant documents. Current evidence outranks historical milestone reports.\n\n| Read | Purpose |\n|---|---|\n| [README](README.md) | Product, architecture and quickstart |\n| [Current state](docs/current-state.md) | Latest evidence, blocker and next safe action |\n| [Capabilities](docs/capabilities.md) | What is implemented versus verified |\n| [Feature roadmap](docs/feature-roadmap.md) | Open capability gaps |\n| [Development workflow](docs/development-workflow.md) | Investigation, changes, testing and handoff |\n| [Architecture decisions](docs/decisions.md) | Safety and ownership contracts |\n| [Java E2E](dev-e2e/README.md) | Test commands, environment and approvals |\n| [Claude evidence](docs/claude-acceptance-2026-11-23.md) | Reviewed single-trial observations and limits |\n| [Evaluation plan](docs/evaluation-plan.md) | Agent scoring and experiments |\n| [Development roadmap](docs/development-roadmap.md) | Prioritized next actions |\n\nRead task-relevant files, not every historical report. Raw evaluation/reports/ is ignored by Git; never invent missing evidence or run paid Claude trials without approval.\n\n";
        s=one(s,"## Before changing anything",x.replace("\n",nl)+"## Before changing anything");
      }
      if(rel.equals("README.md")) {
        String d="## Next development priority: Claude symptom-only acceptance"+nl+"## Current milestone and next development priority";
        if(s.contains(d)) s=one(s,d,"## Current milestone and next development priority");
        String label="**Installed Windows direct tests, 23 November 2026:**";
        if(s.contains(label))s=one(s,label,"**Historical uninterrupted Windows direct-test baseline (23 November 2026):**");
        if(!s.contains("[current-state handoff](docs/current-state.md)"))s=one(s,"## Development workflow","## Development workflow"+nl+nl+"Coding agents should begin with [AGENTS.md](AGENTS.md) and the [current-state handoff](docs/current-state.md) before changing code.");
        if(!s.contains("[Current-state handoff](docs/current-state.md)"))s=one(s,"## Documentation","## Documentation"+nl+nl+"[Current-state handoff](docs/current-state.md) — latest verified evidence and next safe task."+nl);
        s=s.replace(". · [Coding-agent instructions](AGENTS.md)"," · [Coding-agent instructions](AGENTS.md)");
      }
      if(rel.equals("docs/development-workflow.md") && !s.contains("[current-state handoff](current-state.md)")) {
        String x="At the start of a new session, consult the [current-state handoff](current-state.md) for the latest verified milestone, blocker and next task. Update it after materially new evidence, not each minor edit.\n\n";
        s=one(s,"## 1. Non-negotiable principles",x.replace("\n",nl)+"## 1. Non-negotiable principles");
      }
      if(!orig.equals(s))changes.add(new Change(file,orig,s));
    }
    for(var c:changes)System.out.println((apply?"UPDATE ":"WOULD UPDATE ")+root.relativize(c.file));
    if(changes.isEmpty()){System.out.println("UNCHANGED: onboarding already installed");return;}
    if(!apply){System.out.println("DRY RUN; use --apply to write changes");return;}
    Path backup=root.resolve("evaluation/reports/agent-handoff-backups/"+Instant.now().toString().replace(':','-'));
    for(var c:changes){Path target=backup.resolve(root.relativize(c.file));Files.createDirectories(target.getParent());Files.writeString(target,c.before,StandardCharsets.UTF_8);}
    for(var c:changes){Path temp=Files.createTempFile(c.file.getParent(),".handoff-",".tmp");try{Files.writeString(temp,c.after,StandardCharsets.UTF_8);try{Files.move(temp,c.file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,c.file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
    System.out.println("APPLIED "+changes.size()+" files; backups: "+backup);
  }
}

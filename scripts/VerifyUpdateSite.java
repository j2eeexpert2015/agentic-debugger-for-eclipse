import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Java-only check for Eclipse p2 update-site ZIPs (used by disabled CI templates). */
class VerifyUpdateSite {
    public static void main(String[] args) throws Exception {
        Path target = Path.of("eclipse-update-site/target");
        List<Path> archives;
        try (var paths = Files.list(target)) {
            archives = paths.filter(p -> p.getFileName().toString().endsWith(".zip")).toList();
        }
        if (archives.size() != 1) throw new IllegalStateException("Expected exactly one update-site ZIP, found " + archives);
        boolean feature = false, plugin = false;
        try (var zip = new ZipFile(archives.get(0).toFile())) {
            var files = zip.entries();
            while (files.hasMoreElements()) {
                String name = files.nextElement().getName();
                if (name.contains("features/") && !name.endsWith("/")) feature = true;
                if (name.contains("plugins/") && !name.endsWith("/")) plugin = true;
            }
        }
        if (!feature || !plugin) throw new IllegalStateException("Incomplete p2 update site: feature=" + feature + " plugin=" + plugin);
        System.out.println("Verified Eclipse update-site ZIP: " + archives.get(0));
    }
}

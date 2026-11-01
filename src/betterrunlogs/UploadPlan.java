package betterrunlogs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Pure upload decisions: what is pending, which statuses end a file, how long to wait. No game classes. */
public final class UploadPlan {
    private UploadPlan() {}

    static final long BASE_BACKOFF_MS = 30_000L;
    static final long MAX_BACKOFF_MS = 1_800_000L;
    public static final long MAX_RAW_BYTES = 40L * 1024 * 1024;

    /** Ledger key: the log's path relative to better-run-logs/, e.g. "IRONCLAD/1730000000.jsonl.gz". */
    /** The main-menu prompt; it mentions logs already on the PC only when there are some. */
    public static String consentBody(boolean hasLogs) {
        return "Get bot reviews of your Heart fights on spirepilot.com, under your Steam name."
                + (hasLogs ? " Includes runs already on your PC." : "")
                + " Change this any time in Mods \u2192 Better Run Logs \u2192 Config.";
    }

    public static String key(File log) {
        File parent = log.getParentFile();
        return (parent == null ? "" : parent.getName() + "/") + log.getName();
    }

    public static Set<String> parseLedger(Collection<String> lines) {
        Set<String> out = new HashSet<>();
        for (String line : lines) {
            String k = line.trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    /** Logs not yet in the ledger, newest first (mtime, then name, descending). */
    public static List<File> pending(Collection<File> logs, Set<String> ledger) {
        List<File> out = new ArrayList<>();
        for (File f : logs) if (!ledger.contains(key(f))) out.add(f);
        out.sort((a, b) -> {
            int byTime = Long.compare(b.lastModified(), a.lastModified());
            return byTime != 0 ? byTime : b.getName().compareTo(a.getName());
        });
        return out;
    }

    /** A terminal status is ledgered and never retried: accepted, duplicate, too large, or refused as invalid. */
    public static boolean isTerminal(int status) {
        return (status >= 200 && status < 300) || status == 409 || status == 413 || status == 422;
    }

    /** The server refused only because it predates this mod version: retry next launch instead of ledgering. */
    public static boolean serverBehind(int status, String reply) {
        return status == 422 && reply != null && reply.contains("unknown log_version");
    }

    public static long backoffMs(int failures) {
        if (failures <= 0) return 0;
        long ms = BASE_BACKOFF_MS;
        for (int i = 1; i < failures && ms < MAX_BACKOFF_MS; i++) ms *= 2;
        return Math.min(ms, MAX_BACKOFF_MS);
    }

    /** Null when the gz is a finished run under a Steam name (run_start first, run_end last); otherwise why it is skipped. */
    public static String logProblem(InputStream gz, long maxRawBytes) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String first = null;
            String last = null;
            long raw = 0;
            String line;
            while ((line = r.readLine()) != null) {
                raw += line.length() + 1;
                if (raw > maxRawBytes) return "over " + maxRawBytes + " bytes uncompressed";
                if (line.trim().isEmpty()) continue;
                if (first == null) first = line;
                last = line;
            }
            if (first == null) return "empty log";
            if (!first.contains("\"t\":\"run_start\"")) return "first line is not run_start";
            if (!first.contains("\"player_name\":\"")) return "no Steam name (the run was not played through Steam)";
            if (!last.contains("\"t\":\"run_end\"")) return "no run_end line";
            return null;
        } catch (IOException e) {
            return "unreadable gzip: " + e;
        }
    }
}

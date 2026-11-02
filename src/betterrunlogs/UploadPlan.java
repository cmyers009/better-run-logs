package betterrunlogs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    /** The main-menu prompt; it mentions logs already on the PC only when there are some. */
    public static String consentBody(boolean hasLogs) {
        return "Get bot reviews of your Heart fights on spirepilot.com, under your Steam name."
                + (hasLogs ? " Includes runs already on your PC." : "")
                + " Change this any time in Mods \u2192 Better Run Logs \u2192 Config.";
    }

    /** Ledger key: the log's path relative to better-run-logs/, e.g. "IRONCLAD/1730000000.jsonl.gz". */
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

    /**
     * Null when the gz should upload: it opens with run_start or resume under a Steam name, and either ends with
     * run_end or (abandoned) holds at least one fight. Otherwise why it is skipped. The server derives the kind.
     */
    public static String logProblem(InputStream gz, long maxRawBytes) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String first = null;
            String last = null;
            boolean fought = false;
            long raw = 0;
            String line;
            while ((line = r.readLine()) != null) {
                raw += line.length() + 1;
                if (raw > maxRawBytes) return "over " + maxRawBytes + " bytes uncompressed";
                if (line.trim().isEmpty()) continue;
                if (first == null) first = line;
                fought |= line.contains("\"t\":\"battle_start\"");
                last = line;
            }
            if (first == null) return "empty log";
            if (!first.contains("\"t\":\"run_start\"") && !first.contains("\"t\":\"resume\"")) {
                return "first line is not run_start or resume";
            }
            if (!first.contains("\"player_name\":\"")) return "no Steam name (the run was not played through Steam)";
            if (!last.contains("\"t\":\"run_end\"") && !fought) return "abandoned before any fight";
            return null;
        } catch (IOException e) {
            return "unreadable gzip: " + e;
        }
    }

    /** Copies every newline-terminated row; a tail a crash cut off mid-row is dropped. Returns the bytes dropped. */
    public static int copyWholeRows(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            int from = 0;
            for (int i = 0; i < n; i++) {
                if (buf[i] != '\n') continue;
                tail.writeTo(out);
                tail.reset();
                out.write(buf, from, i + 1 - from);
                from = i + 1;
            }
            tail.write(buf, from, n - from);
        }
        return tail.size();
    }
}

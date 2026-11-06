package betterrunlogs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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

    public static final int MAX_PROFILE_CHARS = 64;
    public static final int MAX_PROFILE_RUN_KEYS = 5000;
    private static final Pattern RUN_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** The server's rule for a save-slot profile name: 1..64 chars, no control characters. */
    public static boolean validProfile(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_PROFILE_CHARS) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /** X-Profile-Name value: UTF-8 percent-encoded, spaces as %20, so the server's decodeURIComponent reads it back. */
    public static String encodeHeader(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    /** The decoded string value of a top-level-style "key":"..." in one JSON row; null when absent or cut off. */
    public static String jsonString(String row, String key) {
        if (row == null) return null;
        String open = "\"" + key + "\":\"";
        int at = row.indexOf(open);
        if (at < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = at + open.length(); i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= row.length()) return null;
            char e = row.charAt(i);
            switch (e) {
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 >= row.length()) return null;
                    int code = 0;
                    for (int k = 1; k <= 4; k++) {
                        int d = Character.digit(row.charAt(i + k), 16);
                        if (d < 0) return null;
                        code = code * 16 + d;
                    }
                    out.append((char) code);
                    i += 4;
                    break;
                default: out.append(e);
            }
        }
        return null;
    }

    /** The first non-blank row of a .jsonl.gz, or null when it has none. */
    public static String firstRow(InputStream gz) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) return line;
            }
            return null;
        }
    }

    /** Save slot of a log folder: "IRONCLAD" is slot 0, "1_IRONCLAD" slot 1; -1 for folders that hold no slot's runs. */
    public static int slotOfDir(String dir) {
        if (dir.equals("inprogress") || dir.equals("unfinished") || dir.equals("abandoned")) return -1;
        if (dir.length() > 2 && dir.charAt(1) == '_' && (dir.charAt(0) == '1' || dir.charAt(0) == '2')) {
            return dir.charAt(0) - '0';
        }
        return 0;
    }

    /** The profile a log was played on: its own profile_name row field, else (older logs) the folder's slot name today. */
    public static String profileOf(String firstRow, String dir, String[] slotNames) {
        String own = jsonString(firstRow, "profile_name");
        if (own != null) return validProfile(own) ? own : null;
        int slot = slotOfDir(dir);
        if (slot < 0 || slotNames == null || slot >= slotNames.length) return null;
        String name = slotNames[slot];
        return validProfile(name) ? name : null;
    }

    public static String jsonQuote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    /** PUT /installs/:id/profiles body; run keys the server would refuse are left out, the rest capped at its limit. */
    public static String profileBody(String profile, boolean hidden, Collection<String> runKeys) {
        Set<String> keys = new LinkedHashSet<>();
        for (String k : runKeys) {
            if (keys.size() >= MAX_PROFILE_RUN_KEYS) break;
            if (k != null && RUN_KEY.matcher(k).matches()) keys.add(k);
        }
        StringBuilder out = new StringBuilder("{\"profile\":").append(jsonQuote(profile))
                .append(",\"hidden\":").append(hidden).append(",\"run_keys\":[");
        boolean first = true;
        for (String k : keys) {
            if (!first) out.append(',');
            out.append(jsonQuote(k));
            first = false;
        }
        return out.append("]}").toString();
    }

    public static Set<String> parseHiddenProfiles(String stored) {
        Set<String> out = new TreeSet<>();
        if (stored == null) return out;
        for (String line : stored.split("\n")) if (validProfile(line)) out.add(line);
        return out;
    }

    public static String joinHiddenProfiles(Collection<String> names) {
        return String.join("\n", new TreeSet<>(names));
    }
}

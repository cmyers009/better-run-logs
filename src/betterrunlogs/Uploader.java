package betterrunlogs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Uploads run logs (finished, resumed, abandoned) on one daemon thread. Started only after the player opted in;
 * a file is ledgered only after a terminal status, so a crash mid-upload retries (the server dedupes).
 */
public final class Uploader {
    private Uploader() {}

    static final String API = "https://api.spirepilot.com/api/v1/pilot";
    static final File ROOT = new File("better-run-logs");
    static final File LEDGER = new File(ROOT, "uploaded.ledger");
    private static final Set<String> NOT_RUN_DIRS = new HashSet<>(java.util.Arrays.asList("inprogress", "unfinished"));
    private static final int TIMEOUT_MS = 30_000;
    private static final Object LEDGER_LOCK = new Object();

    private static Worker worker;

    static synchronized void start(String installId) {
        if (worker != null && !worker.stopped) return;
        worker = new Worker(installId);
        Thread t = new Thread(worker, "BetterRunLogs-upload");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        worker.thread = t;
        t.start();
        System.out.println("[BetterRunLogs] uploads on");
    }

    /** The stopping thread, so a caller off the game thread can wait for it; null when none ran. */
    static synchronized Thread stop() {
        if (worker == null) return null;
        Thread t = worker.thread;
        worker.halt();
        worker = null;
        System.out.println("[BetterRunLogs] uploads off");
        return t;
    }

    /** Game thread: queue a just-written log ahead of the backfill. Never blocks, never throws. */
    static synchronized void offer(File log) {
        if (worker != null) worker.queue.offerFirst(log);
    }

    static HttpURLConnection open(String method, String url, String installId) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("X-Install-Id", installId);
        c.setRequestProperty("X-Mod-Version", RunLog.modVersion());
        // Cloudflare refuses Java's default "Java/1.8.0_x" agent (error 1010).
        c.setRequestProperty("User-Agent", "BetterRunLogs/" + RunLog.modVersion());
        return c;
    }

    /** The response body as text (error stream for 4xx/5xx), always consumed and closed. */
    static String body(HttpURLConnection c, int status) throws IOException {
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<File> scanLogs() {
        List<File> out = new ArrayList<>();
        File[] dirs = ROOT.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            if (NOT_RUN_DIRS.contains(d.getName())) continue;
            File[] logs = d.listFiles((dir, name) -> name.endsWith(".jsonl.gz"));
            if (logs != null) Collections.addAll(out, logs);
        }
        return out;
    }

    static Set<String> readLedger() throws IOException {
        synchronized (LEDGER_LOCK) {
            if (!LEDGER.isFile()) return new HashSet<>();
            return UploadPlan.parseLedger(Files.readAllLines(LEDGER.toPath(), StandardCharsets.UTF_8));
        }
    }

    static void appendLedger(String key) throws IOException {
        synchronized (LEDGER_LOCK) {
            ROOT.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(LEDGER, true), StandardCharsets.UTF_8)) {
                w.write(key + "\n");
            }
        }
    }

    /** First row of a log, or null when it cannot be read. */
    static String firstRowOf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return UploadPlan.firstRow(in);
        } catch (IOException | RuntimeException e) { // LOUD-OK: an unreadable log names no profile; its upload reports the problem
            System.err.println("[BetterRunLogs] could not read " + f + ": " + e);
            return null;
        }
    }

    static String profileOf(File f, String[] slots) {
        return UploadPlan.profileOf(firstRowOf(f), f.getParentFile().getName(), slots);
    }

    /** run_ids of the local logs played on one profile, so the site can name runs uploaded before 0.5.0. */
    static Set<String> runKeysOf(String profile, String[] slots) {
        Set<String> out = new LinkedHashSet<>();
        for (File f : scanLogs()) {
            String row = firstRowOf(f);
            if (!profile.equals(UploadPlan.profileOf(row, f.getParentFile().getName(), slots))) continue;
            String id = UploadPlan.jsonString(row, "run_id");
            if (id != null) out.add(id);
        }
        return out;
    }

    /** Tells the site to hide or show one profile's runs; the reply is a line for the player. */
    static String putProfile(String installId, String profile, boolean hidden, String[] slots) throws IOException {
        byte[] body = UploadPlan.profileBody(profile, hidden, runKeysOf(profile, slots)).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = open("PUT", API + "/installs/" + installId + "/profiles", installId);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            String reply = body(c, status);
            if (status == 429) return "Too many changes this hour; try again later.";
            if (status != 200) {
                System.err.println("[BetterRunLogs] PUT profile -> " + status + " " + reply);
                return "Could not update the site (HTTP " + status + ").";
            }
            JsonElement n = new JsonParser().parse(reply).getAsJsonObject().get("runs");
            if (n == null) return "The site gave an unexpected reply; try again later.";
            return n.getAsInt() + " uploaded runs from " + profile + " are now " + (hidden ? "hidden on" : "shown on") + " spirepilot.com.";
        } finally {
            c.disconnect();
        }
    }

    private static final class Worker implements Runnable {
        final String installId;
        final LinkedBlockingDeque<File> queue = new LinkedBlockingDeque<>();
        final Set<String> skipped = new HashSet<>();
        volatile boolean stopped;
        volatile Thread thread;
        volatile HttpURLConnection inFlight;
        Set<String> ledger = new HashSet<>();
        final Set<String> hiddenAtStart;
        int failures;
        int uploaded;
        String lastReply = "";

        Worker(String installId) {
            this.installId = installId;
            this.hiddenAtStart = Consent.hiddenProfiles();
        }

        /** A profile hidden while sharing was off, or before its runs were named, is told to the site first. */
        void syncHidden() {
            String[] slots = Consent.slotNames();
            for (String p : hiddenAtStart) {
                if (stopped) return;
                try {
                    System.out.println("[BetterRunLogs] " + putProfile(installId, p, true, slots));
                } catch (Throwable t) { // LOUD-OK: retried next launch; the server also hides new uploads from this profile
                    System.err.println("[BetterRunLogs] could not hide profile " + p + " on the site: " + t);
                }
            }
        }

        void halt() {
            stopped = true;
            Thread t = thread;
            if (t != null) t.interrupt();
            HttpURLConnection c = inFlight;
            if (c == null) return;
            // disconnect() can wait on a socket close; keep it off the game thread.
            Thread d = new Thread(() -> {
                try {
                    c.disconnect();
                } catch (Throwable e) { // LOUD-OK: the worker is already stopping
                    System.err.println("[BetterRunLogs] disconnect failed: " + e);
                }
            }, "BetterRunLogs-disconnect");
            d.setDaemon(true);
            d.start();
        }

        @Override
        public void run() {
            syncHidden();
            try {
                ledger = readLedger();
                List<File> pending = UploadPlan.pending(scanLogs(), ledger);
                queue.addAll(pending);
                System.out.println("[BetterRunLogs] " + pending.size() + " logs to upload, " + ledger.size() + " already uploaded");
            } catch (Throwable t) { // LOUD-OK: no backfill this session; new logs still upload
                System.err.println("[BetterRunLogs] upload backfill failed: " + t);
            }
            while (!stopped) {
                File f;
                try {
                    f = queue.takeFirst();
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    if (!process(f)) break;
                } catch (Throwable t) { // LOUD-OK: this file waits for the next launch; the thread keeps going
                    System.err.println("[BetterRunLogs] upload of " + f + " failed: " + t);
                }
            }
            System.out.println("[BetterRunLogs] upload thread done (" + uploaded + " uploaded, " + skipped.size() + " skipped)");
        }

        /** False when the thread should end (stopped while waiting). */
        boolean process(File f) throws IOException {
            String key = UploadPlan.key(f);
            if (ledger.contains(key) || skipped.contains(key) || !f.isFile()) return true;
            String problem;
            try (InputStream in = new FileInputStream(f)) {
                problem = UploadPlan.logProblem(in, UploadPlan.MAX_RAW_BYTES);
            }
            if (problem != null) {
                skipped.add(key);
                System.err.println("[BetterRunLogs] not uploading " + key + ": " + problem);
                return true;
            }
            if (stopped) return false;
            int status = post(f);
            if (UploadPlan.serverBehind(status, lastReply)) {
                skipped.add(key);
                System.err.println("[BetterRunLogs] server does not know this mod version yet; " + key + " waits for the next launch");
                return true;
            }
            if (UploadPlan.isTerminal(status)) {
                appendLedger(key);
                ledger.add(key);
                failures = 0;
                if (status < 300 || status == 409) uploaded++;
                else System.err.println("[BetterRunLogs] server refused " + key + " (" + status + "); not retrying");
                return true;
            }
            if (stopped) return false;
            failures++;
            queue.offerFirst(f);
            long wait = UploadPlan.backoffMs(failures);
            System.err.println("[BetterRunLogs] upload of " + key + " got " + status + "; retrying in " + wait / 1000 + " s");
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return false;
            }
            return true;
        }

        /** HTTP status, or -1 when the request did not complete. */
        int post(File f) {
            HttpURLConnection c = null;
            lastReply = "";
            try {
                c = open("POST", API + "/runs", installId);
                inFlight = c;
                if (stopped) return -1;
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/gzip");
                String profile = profileOf(f, Consent.slotNames());
                if (profile != null) c.setRequestProperty("X-Profile-Name", UploadPlan.encodeHeader(profile));
                c.setFixedLengthStreamingMode(f.length());
                try (InputStream in = new FileInputStream(f); OutputStream out = c.getOutputStream()) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                int status = c.getResponseCode();
                String reply = body(c, status);
                lastReply = reply;
                if (status >= 300) System.err.println("[BetterRunLogs] POST " + UploadPlan.key(f) + " -> " + status + " " + reply);
                return status;
            } catch (IOException | RuntimeException e) { // LOUD-OK: a network failure is a retryable -1
                System.err.println("[BetterRunLogs] POST " + UploadPlan.key(f) + " failed: " + e);
                return -1;
            } finally {
                inFlight = null;
                if (c != null) c.disconnect();
            }
        }
    }
}

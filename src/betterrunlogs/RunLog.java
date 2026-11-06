package betterrunlogs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.random.Random;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * One JSONL stream per run. In progress under better-run-logs/inprogress/ (survives crashes and
 * save-and-quit); when the game writes the .run file, a gzip copy goes to better-run-logs/<CHAR>/<same name>.jsonl.gz.
 */
final class RunLog {
    static final String FORMAT_VERSION = "1";
    private static final File INPROGRESS = new File("better-run-logs/inprogress");
    /** Scanned by the uploader: a run retired before run_end is still a fight attempt worth keeping. */
    private static final File ABANDONED = new File("better-run-logs/abandoned");
    /** Where mods before 0.4.0 parked raw abandoned logs; swept into ABANDONED once per launch. */
    private static final File LEGACY_UNFINISHED = new File("better-run-logs/unfinished");
    private static final Gson GSON = new Gson();
    private Runnable deferred;

    private static final RunLog INSTANCE = new RunLog();

    private BufferedWriter out;
    private File file;
    private String runId;
    private long seq;
    private final Map<String, Long> lastCounters = new HashMap<>();
    private final Map<String, Random> lastRngObjects = new HashMap<>();

    static RunLog get() {
        return INSTANCE;
    }

    private static int guardFailures;

    /** Every hook runs through here: a logging bug must never take down the streamer's game. */
    static void guard(String hook, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) { // LOUD-OK: skips one log line; the game keeps running
            if (guardFailures++ < 20) {
                System.err.println("[BetterRunLogs] hook " + hook + " failed (line skipped): " + t);
                t.printStackTrace();
            }
            JsonObject o = new JsonObject();
            o.addProperty("hook", hook);
            o.addProperty("error", String.valueOf(t));
            try {
                INSTANCE.emit("log_error", o);
            } catch (Throwable ignored) { // LOUD-OK: already reported above
            }
        }
    }

    private static String existingRunId(File f) {
        if (!f.isFile()) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (first == null) return null;
            JsonObject o = GSON.fromJson(first, JsonObject.class);
            return o.has("run_id") ? o.get("run_id").getAsString() : null;
        } catch (IOException | RuntimeException e) { // LOUD-OK: unreadable header means a fresh id
            System.err.println("[BetterRunLogs] could not read run_id from " + f + ": " + e);
            return null;
        }
    }

    synchronized boolean open() {
        return out != null;
    }

    synchronized String runId() {
        return runId;
    }

    private static File fileFor(String character, long seed) {
        return new File(INPROGRESS, character + "_" + seed + ".jsonl");
    }

    /** This mod's version from ModTheSpire.json, so a reader knows which format and fixes a log has. */
    static String modVersion() {
        for (com.evacipated.cardcrawl.modthespire.ModInfo m : com.evacipated.cardcrawl.modthespire.Loader.MODINFOS) {
            if ("better-run-logs".equals(m.ID)) return String.valueOf(m.ModVersion);
        }
        return "unknown";
    }

    /** Everything outside the seed that decides what the run offers, so a replay can reproduce it. */
    private static JsonObject profile(String character) {
        JsonObject p = new JsonObject();
        p.addProperty("seed_set", Settings.seedSet);
        p.addProperty("neow_spirits", CardCrawlGame.playerPref.getInteger(character + "_SPIRITS", 0));
        JsonArray cards = new JsonArray();
        for (String id : com.megacrit.cardcrawl.unlock.UnlockTracker.lockedCards) cards.add(id);
        p.add("locked_cards", cards);
        JsonArray relics = new JsonArray();
        for (String id : com.megacrit.cardcrawl.unlock.UnlockTracker.lockedRelics) relics.add(id);
        p.add("locked_relics", relics);
        JsonArray mods = new JsonArray();
        for (com.evacipated.cardcrawl.modthespire.ModInfo m : com.evacipated.cardcrawl.modthespire.Loader.MODINFOS) {
            mods.add(m.ID + (m.ModVersion == null ? "" : "@" + m.ModVersion));
        }
        p.add("mods", mods);
        return p;
    }

    synchronized void begin(boolean resumed) {
        closeQuietly();
        deferred = null;
        String character = String.valueOf(AbstractDungeon.player.chosenClass);
        File f = fileFor(character, Settings.seed);
        if (!INPROGRESS.isDirectory() && !INPROGRESS.mkdirs()) {
            System.err.println("[BetterRunLogs] cannot create " + INPROGRESS.getAbsolutePath());
            return;
        }
        if (!resumed) retireStale(character);
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[BetterRunLogs] cannot open " + f + ": " + e);
            out = null;
            return;
        }
        String prior = resumed ? existingRunId(f) : null;
        file = f;
        runId = prior != null ? prior : UUID.randomUUID().toString();
        seq = 0;
        lastCounters.clear();
        lastRngObjects.clear();
        Snap.markLeftoverRngs(!resumed);
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT_VERSION);
        o.addProperty("run_id", runId);
        o.addProperty("player_name", SteamName.get());
        o.addProperty("profile_name", CardCrawlGame.playerName);
        o.addProperty("seed", Settings.seed);
        o.addProperty("seed_str", com.megacrit.cardcrawl.helpers.SeedHelper.getString(Settings.seed));
        o.addProperty("character", character);
        o.addProperty("ascension", AbstractDungeon.ascensionLevel);
        o.addProperty("daily", Settings.isDailyRun);
        o.addProperty("trial", Settings.isTrial);
        o.addProperty("endless", Settings.isEndless);
        o.addProperty("final_act", Settings.isFinalActAvailable);
        o.addProperty("game_version", CardCrawlGame.TRUE_VERSION_NUM);
        o.addProperty("log_version", modVersion());
        o.add("profile", profile(character));
        o.addProperty("playtime", CardCrawlGame.playtime);
        o.addProperty("wallclock_ms", System.currentTimeMillis());
        o.add("state", Snap.full());
        // A resumed log has no act_start for the current act; a replay reads the boss from here.
        o.addProperty("boss", AbstractDungeon.bossKey);
        emit(resumed ? "resume" : "run_start", o);
        noteRngs();
        flush();
    }

    /** A new run deletes this character's save, so any in-progress log for it can never be resumed. */
    private static void retireStale(String character) {
        File[] stale = INPROGRESS.listFiles((d, n) -> n.startsWith(character + "_") && n.endsWith(".jsonl"));
        if (stale == null) return;
        for (File f : stale) {
            File dest = new File(ABANDONED, f.getName().replace(".jsonl", "_" + f.lastModified() + ".jsonl.gz"));
            if (gzipWhole(f, dest)) offer(dest);
        }
    }

    /** Raw logs older mods left in unfinished/ become uploadable abandoned logs; call before the uploader scans. */
    static void sweepUnfinished() {
        File[] raw = LEGACY_UNFINISHED.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (raw == null) return;
        for (File f : raw) gzipWhole(f, new File(ABANDONED, f.getName() + ".gz"));
    }

    /**
     * Gzips raw's whole rows to dest and deletes raw; false (raw kept) on any IO failure. A crash can cut the
     * last row mid-write, so only newline-terminated rows are copied. dest appears only once whole.
     */
    private static boolean gzipWhole(File raw, File dest) {
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("[BetterRunLogs] cannot create " + dir.getAbsolutePath() + " (raw log kept at " + raw + ")");
            return false;
        }
        File tmp = new File(dir, dest.getName() + ".tmp");
        try {
            int dropped;
            try (InputStream in = new FileInputStream(raw);
                    OutputStream gz = new GZIPOutputStream(new FileOutputStream(tmp))) {
                dropped = UploadPlan.copyWholeRows(in, gz);
            }
            if (dropped > 0) System.out.println("[BetterRunLogs] dropped a " + dropped + "-byte partial last row of " + raw);
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[BetterRunLogs] could not write " + dest + " (raw log kept at " + raw + "): " + e);
            return false;
        }
        if (!raw.delete()) System.err.println("[BetterRunLogs] could not delete " + raw);
        System.out.println("[BetterRunLogs] wrote " + dest.getAbsolutePath());
        return true;
    }

    private static void offer(File gz) {
        try {
            Uploader.offer(gz);
        } catch (Throwable t) { // LOUD-OK: the next launch's backfill uploads it
            System.err.println("[BetterRunLogs] could not queue " + gz + " for upload: " + t);
        }
    }

    /** Only the RNG streams that moved since the last line: [counter, seed0, seed1], plus reseeds. */
    private JsonObject rngDelta() {
        JsonObject d = null;
        for (Map.Entry<String, Random> e : Snap.gameRngs().entrySet()) {
            Random r = e.getValue();
            if (r == null) continue;
            Long last = lastCounters.get(e.getKey());
            boolean reseeded = lastRngObjects.get(e.getKey()) != r;
            if (reseeded || last == null || last != r.counter) {
                if (d == null) d = new JsonObject();
                JsonArray s = Snap.rngState(r);
                if (reseeded && last != null) s.add("reseeded");
                d.add(e.getKey(), s);
            }
        }
        noteRngs();
        return d;
    }

    private void noteRngs() {
        for (Map.Entry<String, Random> e : Snap.gameRngs().entrySet()) {
            if (e.getValue() == null) continue;
            lastCounters.put(e.getKey(), (long) e.getValue().counter);
            lastRngObjects.put(e.getKey(), e.getValue());
        }
    }

    /** Runs a line deferred until the game finishes what it was building, ahead of any later line. */
    synchronized void defer(Runnable line) {
        deferred = line;
    }

    synchronized void runDeferred() {
        Runnable r = deferred;
        deferred = null;
        if (r != null) r.run();
    }

    synchronized void emit(String type, JsonObject body) {
        if (out == null) return;
        runDeferred();
        body.addProperty("t", type);
        body.addProperty("seq", seq++);
        body.addProperty("floor", AbstractDungeon.floorNum);
        JsonObject rng = rngDelta();
        if (rng != null) body.add("rng_d", rng);
        try {
            out.write(GSON.toJson(body));
            out.write('\n');
        } catch (IOException e) {
            System.err.println("[BetterRunLogs] write failed, logging stopped: " + e);
            closeQuietly();
        }
    }

    synchronized void flush() {
        if (out == null) return;
        try {
            out.flush();
        } catch (IOException e) {
            System.err.println("[BetterRunLogs] flush failed: " + e);
        }
    }

    private void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) { // LOUD-OK: closing a stream we are abandoning anyway
            System.err.println("[BetterRunLogs] close failed: " + e);
        }
        out = null;
    }

    synchronized void suspend() {
        flush();
        closeQuietly();
    }

    /**
     * The game just wrote runs/<dir>/<name>.run; the log goes to better-run-logs/<dir>/<name>.jsonl.gz,
     * never into runs/: CharStat deletes every file there that does not parse as a run.
     */
    synchronized void finish(File runFile) {
        if (file == null) return;
        closeQuietly();
        String base = runFile.getName().replaceAll("\\.run$", "");
        File dest = new File(new File("better-run-logs", runFile.getParentFile().getName()), base + ".jsonl.gz");
        if (!gzipWhole(file, dest)) return;
        file = null;
        runId = null;
        offer(dest);
    }
}

import betterrunlogs.UploadPlan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Run with java -ea: UploadPlan's choices, no game classes. */
public final class UploadPlanTest {
    public static void main(String[] args) throws IOException {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new AssertionError("run with java -ea");
        ledgerFilterAndNewestFirst();
        terminalStatuses();
        backoffSequence();
        logChecks();
        consentBodyMentionsOnlyExistingLogs();
        System.out.println("UploadPlanTest: all passed");
    }

    private static void consentBodyMentionsOnlyExistingLogs() {
        assert UploadPlan.consentBody(true).contains("already on your PC");
        assert !UploadPlan.consentBody(false).contains("already on your PC");
        assert UploadPlan.consentBody(false).endsWith("Config.");
    }

    private static File log(File root, String dir, String name, long mtime) throws IOException {
        File d = new File(root, dir);
        d.mkdirs();
        File f = new File(d, name);
        Files.write(f.toPath(), new byte[] {1});
        assert f.setLastModified(mtime);
        return f;
    }

    private static void ledgerFilterAndNewestFirst() throws IOException {
        File root = Files.createTempDirectory("uploadplan").toFile();
        File old = log(root, "IRONCLAD", "100.jsonl.gz", 1_000_000L);
        File mid = log(root, "WATCHER", "200.jsonl.gz", 2_000_000L);
        File done = log(root, "IRONCLAD", "300.jsonl.gz", 3_000_000L);
        File newest = log(root, "IRONCLAD", "400.jsonl.gz", 4_000_000L);
        assert UploadPlan.key(done).equals("IRONCLAD/300.jsonl.gz") : UploadPlan.key(done);
        List<File> got = UploadPlan.pending(Arrays.asList(old, done, newest, mid),
                new HashSet<>(Arrays.asList("IRONCLAD/300.jsonl.gz", "IRONCLAD/999.jsonl.gz")));
        assert got.equals(Arrays.asList(newest, mid, old)) : got;
        assert UploadPlan.pending(new ArrayList<>(), new HashSet<>()).isEmpty();
        assert UploadPlan.parseLedger(Arrays.asList("IRONCLAD/1.jsonl.gz", "", "  ", "WATCHER/2.jsonl.gz "))
                .equals(new HashSet<>(Arrays.asList("IRONCLAD/1.jsonl.gz", "WATCHER/2.jsonl.gz")));
    }

    private static void terminalStatuses() {
        for (int s : new int[] {200, 201, 204, 299, 409, 413, 422}) assert UploadPlan.isTerminal(s) : s;
        for (int s : new int[] {-1, 0, 199, 300, 400, 401, 403, 404, 429, 500, 502, 503}) assert !UploadPlan.isTerminal(s) : s;
    }

    private static void backoffSequence() {
        long[] want = {30000, 60000, 120000, 240000, 480000, 960000, 1800000, 1800000};
        for (int i = 0; i < want.length; i++) {
            assert UploadPlan.backoffMs(i + 1) == want[i] : (i + 1) + " -> " + UploadPlan.backoffMs(i + 1);
        }
        assert UploadPlan.backoffMs(0) == 0;
        assert UploadPlan.backoffMs(500) == 1800000;
    }

    private static byte[] gz(String text) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) {
            g.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    private static String problem(byte[] bytes, long maxRaw) {
        return UploadPlan.logProblem(new ByteArrayInputStream(bytes), maxRaw);
    }

    private static void logChecks() throws IOException {
        String start = "{\"run_id\":\"x\",\"player_name\":\"Panacea\",\"t\":\"run_start\",\"seq\":0}\n";
        String nameless = "{\"run_id\":\"x\",\"t\":\"run_start\",\"seq\":0}\n";
        String mid = "{\"t\":\"turn_start\",\"seq\":1}\n";
        String end = "{\"victory\":true,\"t\":\"run_end\",\"seq\":2}\n";
        assert problem(gz(start + mid + end), 1 << 20) == null;
        assert problem(gz(start + mid + end + "\n"), 1 << 20) == null;
        assert problem(gz(start + mid), 1 << 20).equals("no run_end line");
        assert problem(gz(mid + end), 1 << 20).equals("first line is not run_start");
        assert problem(gz(""), 1 << 20).equals("empty log");
        assert UploadPlan.serverBehind(422, "{\"error\":\"unknown log_version 0.3.1\"}");
        assert !UploadPlan.serverBehind(422, "{\"error\":\"unparseable: run_start.player_name\"}");
        assert !UploadPlan.serverBehind(200, "unknown log_version");
        assert problem(gz(nameless + mid + end), 1 << 20).startsWith("no Steam name");
        byte[] full = gz(start + mid + end);
        assert problem(Arrays.copyOf(full, full.length - 6), 1 << 20).startsWith("unreadable gzip");
        assert problem("not gzip".getBytes(StandardCharsets.UTF_8), 1 << 20).startsWith("unreadable gzip");
        assert problem(gz(start + mid + end), 40).equals("over 40 bytes uncompressed");
    }
}

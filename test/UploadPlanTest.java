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
        wholeRowsOnly();
        consentBodyMentionsOnlyExistingLogs();
        profileHelpers();
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
        String resume = "{\"run_id\":\"x\",\"player_name\":\"Panacea\",\"t\":\"resume\",\"seq\":0}\n";
        String nameless = "{\"run_id\":\"x\",\"t\":\"run_start\",\"seq\":0}\n";
        String fight = "{\"encounter\":\"Cultist\",\"t\":\"battle_start\",\"seq\":1}\n";
        String mid = "{\"t\":\"turn_start\",\"seq\":2}\n";
        String end = "{\"victory\":true,\"t\":\"run_end\",\"seq\":3}\n";
        assert problem(gz(start + mid + end), 1 << 20) == null;
        assert problem(gz(start + mid + end + "\n"), 1 << 20) == null;
        assert problem(gz(resume + fight + mid + end), 1 << 20) == null;
        assert problem(gz(start + fight + mid), 1 << 20) == null;
        assert problem(gz(resume + fight + mid), 1 << 20) == null;
        assert problem(gz(start + mid), 1 << 20).equals("abandoned before any fight");
        assert problem(gz(mid + end), 1 << 20).equals("first line is not run_start or resume");
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

    private static String copied(String raw, int[] dropped) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dropped[0] = UploadPlan.copyWholeRows(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void wholeRowsOnly() throws IOException {
        int[] dropped = new int[1];
        assert copied("{\"a\":1}\n{\"b\":2}\n", dropped).equals("{\"a\":1}\n{\"b\":2}\n") && dropped[0] == 0;
        assert copied("{\"a\":1}\n{\"b\":{\"c\":2}", dropped).equals("{\"a\":1}\n") && dropped[0] == 12;
        assert copied("{\"a\":", dropped).isEmpty() && dropped[0] == 5;
        assert copied("", dropped).isEmpty() && dropped[0] == 0;
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append("{\"row\":").append(i).append("}\n");
        assert copied(big + "{\"cut", dropped).equals(big.toString()) && dropped[0] == 5;
    }

    private static void profileHelpers() throws IOException {
        assert UploadPlan.validProfile("A");
        assert UploadPlan.validProfile("Pan acea \u00dcn\u00efcode");
        assert !UploadPlan.validProfile(null) && !UploadPlan.validProfile("");
        assert !UploadPlan.validProfile("a\tb") && !UploadPlan.validProfile("a\u007fb");
        assert UploadPlan.validProfile(new String(new char[64]).replace('\0', 'x'));
        assert !UploadPlan.validProfile(new String(new char[65]).replace('\0', 'x'));

        assert UploadPlan.encodeHeader("Pan acea+%").equals("Pan%20acea%2B%25") : UploadPlan.encodeHeader("Pan acea+%");
        assert UploadPlan.encodeHeader("\u00dc").equals("%C3%9C");

        String row = "{\"run_id\":\"abcd1234-run\",\"player_name\":\"elf\",\"profile_name\":\"a \\\"q\\\" \\\\ \\u00dc\\n\",\"t\":\"run_start\"}";
        assert "abcd1234-run".equals(UploadPlan.jsonString(row, "run_id"));
        assert "a \"q\" \\ \u00dc\n".equals(UploadPlan.jsonString(row, "profile_name")) : UploadPlan.jsonString(row, "profile_name");
        assert UploadPlan.jsonString(row, "missing") == null;
        assert UploadPlan.jsonString("{\"k\":\"cut", "k") == null;
        assert UploadPlan.jsonString("{\"k\":\"bad\\u00z1\"}", "k") == null;
        assert UploadPlan.jsonString("{\"k\":\"short\\u00\"}", "k") == null;
        assert UploadPlan.jsonString(null, "k") == null;

        assert UploadPlan.firstRow(new ByteArrayInputStream(gz("\n  \n{\"a\":1}\n{\"b\":2}\n"))).equals("{\"a\":1}");
        assert UploadPlan.firstRow(new ByteArrayInputStream(gz("\n"))) == null;

        assert UploadPlan.slotOfDir("IRONCLAD") == 0 && UploadPlan.slotOfDir("1_IRONCLAD") == 1 && UploadPlan.slotOfDir("2_WATCHER") == 2;
        assert UploadPlan.slotOfDir("abandoned") == -1 && UploadPlan.slotOfDir("inprogress") == -1 && UploadPlan.slotOfDir("unfinished") == -1;
        assert UploadPlan.slotOfDir("3_IRONCLAD") == 0;

        String[] slots = {"Main", "offline", ""};
        String tagged = "{\"profile_name\":\"offline\",\"t\":\"run_start\"}";
        String untagged = "{\"t\":\"run_start\"}";
        assert "offline".equals(UploadPlan.profileOf(tagged, "IRONCLAD", slots));
        assert UploadPlan.profileOf("{\"profile_name\":\"\"}", "IRONCLAD", slots) == null;
        assert "Main".equals(UploadPlan.profileOf(untagged, "IRONCLAD", slots));
        assert "offline".equals(UploadPlan.profileOf(untagged, "1_IRONCLAD", slots));
        assert UploadPlan.profileOf(untagged, "2_IRONCLAD", slots) == null;
        assert UploadPlan.profileOf(untagged, "abandoned", slots) == null;
        assert "offline".equals(UploadPlan.profileOf(tagged, "abandoned", slots));
        assert UploadPlan.profileOf(untagged, "IRONCLAD", null) == null;

        assert UploadPlan.jsonQuote("a\"b\\c\n\u2028").equals("\"a\\\"b\\\\c\\u000a\\u2028\"") : UploadPlan.jsonQuote("a\"b\\c\n\u2028");
        assert UploadPlan.profileBody("off \"x\"", true, Arrays.asList("abcd1234-run", "bad key!", "abcd1234-run", null, "short"))
                .equals("{\"profile\":\"off \\\"x\\\"\",\"hidden\":true,\"run_keys\":[\"abcd1234-run\"]}")
                : UploadPlan.profileBody("off \"x\"", true, Arrays.asList("abcd1234-run", "bad key!", "abcd1234-run", null, "short"));
        assert UploadPlan.profileBody("p", false, new ArrayList<>()).equals("{\"profile\":\"p\",\"hidden\":false,\"run_keys\":[]}");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 6000; i++) many.add(String.format("run-%08d", i));
        String body = UploadPlan.profileBody("p", true, many);
        assert body.split(",").length - 2 == UploadPlan.MAX_PROFILE_RUN_KEYS : body.split(",").length;

        assert UploadPlan.parseHiddenProfiles(null).isEmpty();
        assert UploadPlan.parseHiddenProfiles("b\n\na\nb").equals(new HashSet<>(Arrays.asList("a", "b")));
        assert UploadPlan.joinHiddenProfiles(Arrays.asList("b", "a")).equals("a\nb");
        assert UploadPlan.parseHiddenProfiles(UploadPlan.joinHiddenProfiles(Arrays.asList("x y", "\u00dc"))).equals(new HashSet<>(Arrays.asList("x y", "\u00dc")));
    }
}

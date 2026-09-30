package betterrunlogs;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.GenericEventDialog;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.metrics.Metrics;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import java.io.File;
import java.util.HashMap;

/** Method hooks BaseMod does not provide. Every hook only reads game state, inside RunLog.guard. */
public final class Patches {
    private Patches() {}

    static final int PLAYER = -1;
    static final int NONE = -2;
    static final int NOT_IN_GROUP = -3;

    static int monsterIndex(AbstractCreature c) {
        if (c == null) return NONE;
        if (c == AbstractDungeon.player) return PLAYER;
        AbstractRoom room = Snap.room();
        if (room == null || room.monsters == null) return NOT_IN_GROUP;
        int i = room.monsters.monsters.indexOf(c);
        return i < 0 ? NOT_IN_GROUP : i;
    }

    private static AbstractGameAction lastAction;

    @SpirePatch(clz = GameActionManager.class, method = "getNextAction")
    public static class ActionExecuted {
        @SpirePostfixPatch
        public static void Postfix(GameActionManager __instance) {
            AbstractGameAction a = __instance.currentAction;
            if (a == null || a == lastAction) return;
            lastAction = a;
            RunLog.guard("action", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("action", a.getClass().getName().replace("com.megacrit.cardcrawl.actions.", ""));
                o.addProperty("type", String.valueOf(a.actionType));
                if (a.amount != 0) o.addProperty("amt", a.amount);
                if (a.source != null) o.addProperty("src", monsterIndex(a.source));
                if (a.target != null) o.addProperty("tgt", monsterIndex(a.target));
                if (Snap.inCombat()) o.add("hp", Snap.hpVector());
                RunLog.get().emit("action", o);
            });
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "useCard")
    public static class CardPlayed {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance, AbstractCard c, AbstractMonster m, int energyOnUse) {
            RunLog.guard("cardPlay", () -> {
                JsonObject o = new JsonObject();
                o.add("card", Snap.card(c));
                if (m != null) o.addProperty("target", monsterIndex(m));
                o.addProperty("energyOnUse", energyOnUse);
                o.addProperty("energy", com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount);
                if (c.purgeOnUse) o.addProperty("copy", true);
                if (c.dontTriggerOnUseCard) o.addProperty("noTrigger", true);
                o.add("hand", Snap.pile(__instance.hand));
                o.add("hp", Snap.hpVector());
                RunLog.get().emit("card_play", o);
            });
        }
    }

    @SpirePatch(clz = AbstractRoom.class, method = "endTurn")
    public static class EndTurn {
        @SpirePrefixPatch
        public static void Prefix(AbstractRoom __instance) {
            RunLog.guard("endTurn", () -> {
                JsonObject o = new JsonObject();
                o.add("hand", Snap.pile(AbstractDungeon.player.hand));
                o.addProperty("energy", com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount);
                o.add("monsters", Snap.monsters());
                RunLog.get().emit("end_turn", o);
            });
        }
    }

    @SpirePatch(clz = AbstractMonster.class, method = "rollMove")
    public static class MoveRolled {
        @SpirePostfixPatch
        public static void Postfix(AbstractMonster __instance) {
            RunLog.guard("rollMove", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("i", monsterIndex(__instance));
                o.addProperty("id", __instance.id);
                o.addProperty("move", __instance.nextMove);
                o.addProperty("intent", String.valueOf(__instance.intent));
                RunLog.get().emit("monster_roll", o);
            });
        }
    }

    private static void eventChoice(String dialog, int option) {
        if (option < 0) return;
        RunLog.guard("eventChoice", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("dialog", dialog);
            o.addProperty("option", option);
            AbstractRoom room = Snap.room();
            if (room != null && room.event != null) o.addProperty("event", room.event.getClass().getName());
            RunLog.get().emit("event_choice", o);
        });
    }

    @SpirePatch(clz = GenericEventDialog.class, method = "getSelectedOption")
    public static class ImageEventChoice {
        @SpirePostfixPatch
        public static int Postfix(int result) {
            eventChoice("image", result);
            return result;
        }
    }

    @SpirePatch(clz = RoomEventDialog.class, method = "getSelectedOption")
    public static class RoomEventChoice {
        @SpirePostfixPatch
        public static int Postfix(int result, RoomEventDialog __instance) {
            eventChoice("room", result);
            return result;
        }
    }

    @SpirePatch(clz = CardRewardScreen.class, method = "acquireCard")
    public static class CardRewardPick {
        @SpirePrefixPatch
        public static void Prefix(CardRewardScreen __instance, AbstractCard c) {
            RunLog.guard("cardPick", () -> {
                JsonObject o = new JsonObject();
                o.add("card", Snap.card(c));
                o.add("offered", BetterRunLogs.cards(__instance.rewardGroup));
                RunLog.get().emit("card_pick", o);
            });
        }
    }

    /** "SKIP" or "Singing Bowl": the only card-reward exits that add no card. */
    @SpirePatch(clz = CardRewardScreen.class, method = "recordMetrics", paramtypez = {String.class})
    public static class CardRewardSkip {
        @SpirePrefixPatch
        public static void Prefix(CardRewardScreen __instance, String how) {
            RunLog.guard("cardSkip", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("how", how);
                o.add("offered", BetterRunLogs.cards(__instance.rewardGroup));
                RunLog.get().emit("card_skip", o);
            });
        }
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "closeCurrentScreen")
    public static class ScreenClosing {
        @SpirePrefixPatch
        public static void Prefix() {
            RunLog.guard("closeScreen", BetterRunLogs::selectionsNow);
        }
    }

    @SpirePatch(clz = Metrics.class, method = "gatherAllData")
    public static class TagRunFile {
        @SpirePostfixPatch
        @SuppressWarnings("unchecked")
        public static void Postfix(Metrics __instance, boolean death, boolean trueVictor, MonsterGroup monsters) {
            RunLog.guard("tagRunFile", () -> {
                String id = RunLog.get().runId();
                if (id == null) return;
                Object params = Snap.privateField(__instance, Metrics.class, "params");
                if (params != null) ((HashMap<Object, Object>) params).put("better_run_log_id", id);
            });
        }
    }

    /** Only death (DeathScreen, also act-3 wins) or trueVictor (VictoryScreen) end a run; initializeFirstRoom also saves mid-run. */
    @SpirePatch(clz = Metrics.class, method = "gatherAllDataAndSave")
    public static class RunFileWritten {
        @SpirePostfixPatch
        public static void Postfix(Metrics __instance, boolean death, boolean trueVictor, MonsterGroup monsters) {
            if (!death && !trueVictor) return;
            RunLog.guard("runEnd", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("death", death);
                o.addProperty("victory", trueVictor);
                o.add("state", Snap.full());
                RunLog.get().emit("run_end", o);
                File newest = newestRunFile(new File("runs"));
                if (newest == null) {
                    System.err.println("[BetterRunLogs] no .run file found; raw log left in better-run-logs/inprogress");
                    RunLog.get().suspend();
                    return;
                }
                RunLog.get().finish(newest);
            });
        }
    }

    static File newestRunFile(File root) {
        File best = null;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] runs = d.listFiles((dir, name) -> name.endsWith(".run"));
            if (runs == null) continue;
            for (File r : runs) if (best == null || r.lastModified() > best.lastModified()) best = r;
        }
        return best;
    }
}

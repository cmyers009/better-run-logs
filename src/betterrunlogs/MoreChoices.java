package betterrunlogs;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.events.shrines.GremlinMatchGame;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.screens.select.BossRelicSelectScreen;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Turn ends the player did not choose, boss relic picks and skips, and Match and Keep flips. */
public final class MoreChoices {
    private MoreChoices() {}

    @SpirePatch(clz = GameActionManager.class, method = "callEndTurnEarlySequence")
    public static class EndTurnEarly {
        @SpirePrefixPatch
        public static void Prefix(GameActionManager __instance) {
            RunLog.guard("endTurnEarly", () -> RunLog.get().emit("end_turn_early", new JsonObject()));
        }
    }

    private static JsonArray offered(BossRelicSelectScreen s) {
        JsonArray a = new JsonArray();
        for (AbstractRelic r : s.relics) a.add(r.relicId);
        return a;
    }

    @SpirePatch(clz = BossRelicSelectScreen.class, method = "relicObtainLogic")
    public static class BossPick {
        @SpirePrefixPatch
        public static void Prefix(BossRelicSelectScreen __instance, AbstractRelic r) {
            RunLog.guard("bossPick", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", r.relicId);
                o.add("offered", offered(__instance));
                RunLog.get().emit("boss_relic_pick", o);
            });
        }
    }

    @SpirePatch(clz = BossRelicSelectScreen.class, method = "relicSkipLogic")
    public static class BossSkip {
        @SpirePrefixPatch
        public static void Prefix(BossRelicSelectScreen __instance) {
            RunLog.guard("bossSkip", () -> {
                JsonObject o = new JsonObject();
                o.add("offered", offered(__instance));
                RunLog.get().emit("boss_relic_skip", o);
            });
        }
    }

    /** Board position 0..11 (row-major) of each card, fixed when the game is dealt. */
    private static final Map<UUID, Integer> positions = new HashMap<>();
    private static final java.util.Set<UUID> faceUpBefore = new java.util.HashSet<>();
    private static int attemptsBefore;
    private static int matchedBefore;

    @SpirePatch(clz = GremlinMatchGame.class, method = SpirePatch.CONSTRUCTOR)
    public static class MatchDealt {
        @SpirePostfixPatch
        public static void Postfix(GremlinMatchGame __instance) {
            RunLog.guard("matchDealt", () -> {
                positions.clear();
                CardGroup cards = (CardGroup) Snap.privateField(__instance, GremlinMatchGame.class, "cards");
                for (int i = 0; i < cards.group.size(); i++) positions.put(cards.group.get(i).uuid, i % 4 + 4 * (i % 3));
            });
        }
    }

    private static CardGroup board(GremlinMatchGame g) {
        return (CardGroup) Snap.privateField(g, GremlinMatchGame.class, "cards");
    }

    private static int intField(GremlinMatchGame g, String name) {
        return (Integer) Snap.privateField(g, GremlinMatchGame.class, name);
    }

    /** A flip is a card turning face up; a pair resolves (after its delay) when attemptCount drops. */
    @SpirePatch(clz = GremlinMatchGame.class, method = "updateMatchGameLogic")
    public static class MatchFlip {
        @SpirePrefixPatch
        public static void Prefix(GremlinMatchGame __instance) {
            RunLog.guard("matchFlipBefore", () -> {
                faceUpBefore.clear();
                for (AbstractCard c : board(__instance).group) if (c.isFlipped) faceUpBefore.add(c.uuid);
                attemptsBefore = intField(__instance, "attemptCount");
                matchedBefore = intField(__instance, "cardsMatched");
            });
        }

        @SpirePostfixPatch
        public static void Postfix(GremlinMatchGame __instance) {
            RunLog.guard("matchFlip", () -> {
                for (AbstractCard c : board(__instance).group) {
                    if (!c.isFlipped || faceUpBefore.contains(c.uuid)) continue;
                    JsonObject o = Snap.card(c);
                    Integer pos = positions.get(c.uuid);
                    if (pos != null) o.addProperty("position", pos);
                    RunLog.get().emit("match_flip", o);
                }
                int attempts = intField(__instance, "attemptCount");
                if (attempts != attemptsBefore) {
                    JsonObject o = new JsonObject();
                    o.addProperty("matched", intField(__instance, "cardsMatched") > matchedBefore);
                    o.addProperty("attempts_left", attempts);
                    RunLog.get().emit("match_pair", o);
                }
            });
        }
    }
}

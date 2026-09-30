package betterrunlogs;

import basemod.BaseMod;
import basemod.interfaces.OnPlayerTurnStartPostDrawSubscriber;
import basemod.interfaces.OnStartBattleSubscriber;
import basemod.interfaces.PostBattleSubscriber;
import basemod.interfaces.PostDeathSubscriber;
import basemod.interfaces.PostDrawSubscriber;
import basemod.interfaces.PostExhaustSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PotionGetSubscriber;
import basemod.interfaces.PreMonsterTurnSubscriber;
import basemod.interfaces.RelicGetSubscriber;
import basemod.interfaces.StartActSubscriber;
import basemod.interfaces.StartGameSubscriber;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import java.util.ArrayList;
import java.util.List;

/** Entry point: BaseMod hooks for run lifecycle, combat turns, and screen changes. */
@SpireInitializer
public class BetterRunLogs implements StartGameSubscriber, StartActSubscriber, OnStartBattleSubscriber,
        PostBattleSubscriber, OnPlayerTurnStartPostDrawSubscriber, PreMonsterTurnSubscriber, PostDrawSubscriber,
        PostExhaustSubscriber, PotionGetSubscriber, RelicGetSubscriber, PostDeathSubscriber,
        PostUpdateSubscriber {

    private String lastScreenKey = "";
    private String lastGridPick = "";
    private String lastHandPick = "";
    private boolean wasInRun;

    public static void initialize() {
        instance = new BetterRunLogs();
        BaseMod.subscribe(instance);
        System.out.println("[BetterRunLogs] initialized");
    }

    private static RunLog log() {
        return RunLog.get();
    }

    private static JsonObject with(String key, JsonObject value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    @Override
    public void receiveStartGame() {
        RunLog.guard("startGame", () -> {
            log().begin(CardCrawlGame.loadingSave);
            wasInRun = true;
            lastScreenKey = "";
            lastDeck = new java.util.HashMap<>();
            lastStats = "";
            lastDiscovery = null;
            lastRelics = null;
            lastKeys = "";
            lastNode = null;
        });
    }

    @Override
    public void receiveStartAct() {
        RunLog.guard("startAct", () -> {
            JsonObject o = with("state", Snap.full());
            o.addProperty("boss", AbstractDungeon.bossKey);
            o.add("bossList", strings(AbstractDungeon.bossList));
            o.add("map", map());
            log().emit("act_start", o);
            log().flush();
        });
    }

    @Override
    public void receiveOnBattleStart(AbstractRoom room) {
        RunLog.guard("battleStart", () -> {
            JsonObject o = with("state", Snap.full());
            o.addProperty("encounter", AbstractDungeon.lastCombatMetricKey);
            o.add("monsters", Snap.monsters());
            log().emit("battle_start", o);
        });
    }

    @Override
    public void receivePostBattle(AbstractRoom room) {
        RunLog.guard("postBattle", () -> {
            JsonObject o = with("state", Snap.full());
            o.addProperty("turns", AbstractDungeon.actionManager.turn);
            o.add("monsters", Snap.monsters());
            log().emit("battle_end", o);
            log().flush();
        });
    }

    @Override
    public void receiveOnPlayerTurnStartPostDraw() {
        RunLog.guard("turnStart", () -> {
            log().emit("turn_start", with("state", Snap.full()));
            log().flush();
        });
    }

    @Override
    public boolean receivePreMonsterTurn(AbstractMonster m) {
        RunLog.guard("monsterTurn", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("i", Patches.monsterIndex(m));
            o.addProperty("id", m.id);
            o.addProperty("move", m.nextMove);
            o.addProperty("intent", String.valueOf(m.intent));
            log().emit("monster_turn", o);
        });
        return true;
    }

    @Override
    public void receivePostDraw(AbstractCard c) {
        RunLog.guard("draw", () -> log().emit("draw", with("card", Snap.card(c))));
    }

    @Override
    public void receivePostExhaust(AbstractCard c) {
        RunLog.guard("exhaust", () -> log().emit("exhaust", with("card", Snap.card(c))));
    }

    @Override
    public void receivePotionGet(AbstractPotion p) {
        RunLog.guard("potionGet", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.ID);
            o.addProperty("slot", p.slot);
            o.add("belt", Snap.potions());
            log().emit("potion_get", o);
        });
    }

    @Override
    public void receiveRelicGet(AbstractRelic r) {
        RunLog.guard("relicGet", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.relicId);
            log().emit("relic_get", o);
        });
    }

    @Override
    public void receivePostDeath() {
        RunLog.guard("death", () -> {
            log().emit("death", with("state", Snap.full()));
            log().flush();
        });
    }

    @Override
    public void receivePostUpdate() {
        RunLog.guard("postUpdate", this::postUpdate);
    }

    private void postUpdate() {
        boolean inRun = CardCrawlGame.isInARun() && AbstractDungeon.player != null && log().open();
        if (!inRun) {
            if (wasInRun && !CardCrawlGame.isInARun()) log().suspend();
            wasInRun = CardCrawlGame.isInARun();
            return;
        }
        wasInRun = true;
        PotionLog.endFrame();
        Choices.endFrame();
        watchScreen();
        watchDiscovery();
        watchDeckAndStats();
        watchRelicsAndKeys();
        log().flush();
    }

    private void watchScreen() {
        AbstractRoom room = Snap.room();
        String key = AbstractDungeon.floorNum + "|" + (room == null ? "" : room.getClass().getName() + "|" + room.phase)
                + "|" + AbstractDungeon.screen + "|" + AbstractDungeon.isScreenUp;
        if (key.equals(lastScreenKey)) return;
        boolean newRoom = !lastScreenKey.startsWith(AbstractDungeon.floorNum + "|" + (room == null ? "" : room.getClass().getName()));
        lastScreenKey = key;
        JsonObject o = new JsonObject();
        o.add("where", Snap.where());
        if (newRoom) {
            o.add("state", Snap.full());
            MapRoomNode now = AbstractDungeon.getCurrMapNode();
            if (lastNode != null && now != null && now != lastNode && lastNode.y >= 0 && now.y == lastNode.y + 1) {
                boolean pathed = lastNode.isConnectedTo(now);
                o.addProperty("from_x", lastNode.x);
                o.addProperty("from_y", lastNode.y);
                if (!pathed) o.addProperty("flight", true);
            }
            lastNode = now;
        }
        addScreenContents(o);
        log().emit(newRoom ? "room_enter" : "screen", o);
    }

    private void addScreenContents(JsonObject o) {
        switch (AbstractDungeon.screen) {
            case COMBAT_REWARD:
                o.add("rewards", rewards(AbstractDungeon.combatRewardScreen.rewards));
                break;
            case CARD_REWARD:
                o.add("cards", cards(AbstractDungeon.cardRewardScreen.rewardGroup));
                break;
            case BOSS_REWARD:
                o.add("relics", relicIds(AbstractDungeon.bossRelicScreen.relics));
                break;
            case SHOP:
                o.add("shop", shop(AbstractDungeon.shopScreen));
                break;
            case GRID:
                o.add("grid", Snap.pile(AbstractDungeon.gridSelectScreen.targetGroup));
                break;
            default:
                break;
        }
    }

    private static BetterRunLogs instance;
    private AbstractCard lastDiscovery;
    private java.util.Map<String, String> lastDeck = new java.util.HashMap<>();
    private String lastStats = "";
    private java.util.List<String> lastRelics;
    private String lastKeys = "";
    private MapRoomNode lastNode;

    /** Also called as a screen closes, so a pick consumed within the same frame is not missed. */
    static void selectionsNow() {
        if (instance != null && log().open()) instance.watchSelections();
    }

    private void watchDiscovery() {
        AbstractCard d = AbstractDungeon.cardRewardScreen.discoveryCard;
        if (d == lastDiscovery) return;
        lastDiscovery = d;
        if (d == null) return;
        JsonObject o = with("card", Snap.card(d));
        o.add("offered", cards(AbstractDungeon.cardRewardScreen.rewardGroup));
        log().emit("discovery_pick", o);
    }

    /** Deck adds/removes/upgrades and gold/HP/max-HP moves, whatever caused them (shop, campfire, event). */
    private void watchDeckAndStats() {
        java.util.Map<String, String> now = new java.util.HashMap<>();
        for (AbstractCard c : AbstractDungeon.player.masterDeck.group) now.put(c.uuid.toString(), c.cardID + "+" + c.timesUpgraded + "/" + c.misc);
        if (!now.equals(lastDeck)) {
            JsonArray added = new JsonArray();
            JsonArray removed = new JsonArray();
            JsonArray changed = new JsonArray();
            for (AbstractCard c : AbstractDungeon.player.masterDeck.group) {
                String before = lastDeck.get(c.uuid.toString());
                if (before == null) added.add(Snap.card(c));
                else if (!before.equals(now.get(c.uuid.toString()))) changed.add(Snap.card(c));
            }
            for (java.util.Map.Entry<String, String> e : lastDeck.entrySet()) {
                if (!now.containsKey(e.getKey())) {
                    JsonObject r = new JsonObject();
                    r.addProperty("uuid", e.getKey());
                    r.addProperty("was", e.getValue());
                    removed.add(r);
                }
            }
            boolean first = lastDeck.isEmpty();
            lastDeck = now;
            if (!first) {
                JsonObject o = new JsonObject();
                o.add("added", added);
                o.add("removed", removed);
                o.add("changed", changed);
                log().emit("deck_change", o);
            }
        }
        String stats = AbstractDungeon.player.gold + "|" + AbstractDungeon.player.maxHealth
                + (Snap.inCombat() ? "" : "|" + AbstractDungeon.player.currentHealth);
        if (!stats.equals(lastStats)) {
            boolean first = lastStats.isEmpty();
            lastStats = stats;
            if (!first) {
                JsonObject o = new JsonObject();
                o.addProperty("gold", AbstractDungeon.player.gold);
                o.addProperty("maxHp", AbstractDungeon.player.maxHealth);
                o.addProperty("hp", AbstractDungeon.player.currentHealth);
                log().emit("stats_change", o);
            }
        }
    }

    private static String relicKey(com.megacrit.cardcrawl.relics.AbstractRelic r) {
        return r.relicId + "#" + r.counter + (r.grayscale ? "#used" : "");
    }

    /** Relics gained or lost and every counter/used-up change (Winged Boots, Pen Nib, Omamori, ...); act-4 keys. */
    private void watchRelicsAndKeys() {
        java.util.List<String> now = new java.util.ArrayList<>();
        for (com.megacrit.cardcrawl.relics.AbstractRelic r : AbstractDungeon.player.relics) now.add(relicKey(r));
        if (!now.equals(lastRelics)) {
            if (lastRelics != null) {
                JsonObject o = new JsonObject();
                JsonArray before = new JsonArray();
                for (String k : lastRelics) before.add(k);
                o.add("before", before);
                o.add("relics", Snap.relics());
                log().emit("relic_change", o);
            }
            lastRelics = now;
        }
        String keys = (com.megacrit.cardcrawl.core.Settings.hasRubyKey ? "R" : "")
                + (com.megacrit.cardcrawl.core.Settings.hasEmeraldKey ? "E" : "")
                + (com.megacrit.cardcrawl.core.Settings.hasSapphireKey ? "S" : "");
        if (!keys.equals(lastKeys)) {
            JsonObject o = new JsonObject();
            o.addProperty("ruby", com.megacrit.cardcrawl.core.Settings.hasRubyKey);
            o.addProperty("emerald", com.megacrit.cardcrawl.core.Settings.hasEmeraldKey);
            o.addProperty("sapphire", com.megacrit.cardcrawl.core.Settings.hasSapphireKey);
            log().emit("keys", o);
            lastKeys = keys;
        }
    }

    private void watchSelections() {
        String grid = uuids(AbstractDungeon.gridSelectScreen.selectedCards);
        if (!grid.equals(lastGridPick)) {
            lastGridPick = grid;
            if (!grid.isEmpty()) log().emit("grid_select", with("picked", wrap(cards(AbstractDungeon.gridSelectScreen.selectedCards))));
        }
        String hand = uuids(AbstractDungeon.handCardSelectScreen.selectedCards.group);
        if (!hand.equals(lastHandPick)) {
            lastHandPick = hand;
            if (!hand.isEmpty()) log().emit("hand_select", with("picked", wrap(cards(AbstractDungeon.handCardSelectScreen.selectedCards.group))));
        }
    }

    private static JsonObject wrap(JsonArray a) {
        JsonObject o = new JsonObject();
        o.add("cards", a);
        return o;
    }

    private static String uuids(List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (AbstractCard c : cards) sb.append(c.uuid).append(',');
        return sb.toString();
    }

    static JsonArray cards(List<AbstractCard> cards) {
        JsonArray a = new JsonArray();
        if (cards != null) for (AbstractCard c : cards) a.add(Snap.card(c));
        return a;
    }

    private static JsonArray strings(List<String> xs) {
        JsonArray a = new JsonArray();
        if (xs != null) for (String s : xs) a.add(s);
        return a;
    }

    private static JsonArray relicIds(List<AbstractRelic> relics) {
        JsonArray a = new JsonArray();
        if (relics != null) for (AbstractRelic r : relics) a.add(r.relicId);
        return a;
    }

    private static JsonArray rewards(List<RewardItem> rewards) {
        JsonArray a = new JsonArray();
        for (RewardItem r : rewards) {
            JsonObject o = new JsonObject();
            o.addProperty("type", String.valueOf(r.type));
            if (r.goldAmt != 0) o.addProperty("gold", r.goldAmt + r.bonusGold);
            if (r.relic != null) o.addProperty("relic", r.relic.relicId);
            if (r.potion != null) o.addProperty("potion", r.potion.ID);
            if (r.cards != null) o.add("cards", cards(r.cards));
            a.add(o);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    static JsonObject shop(ShopScreen s) {
        JsonObject o = new JsonObject();
        JsonArray cs = new JsonArray();
        for (List<AbstractCard> group : java.util.Arrays.asList(s.coloredCards, s.colorlessCards)) {
            for (AbstractCard c : group) {
                JsonObject co = Snap.card(c);
                co.addProperty("price", c.price);
                cs.add(co);
            }
        }
        o.add("cards", cs);
        JsonArray rs = new JsonArray();
        Object relics = Snap.privateField(s, ShopScreen.class, "relics");
        if (relics != null) {
            for (StoreRelic r : (ArrayList<StoreRelic>) relics) {
                JsonObject ro = new JsonObject();
                ro.addProperty("id", r.relic.relicId);
                ro.addProperty("price", r.price);
                if (r.isPurchased) ro.addProperty("bought", true);
                rs.add(ro);
            }
        }
        o.add("relics", rs);
        JsonArray ps = new JsonArray();
        Object potions = Snap.privateField(s, ShopScreen.class, "potions");
        if (potions != null) {
            for (StorePotion p : (ArrayList<StorePotion>) potions) {
                JsonObject po = new JsonObject();
                po.addProperty("id", p.potion.ID);
                po.addProperty("price", p.price);
                if (p.isPurchased) po.addProperty("bought", true);
                ps.add(po);
            }
        }
        o.add("potions", ps);
        o.addProperty("purgeCost", ShopScreen.actualPurgeCost);
        o.addProperty("purgeAvailable", s.purgeAvailable);
        return o;
    }

    private static JsonArray map() {
        JsonArray rows = new JsonArray();
        if (AbstractDungeon.map == null) return rows;
        for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
            for (MapRoomNode n : row) {
                if (n.getEdges().isEmpty() && n.room == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("x", n.x);
                o.addProperty("y", n.y);
                o.addProperty("sym", n.getRoomSymbol(true));
                if (n.hasEmeraldKey) o.addProperty("emerald", true);
                JsonArray edges = new JsonArray();
                for (MapEdge e : n.getEdges()) {
                    JsonArray ea = new JsonArray();
                    ea.add(e.dstX);
                    ea.add(e.dstY);
                    edges.add(ea);
                }
                o.add("to", edges);
                rows.add(o);
            }
        }
        return rows;
    }
}

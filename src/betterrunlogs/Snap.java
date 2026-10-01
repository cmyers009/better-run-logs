package betterrunlogs;

import com.badlogic.gdx.math.MathUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.EnemyMoveInfo;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Game-state to JSON. Read-only: nothing here may advance an RNG or mutate state. */
final class Snap {
    private Snap() {}

    /** The map and Neow RNGs a new run inherits from the last one until its act and Neow replace them. */
    private static final java.util.Set<Random> leftover = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    static void markLeftoverRngs(boolean newRun) {
        leftover.clear();
        if (!newRun) return;
        if (AbstractDungeon.mapRng != null) leftover.add(AbstractDungeon.mapRng);
        if (NeowEvent.rng != null) leftover.add(NeowEvent.rng);
    }

    static Map<String, Random> gameRngs() {
        Map<String, Random> m = new LinkedHashMap<>();
        m.put("monster", AbstractDungeon.monsterRng);
        m.put("map", AbstractDungeon.mapRng);
        m.put("event", AbstractDungeon.eventRng);
        m.put("merchant", AbstractDungeon.merchantRng);
        m.put("card", AbstractDungeon.cardRng);
        m.put("treasure", AbstractDungeon.treasureRng);
        m.put("relic", AbstractDungeon.relicRng);
        m.put("potion", AbstractDungeon.potionRng);
        m.put("monsterHp", AbstractDungeon.monsterHpRng);
        m.put("ai", AbstractDungeon.aiRng);
        m.put("shuffle", AbstractDungeon.shuffleRng);
        m.put("cardRandom", AbstractDungeon.cardRandomRng);
        m.put("misc", AbstractDungeon.miscRng);
        m.put("neow", NeowEvent.rng);
        m.values().removeIf(leftover::contains);
        return m;
    }

    static JsonArray rngState(Random r) {
        JsonArray a = new JsonArray();
        a.add(r.counter);
        a.add(r.random.getState(0));
        a.add(r.random.getState(1));
        return a;
    }

    static JsonObject allRngs() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Random> e : gameRngs().entrySet()) {
            if (e.getValue() != null) o.add(e.getKey(), rngState(e.getValue()));
        }
        Object seed = privateField(MathUtils.random, java.util.Random.class, "seed");
        if (seed != null) o.addProperty("gdxMathUtils", ((java.util.concurrent.atomic.AtomicLong) seed).get());
        return o;
    }

    static JsonObject card(AbstractCard c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.cardID);
        o.addProperty("uuid", c.uuid.toString());
        if (c.timesUpgraded != 0) o.addProperty("upg", c.timesUpgraded);
        o.addProperty("cost", c.cost);
        if (c.costForTurn != c.cost) o.addProperty("costForTurn", c.costForTurn);
        if (c.misc != 0) o.addProperty("misc", c.misc);
        if (c.freeToPlayOnce) o.addProperty("free", true);
        return o;
    }

    static JsonArray pile(CardGroup g) {
        JsonArray a = new JsonArray();
        for (AbstractCard c : g.group) a.add(card(c));
        return a;
    }

    static JsonArray powers(AbstractCreature cr) {
        JsonArray a = new JsonArray();
        for (AbstractPower p : cr.powers) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.ID);
            o.addProperty("amt", p.amount);
            a.add(o);
        }
        return a;
    }

    static Object privateField(Object obj, Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (ReflectiveOperationException e) { // LOUD-OK: a field missing on a modded monster skips that key
            return null;
        }
    }

    static JsonObject monster(AbstractMonster m, int index) {
        JsonObject o = new JsonObject();
        o.addProperty("i", index);
        o.addProperty("id", m.id);
        o.addProperty("hp", m.currentHealth);
        o.addProperty("maxHp", m.maxHealth);
        o.addProperty("block", m.currentBlock);
        rolledMove(o, m);
        JsonArray hist = new JsonArray();
        for (Byte b : m.moveHistory) hist.add(b);
        o.add("moveHistory", hist);
        if (m.isDying || m.isDead) o.addProperty("dead", true);
        if (m.escaped || m.isEscaping) o.addProperty("escaped", true);
        o.add("powers", powers(m));
        return o;
    }

    static JsonArray monsters() {
        JsonArray a = new JsonArray();
        if (room() == null || room().monsters == null) return a;
        int i = 0;
        for (AbstractMonster m : room().monsters.monsters) a.add(monster(m, i++));
        return a;
    }

    /** Compact [hp, block] per creature, player first: cheap enough for every action. */
    static JsonArray hpVector() {
        JsonArray a = new JsonArray();
        AbstractPlayer p = AbstractDungeon.player;
        a.add(p.currentHealth);
        a.add(p.currentBlock);
        if (room() != null && room().monsters != null) {
            for (AbstractMonster m : room().monsters.monsters) {
                a.add(m.currentHealth);
                a.add(m.currentBlock);
            }
        }
        return a;
    }

    static JsonArray relics() {
        JsonArray a = new JsonArray();
        for (AbstractRelic r : AbstractDungeon.player.relics) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.relicId);
            o.addProperty("counter", r.counter);
            if (r.grayscale) o.addProperty("used", true);
            a.add(o);
        }
        return a;
    }

    static JsonArray potions() {
        JsonArray a = new JsonArray();
        for (AbstractPotion p : AbstractDungeon.player.potions) a.add(p.ID);
        return a;
    }

    static JsonObject player(boolean inCombat) {
        AbstractPlayer p = AbstractDungeon.player;
        JsonObject o = new JsonObject();
        o.addProperty("class", String.valueOf(p.chosenClass));
        o.addProperty("hp", p.currentHealth);
        o.addProperty("maxHp", p.maxHealth);
        o.addProperty("gold", p.gold);
        o.add("relics", relics());
        o.add("potions", potions());
        o.add("deck", pile(p.masterDeck));
        if (inCombat) {
            o.addProperty("block", p.currentBlock);
            o.addProperty("energy", com.megacrit.cardcrawl.ui.panels.EnergyPanel.totalCount);
            o.add("powers", powers(p));
            o.add("hand", pile(p.hand));
            o.add("draw", pile(p.drawPile));
            o.add("discard", pile(p.discardPile));
            o.add("exhaust", pile(p.exhaustPile));
            o.add("limbo", pile(p.limbo));
            if (p.stance != null) o.addProperty("stance", p.stance.ID);
            JsonArray orbs = new JsonArray();
            for (AbstractOrb orb : p.orbs) {
                JsonObject oo = new JsonObject();
                oo.addProperty("id", orb.ID);
                oo.addProperty("evoke", orb.evokeAmount);
                oo.addProperty("passive", orb.passiveAmount);
                orbs.add(oo);
            }
            o.add("orbs", orbs);
            o.addProperty("maxOrbs", p.maxOrbs);
        }
        return o;
    }

    static com.megacrit.cardcrawl.rooms.AbstractRoom room() {
        return AbstractDungeon.getCurrMapNode() == null ? null : AbstractDungeon.getCurrMapNode().room;
    }

    static JsonObject where() {
        JsonObject o = new JsonObject();
        o.addProperty("act", AbstractDungeon.actNum);
        o.addProperty("floor", AbstractDungeon.floorNum);
        o.addProperty("dungeon", AbstractDungeon.id);
        if (AbstractDungeon.getCurrMapNode() != null) {
            o.addProperty("x", AbstractDungeon.getCurrMapNode().x);
            o.addProperty("y", AbstractDungeon.getCurrMapNode().y);
        }
        if (room() != null) {
            o.addProperty("room", room().getClass().getSimpleName());
            o.addProperty("phase", String.valueOf(room().phase));
        }
        o.addProperty("screen", String.valueOf(AbstractDungeon.screen));
        if (AbstractDungeon.actionManager != null && inCombat()) o.addProperty("turn", AbstractDungeon.actionManager.turn);
        return o;
    }

    /** The move rollMove chose. The intent fields lag it until the intent is shown, which waits on battle-start effects. */
    static void rolledMove(JsonObject o, AbstractMonster m) {
        EnemyMoveInfo move = (EnemyMoveInfo) privateField(m, AbstractMonster.class, "move");
        if (move == null) {
            o.addProperty("move", m.nextMove);
            o.addProperty("intent", String.valueOf(m.intent));
            return;
        }
        o.addProperty("move", move.nextMove);
        o.addProperty("intent", String.valueOf(move.intent));
        if (move.baseDamage >= 0) o.addProperty("baseDmg", move.baseDamage);
        if (move.isMultiDamage) o.addProperty("hits", move.multiplier);
    }

    static boolean inCombat() {
        return room() != null && room().phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
    }

    static JsonObject full() {
        JsonObject o = new JsonObject();
        boolean combat = inCombat();
        o.add("where", where());
        o.add("player", player(combat));
        if (combat) o.add("monsters", monsters());
        o.add("rng", allRngs());
        o.addProperty("seed", Settings.seed);
        return o;
    }
}

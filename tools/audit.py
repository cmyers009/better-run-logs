"""Rebuilds deck, gold, max HP, relics (and HP between rooms) from a log's event lines and checks them against every full-state snapshot.

A mismatch means an event the log should have recorded is missing or wrong.
Usage: python3 audit.py <log.jsonl[.gz]> [...]
"""
import gzip, json, sys


def read(path):
    with (gzip.open if path.endswith(".gz") else open)(path, "rt") as f:
        return [json.loads(l) for l in f if l.strip()]


def card_key(c):
    return c["uuid"], c["id"], c.get("upg", 0)


def audit(path):
    ev = read(path)
    deck = player = None
    problems = []
    for e in ev:
        st = e.get("state")
        if st and "player" in st:
            p = st["player"]
            snap = {"deck": sorted(card_key(c) for c in p["deck"]), "gold": p["gold"], "hp": p["hp"],
                    "maxHp": p["maxHp"], "relics": [r["id"] if isinstance(r, dict) else r for r in p["relics"]]}
            if deck is not None and e["t"] != "resume":
                mine = {"deck": sorted(deck.values()), **player}
                for k in snap:
                    if k == "hp" and e["t"] != "room_enter":
                        continue
                    if mine[k] != snap[k]:
                        problems.append((e["seq"], e["t"], k, mine[k], snap[k]))
            deck = {c["uuid"]: card_key(c) for c in p["deck"]}
            player = {k: snap[k] for k in ("gold", "hp", "maxHp", "relics")}
            continue
        if deck is None:
            continue
        t = e["t"]
        if t == "deck_change":
            for c in e.get("removed", []): deck.pop(c["uuid"], None)
            for c in e.get("added", []) + e.get("changed", []): deck[c["uuid"]] = card_key(c)
        elif t == "stats_change":
            for k in ("gold", "hp", "maxHp"):
                if k in e: player[k] = e[k]
        elif t == "relic_change":
            player["relics"] = [r["id"] for r in e["relics"]]
    return ev, problems


def main():
    bad = 0
    for path in sys.argv[1:]:
        ev, problems = audit(path)
        snaps = sum(1 for e in ev if "player" in (e.get("state") or {}))
        print(f"{path.split('/')[-1]}: {len(ev)} lines, {snaps} snapshots, {len(problems)} mismatches")
        for seq, t, k, mine, theirs in problems[:10]:
            bad += 1
            if isinstance(mine, list):
                print(f"  seq {seq} ({t}) {k}: events-only {sorted(set(mine) - set(theirs))[:4]} snapshot-only {sorted(set(theirs) - set(mine))[:4]}")
            else:
                print(f"  seq {seq} ({t}) {k}: events say {mine}, snapshot says {theirs}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

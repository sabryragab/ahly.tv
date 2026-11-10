"""Al Ahly next match and last result (TheSportsDB free key). Runs every 20 minutes."""
import json, time, urllib.request
from pathlib import Path
OUT = Path(__file__).resolve().parent.parent / "data" / "ahly.json"
B = "https://www.thesportsdb.com/api/v1/json/123/"

def evs(path):
    with urllib.request.urlopen(urllib.request.Request(B + path, headers={"User-Agent": "AhlyTV"}), timeout=20) as r:
        j = json.loads(r.read() or b"{}")
    return j.get("events") or j.get("results") or []

def slim(e):
    return {"home": e.get("strHomeTeam"), "away": e.get("strAwayTeam"), "hs": e.get("intHomeScore"), "as": e.get("intAwayScore"),
            "date": e.get("dateEvent"), "time": e.get("strTime"), "ts": e.get("strTimestamp"), "league": e.get("strLeague"),
            "hb": e.get("strHomeTeamBadge"), "ab": e.get("strAwayTeamBadge")}

nxt = [slim(e) for e in evs("eventsnext.php?id=138995")][:3]
last = [slim(e) for e in evs("eventslast.php?id=138995")][:3]
old = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
new = {"next": nxt, "last": last}
if {k: old.get(k) for k in ("next", "last")} != new:
    new["checked"] = time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime())
    OUT.write_text(json.dumps(new, ensure_ascii=False), encoding="utf-8")
    print("updated")
else:
    print("no change")


# ---------- most important match (among the next fixture of each big league + Al Ahly) ----------
LEAGUES = {4829: 60, 4668: 55, 4480: 70, 4328: 50, 4335: 50, 4720: 58, 4332: 35, 4331: 35, 4334: 30}
BIG = {"Al Ahly": 60, "Zamalek": 40, "Al-Hilal": 40, "Al-Nassr": 40, "Al-Ittihad": 35, "Al-Ahli": 30, "Pyramids": 20,
       "Real Madrid": 40, "Barcelona": 40, "Liverpool": 35, "Manchester City": 32, "Arsenal": 30, "Manchester United": 30,
       "Chelsea": 25, "Bayern Munich": 28, "Paris SG": 28, "Juventus": 22, "Inter": 22, "AC Milan": 22, "Atletico Madrid": 22}
MOUT = OUT.parent / "match.json"
cands = []
for lid, w in LEAGUES.items():
    try:
        for e in evs("eventsnextleague.php?id=%d" % lid)[:3]:
            m = slim(e)
            m["score"] = w + BIG.get(m["home"], 0) + BIG.get(m["away"], 0)
            cands.append(m)
        time.sleep(2.2)
    except Exception as ex:
        print("league", lid, "failed", ex)
for m in nxt[:1]:
    m = dict(m); m["score"] = LEAGUES.get(4829, 60) + 60 + BIG.get(m["away"] if m["home"] == "Al Ahly" else m["home"], 0)
    cands.append(m)
seen, uniq = set(), []
for m in cands:
    k = (m["home"], m["away"], m["date"])
    if m["ts"] and k not in seen:
        seen.add(k); uniq.append(m)
if uniq:
    oldm = json.loads(MOUT.read_text(encoding="utf-8")) if MOUT.exists() else {}
    if oldm.get("matches") != uniq:
        MOUT.write_text(json.dumps({"matches": uniq, "checked": time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime())}, ensure_ascii=False), encoding="utf-8")
        print("matches updated", len(uniq))

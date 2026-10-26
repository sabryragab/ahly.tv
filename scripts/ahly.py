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

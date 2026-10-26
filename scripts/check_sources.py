"""Fetch every channel / radio source the app uses, test each stream, and
write cleaned lists into data/ so the app only shows what actually works.

Runs daily on GitHub Actions. Stdlib only.
"""
import json, re, ssl, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "data"
OUT.mkdir(exist_ok=True)
UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE  # many IPTV hosts have broken certs; the TV WebView is lenient too

AR = re.compile(r"[؀-ۿ]")
ARAB_ID = re.compile(r"\.(eg|sa|ae|kw|qa|bh|om|jo|lb|ma|dz|tn|iq|sy|ps|ly|sd|ye)(@|$)", re.I)

TV_SOURCES = [
    ("eg", "https://iptv-org.github.io/iptv/countries/eg.m3u", False),
    ("sa", "https://iptv-org.github.io/iptv/countries/sa.m3u", False),
    ("religious", "https://iptv-org.github.io/iptv/categories/religious.m3u", True),
    ("news", "https://iptv-org.github.io/iptv/categories/news.m3u", True),
    ("sports", "https://iptv-org.github.io/iptv/categories/sports.m3u", True),
]
RB_HOSTS = ["de1", "de2", "fi1", "nl1", "at1"]


def get(url, n=None, timeout=12):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        return r.status, r.headers.get("Content-Type", ""), (r.read(n) if n else r.read())


def probe(url):
    """'ok' if the stream answers with playable data, 'geo' if refused (may work
    from Saudi/Egypt), 'dead' otherwise."""
    try:
        st, ct, body = get(url, n=4096, timeout=10)
        head = body[:4096]
        if b"#EXTM3U" in head or b"#EXT-X" in head:
            return "ok"
        if "audio" in ct or "video" in ct or "mpegurl" in ct.lower() or "octet" in ct:
            return "ok"
        if head[:3] in (b"ID3",) or head[:1] == b"\xff" or head[:4] == b"OggS" or head[:1] == b"G":
            return "ok"
        return "dead"
    except urllib.error.HTTPError as e:
        return "geo" if e.code in (401, 403, 451) else "dead"
    except Exception:
        return "dead"


def parse_m3u(text, arab_only):
    out, meta = [], None
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            a = lambda k: (re.search(k + r'="([^"]*)"', line) or [None, ""])[1]
            meta = {"name": line.rsplit(",", 1)[-1].strip(), "logo": a("tvg-logo"),
                    "group": (a("group-title") or "عام").split(";")[0], "lang": a("tvg-language"), "id": a("tvg-id")}
        elif not line.startswith("#") and meta:
            meta["url"] = line
            out.append(meta)
            meta = None
    if arab_only:
        out = [c for c in out if AR.search(c["name"]) or "arab" in c["lang"].lower() or ARAB_ID.search(c["id"])]
    return out


def write_m3u(path, chans):
    lines = ["#EXTM3U"]
    for c in chans:
        lines.append(f'#EXTINF:-1 tvg-id="{c["id"]}" tvg-logo="{c["logo"]}" group-title="{c["group"]}",{c["name"]}')
        lines.append(c["url"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


report = [f"checked {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}"]
pool = ThreadPoolExecutor(40)

for key, url, arab in TV_SOURCES:
    try:
        chans = parse_m3u(get(url)[2].decode("utf-8", "replace"), arab)
    except Exception as e:
        report.append(f"tv/{key}: SOURCE FAILED {e}")
        continue
    seen, uniq = set(), []
    for c in chans:
        if c["url"] not in seen:
            seen.add(c["url"]); uniq.append(c)
    res = list(pool.map(lambda c: probe(c["url"]), uniq))
    good = [c for c, r in zip(uniq, res) if r == "ok"] + [c for c, r in zip(uniq, res) if r == "geo"]
    write_m3u(OUT / f"tv-{key}.m3u", good)
    report.append(f"tv/{key}: {len(uniq)} listed, {res.count('ok')} ok, {res.count('geo')} geo-blocked kept, {res.count('dead')} dead removed")

# Quran radios (mp3quran)
try:
    j = json.loads(get("https://mp3quran.net/api/v3/radios?language=ar")[2])
    radios = [{"name": r["name"].strip(), "url": r["url"], "logo": ""} for r in j.get("radios", [])]
    res = list(pool.map(lambda r: probe(r["url"]), radios))
    good = [r for r, s in zip(radios, res) if s != "dead"]
    (OUT / "radio-quran.json").write_text(json.dumps(good, ensure_ascii=False), encoding="utf-8")
    report.append(f"radio/quran: {len(radios)} listed, {len(good)} working")
except Exception as e:
    report.append(f"radio/quran: SOURCE FAILED {e}")

# Country radios (radio-browser, several mirrors)
for cc in ("EG", "SA"):
    data = None
    for h in RB_HOSTS:
        try:
            data = json.loads(get(f"https://{h}.api.radio-browser.info/json/stations/search?countrycode={cc}"
                                  "&hidebroken=true&order=clickcount&reverse=true&limit=150")[2])
            break
        except Exception:
            continue
    if data is None:
        report.append(f"radio/{cc}: SOURCE FAILED (all mirrors)")
        continue
    st = [{"name": s["name"].strip(), "url": s.get("url_resolved") or s["url"], "logo": s.get("favicon", "")} for s in data]
    res = list(pool.map(lambda s: probe(s["url"]), st))
    good = [s for s, r in zip(st, res) if r != "dead"]
    (OUT / f"radio-{cc.lower()}.json").write_text(json.dumps(good, ensure_ascii=False), encoding="utf-8")
    report.append(f"radio/{cc}: {len(st)} listed, {len(good)} working")

(OUT / "report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
print("\n".join(report))
if any("SOURCE FAILED" in r for r in report):
    sys.exit(0)  # keep previous good files; report shows the failure

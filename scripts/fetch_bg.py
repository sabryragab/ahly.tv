#!/usr/bin/env python3
"""Downloads curated high-resolution (2560px) photos from Wikimedia Commons into
app/src/main/assets/bg and rewrites scripts/bg.json (author + licence for credits)."""
import json, os, re, sys, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/bg")
UA = {"User-Agent": "AhlyTV/1.0 (personal TV app)"}
# (file name, short id, kind)
PICS = [
    ("Wide shot of the Green Dome at The Prophet's Mosque (Al Masjid-e-Nabawi).jpg", "nabawi1", "m"),
    ("Medium shot of the Green Dome at The Prophet's Mosque (Al Masjid-e-Nabawi).jpg", "nabawi2", "m"),
    ("Nasir al-Mulk Mosque Darafsh (1).JPG", "nasir", "m"),
    ("Muscat, moschea del sultano qaboos, interno, grande sala di preghiera 02,0.jpg", "qaboos1", "m"),
    ("Muscat, moschea del sultano qaboos, interno, grande sala di preghiera 07.jpg", "qaboos2", "m"),
    ("Interior of Blue Mosque in Istanbul, Turkey 002.jpg", "blue", "m"),
    ("Beech and ferns in Gullmarsskogen.jpg", "beech", "n"),
    ("Kuang Si Falls and a turquoise water pool in Luang Prabang province Laos.jpg", "kuangsi", "n"),
    ("Li Phi falls at sunrise with colorful clouds in Don Khon Laos.jpg", "liphi", "n"),
    ("Colorful sky with pink and blue clouds reflecting in the water of a paddy field at dusk in Don Det Laos.jpg", "dusk", "n"),
    ("Red Sea 36.jpg", "redsea1", "n"),
    ("RedSeaGlowingSunset IMG 9128.jpg", "redsea2", "n"),
    ("Mesmerising natural beauty - the milky way and the desert at Al Quaa.jpg", "milky", "n"),
    ("Baumsilhouetten am Uetliberg mit Novembernebel und Sonnenstrahlen.jpg", "fog", "n"),
    ("Mount Shasta, Lake Siskiyou, SW view.jpg", "shasta", "n"),
    ("Fanal (Madeira, Portugal), Lorbeerwald -- 2025 -- 1532.jpg", "fanal", "n"),
    ("Acacia in Ein Khadra Desert Oasis 00 (87).jpg", "acacia", "n"),
    ("JOR-2017-Wadi Rum 06.jpg", "rum", "n"),
    ("Gullfoss, Suðurland, Islandia, 2014-08-16, DD 119.JPG", "gullfoss", "n"),
]


def get(url, binary=False, tries=6):
    for i in range(tries):
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90).read()
            return r if binary else json.loads(r)
        except Exception as e:
            print("retry", i, str(e)[:80], file=sys.stderr)
            time.sleep(6 + i * 8)
    raise SystemExit("failed " + url)


def main():
    for f in os.listdir(OUT):
        if f.endswith(".jpg"):
            os.remove(os.path.join(OUT, f))
    res = []
    for title, sid, kind in PICS:
        q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": "File:" + title,
                                    "prop": "imageinfo", "iiprop": "url|size|extmetadata", "iiurlwidth": 2560})
        d = get("https://commons.wikimedia.org/w/api.php?" + q)
        p = list(d["query"]["pages"].values())[0]
        if "imageinfo" not in p:
            print("missing", title)
            continue
        ii = p["imageinfo"][0]
        m = ii["extmetadata"]
        au = re.sub(r"<[^>]+>", "", m.get("Artist", {}).get("value", "")).replace("\n", " ").strip()
        au = au.split("Derivative")[0].replace("Original:", "").strip()[:40]
        lic = m.get("LicenseShortName", {}).get("value", "")
        data = get(ii["thumburl"], binary=True)
        if len(data) < 60000:
            print("tiny", title)
            continue
        open(os.path.join(OUT, sid + ".jpg"), "wb").write(data)
        res.append({"f": "bg/%s.jpg" % sid, "a": au, "l": lic, "k": kind})
        print("ok", sid, len(data) // 1024, "KB")
        time.sleep(3)
    json.dump(res, open(os.path.join(ROOT, "scripts/bg.json"), "w"), ensure_ascii=False, indent=0)
    print("total", len(res))


main()

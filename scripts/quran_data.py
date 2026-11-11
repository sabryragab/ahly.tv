#!/usr/bin/env python3
"""Builds data/quran-text.json (Uthmani text, basmala stripped) and
data/qt/<read>.json (ayah start times in ms per surah) for every reciter in
data/quran.json that mp3quran.net publishes timings for. Runs on a GitHub runner."""
import json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, "data")
UA = {"User-Agent": "Mozilla/5.0 AhlyTV"}


def get(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            return json.load(urllib.request.urlopen(req, timeout=40))
        except Exception as e:
            if i == tries - 1:
                raise
            time.sleep(2 + i * 2)


def text():
    out = os.path.join(D, "quran-text.json")
    d = get("https://api.alquran.cloud/v1/quran/quran-uthmani")["data"]["surahs"]
    res = []
    for s in d:
        ay = [a["text"].replace("﻿", "").strip() for a in s["ayahs"]]
        n = s["number"]
        if n not in (1, 9) and ay:
            w = ay[0].split(" ")
            if len(w) > 4 and w[0].startswith("بِسْمِ"):
                ay[0] = " ".join(w[4:])
        res.append(ay)
    assert len(res) == 114 and sum(len(x) for x in res) == 6236, "bad text"
    json.dump(res, open(out, "w"), ensure_ascii=False, separators=(",", ":"))
    print("text ok", os.path.getsize(out))


def timings():
    reads = get("https://www.mp3quran.net/api/v3/ayat_timing/reads")
    quran = json.load(open(os.path.join(D, "quran.json")))
    by = {r["folder_url"]: r for r in reads if r.get("soar_count") == 114}
    os.makedirs(os.path.join(D, "qt"), exist_ok=True)
    idx = {}
    for i, r in enumerate(quran["reciters"]):
        m = by.get(r["s"])
        if m:
            idx[i] = m["id"]

    def one(read):
        res = {}
        for s in range(1, 115):
            try:
                d = get("https://www.mp3quran.net/api/v3/ayat_timing?surah=%d&read=%d" % (s, read))
            except Exception as e:
                print("fail", read, s, e)
                return None
            n = max(a["ayah"] for a in d)
            arr = [-1] * (n + 1)
            for a in d:
                arr[a["ayah"]] = a["start_time"]
            res[str(s)] = arr
        return res

    done = {}
    uniq = sorted(set(idx.values()))
    with ThreadPoolExecutor(4) as ex:
        for read, r in zip(uniq, ex.map(one, uniq)):
            if r:
                json.dump(r, open(os.path.join(D, "qt", "%d.json" % read), "w"), separators=(",", ":"))
                done[read] = 1
    mp = {str(i): rd for i, rd in idx.items() if rd in done}
    json.dump(mp, open(os.path.join(D, "qt", "index.json"), "w"), separators=(",", ":"))
    print("timings", len(mp), "reciters of", len(quran["reciters"]))


if __name__ == "__main__":
    text()
    timings()

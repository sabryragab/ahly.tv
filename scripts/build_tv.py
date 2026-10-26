"""Builds app/src/main/assets/index.html from index.src.html (embeds the azkar)."""
import json
from pathlib import Path
root = Path(__file__).resolve().parent.parent
src = (root / "app/src/main/assets/index.src.html").read_text(encoding="utf-8")
az = json.loads((root / "scripts/azkar.json").read_text(encoding="utf-8"))
bgs = json.loads((root / "scripts/bg.json").read_text(encoding="utf-8"))
out = src.replace("__AZKAR__", json.dumps(az, ensure_ascii=False)).replace("__BGS__", json.dumps(bgs, ensure_ascii=False))
(root / "app/src/main/assets/index.html").write_text(out, encoding="utf-8")
# guard: CSS/JS features older Android TV browsers lack
bad = [w for w in ("clamp(", "min(", "max(", "env(", "aspect-ratio", "inset:", "?.", "??") if w in out.split("<script>")[0] + "".join(out.split("<style>")[1:2])]
css = out[out.index("<style>"):out.index("</style>")]
bad = [w for w in ("clamp(", "min(", "max(", "env(", "aspect-ratio", "inset:") if w in css.replace("minmax(", "")]
js = out[out.rindex("<script>"):]
bad += [w for w in ("?.", "??", "replaceAll(", ".at(", "structuredClone") if w in js]
if bad:
    raise SystemExit("unsupported on old TV browsers: " + ", ".join(bad))
print("built index.html", len(out))

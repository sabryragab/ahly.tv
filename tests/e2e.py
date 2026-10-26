"""End-to-end check of the TV app in real Google Chrome (same engine family as the
Android TV WebView). Loads the bundled page from file://, drives it with arrow
keys like the remote, opens every channel list and radio list, and plays the
first streams. Writes test-report.txt; exits non-zero if a list fails to load."""
import sys, time
from pathlib import Path
from playwright.sync_api import sync_playwright

PAGE = Path(sys.argv[1]).resolve().as_uri()
lines, failed = [], False


REPORT = Path("test-report.txt")
REPORT.write_text("", encoding="utf-8")


def log(s):
    print(s, flush=True)
    lines.append(s)
    with REPORT.open("a", encoding="utf-8") as f:
        f.write(s + "\n")


def wait_tiles(page, grid, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        n = page.locator(f"{grid} .ch").count()
        if n:
            return n
        if page.locator(f"{grid} .state b").count():
            return -1
        time.sleep(0.5)
    return 0


def media_plays(page, sel, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        t = page.evaluate(f"(()=>{{const m=document.querySelector('{sel}');return m?m.currentTime:0}})()")
        if t and t > 0.5:
            return True
        time.sleep(1)
    return False


def main():
  global failed
  with sync_playwright() as p:
      b = p.chromium.launch(channel="chrome", args=[
          "--allow-file-access-from-files", "--disable-web-security",
          "--autoplay-policy=no-user-gesture-required", "--mute-audio"])
      page = b.new_page(viewport={"width": 1920, "height": 1080})
      # behave like an older TV WebView: remove newer DOM methods before the app loads
      page.add_init_script("delete Element.prototype.replaceChildren; delete Document.prototype.replaceChildren; delete DocumentFragment.prototype.replaceChildren;")
      errors = []
      page.on("pageerror", lambda e: errors.append(str(e)))
      page.goto(PAGE)
      page.wait_for_timeout(1500)

      # remote navigation: start on the home tab, move right through the tabs
      focused = page.evaluate("document.activeElement && document.activeElement.textContent.trim()")
      log(f"focus on open: {focused}")
      page.evaluate("tvKey('R')")
      log(f"after RIGHT: {page.evaluate('document.activeElement.textContent.trim()')}")
      page.evaluate("tvKey('D')")
      log(f"after DOWN: {page.evaluate('document.activeElement.className')}")
      log(f"Hls loaded: {page.evaluate('!!window.Hls')}")
      log("prayer: " + page.locator("#home-prayers").inner_text().replace(chr(10), " "))
      nx = page.locator("#nx-name").inner_text()
      log(f"next prayer card: {nx} {page.locator('#nx-cd').inner_text()}")
      if nx.strip() in ("", "—") or not page.locator("#home-prayers .pr").count():
          failed = True
          log("PRAYER TIMES MISSING")

      page.evaluate("show('apps')")
      log("apps tab: " + page.locator("#apps-grid").inner_text().strip()[:60])
      # TV lists
      page.evaluate("show('tv')")
      chips = page.locator("#tv-sources .chip")
      for i in range(chips.count()):
          name = chips.nth(i).inner_text()
          chips.nth(i).click()
          page.wait_for_timeout(300)
          n = wait_tiles(page, "#tv-grid")
          if n <= 0:
              failed = True
              log(f"TV {name}: FAILED to load")
              continue
          played = 0
          for k in range(min(3, n)):
              page.evaluate(f"playChannel({k})")
              ok = media_plays(page, "#video")
              played += ok
              page.evaluate("closePlayer()")
          log(f"TV {name}: {n} channels, first 3 played: {played}/3")
      page.screenshot(path="shot-tv.png")

      # Radio lists
      page.evaluate("show('radio')")
      chips = page.locator("#rd-sources .chip")
      for i in range(chips.count()):
          name = chips.nth(i).inner_text()
          chips.nth(i).click()
          page.wait_for_timeout(300)
          n = wait_tiles(page, "#rd-grid")
          if n <= 0:
              failed = True
              log(f"Radio {name}: FAILED to load")
              continue
          played = 0
          for k in range(min(3, n)):
              page.locator("#rd-grid .ch").nth(k).click()
              played += media_plays(page, "#radio", 15)
          page.evaluate("stopRadio()")
          log(f"Radio {name}: {n} stations, first 3 played: {played}/3")

      # adhan overlay + azkar counter
      page.evaluate("showAdhan('dhuhr')")
      log(f"adhan overlay shows: {page.locator('#adhan').is_visible()}")
      page.evaluate("tvKey('C')")
      log(f"adhan closes with OK: {not page.locator('#adhan').is_visible()}")
      page.evaluate("show('azkar')")
      page.wait_for_timeout(300)
      first = page.locator("#zk-list .zk").first
      before = first.locator(".cnt").inner_text()
      first.click()
      log(f"azkar counter: {before} -> {first.locator('.cnt').inner_text()}")
      page.evaluate("show('home')")
      page.screenshot(path="shot-home.png")

      log(f"page errors: {errors if errors else 'none'}")
      b.close()


try:
    main()
except Exception:
    import traceback
    log("CRASH: " + traceback.format_exc())
    failed = True
sys.exit(1 if failed else 0)

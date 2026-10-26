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
        n = page.locator(f"{grid} .card").count()
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
      chips = page.locator("#tv-sources .chip:not(.add)")
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
          for k in range(min(2, n)):
              page.evaluate(f"playChannel({k})")
              ok = media_plays(page, "#video")
              played += ok
              page.evaluate("closePlayer()")
          log(f"TV {name}: {n} channels, first 2 played: {played}/2")
      page.evaluate("show('tv')")
      page.locator("#tv-sources .chip").first.click()
      wait_tiles(page, "#tv-grid")
      page.screenshot(path="shot-tv.png")
      # playlist sent from the phone page
      page.evaluate("onPhoneAdd({id:'utest',name:'قايمة من الموبايل',url:'https://raw.githubusercontent.com/sabryragab/ahly.tv/main/data/tv-sa.m3u'})")
      page.wait_for_timeout(300)
      n = wait_tiles(page, "#tv-grid")
      log(f"playlist added from phone: {n} channels, chip: {page.locator('#tv-sources .chip').first.inner_text()}")
      if n <= 0:
          failed = True
      page.evaluate("deletePlaylist('utest')")
      page.locator("#pl-open").click()
      page.wait_for_timeout(300)
      log(f"add-from-phone panel shows: {page.locator('#pl-panel').is_visible()}")
      page.screenshot(path="shot-add.png")
      page.evaluate("document.querySelector('#pl-close').click()")

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
              page.locator("#rd-grid .card").nth(k).click()
              played += media_plays(page, "#radio", 15)
          page.evaluate("stopRadio()")
          log(f"Radio {name}: {n} stations, first 3 played: {played}/3")
          tops = page.evaluate("Array.from(document.querySelectorAll('#rd-grid .card')).slice(0,5).map(e=>Math.round(e.getBoundingClientRect().top))")
          if max(tops) - min(tops) > 12:
              failed = True
              log(f"RADIO CARDS NOT IN A ROW: {tops}")
          if i == 0:
              page.screenshot(path="shot-radio.png")

      # adhan overlay + azkar counter
      page.evaluate("showAdhan('dhuhr')")
      log(f"adhan overlay shows: {page.locator('#adhan').is_visible()}")
      page.evaluate("tvKey('C')")
      log(f"adhan closes with OK: {not page.locator('#adhan').is_visible()}")
      page.evaluate("show('azkar')")
      page.wait_for_timeout(300)
      before = page.locator("#zk-cnt").inner_text()
      page.locator("#zk-reader").click()
      log(f"azkar counter: {before} -> {page.locator('#zk-cnt').inner_text()}")
      page.wait_for_timeout(1200)  # a finished zikr moves on by itself
      page.evaluate("document.querySelector('#zk-reader').focus(); tvKey('L')")
      log(f"azkar next with LEFT: {page.locator('#zk-pos').inner_text()}")
      fs = page.evaluate("getComputedStyle(document.querySelector('#zk-text p')).fontSize")
      log(f"azkar font size at 1920px: {fs}")
      page.screenshot(path="shot-azkar.png")
      page.locator("#zk-listen").click()
      ok = media_plays(page, '#zk-audio', 25)
      st = page.evaluate("(()=>{const a=document.querySelector('#zk-audio');return {src:a.currentSrc,paused:a.paused,rs:a.readyState,ns:a.networkState,err:a.error&&a.error.code,t:a.currentTime}})()")
      log(f"azkar audio plays: {ok} {st}")
      page.evaluate("stopZkAudio()")
      # Quran library
      page.evaluate("show('quran')")
      page.wait_for_timeout(2500)
      ns = page.locator("#q-surahs .surah").count()
      log(f"quran library: {ns} surahs, {page.locator('#q-reciters .chip').count()} reciters")
      if ns != 114:
          failed = True
      page.locator("#q-surahs .surah").nth(17).click()
      log(f"quran audio plays (Al-Kahf): {media_plays(page, '#radio', 20)} {page.locator('#np-name').inner_text()}")
      page.screenshot(path="shot-quran.png")
      page.evaluate("stopRadio()")
      # favourites, recent, channel number
      page.evaluate("show('tv')")
      page.locator("#tv-sources .chip:not(.add)").nth(0).click()
      wait_tiles(page, "#tv-grid")
      fav = page.evaluate("(()=>{const c=document.querySelector('#tv-grid .card');c.focus();tvKey('CL');return document.querySelector('#tv-sources .chip').textContent})()")
      log(f"long OK adds favourite: {fav}")
      page.evaluate("tvKey('CL')")
      # screensaver
      page.evaluate("show('home')")
      page.wait_for_timeout(2500)
      log(f"weather: {page.locator('#wx-now').inner_text()} {page.locator('#wx-t').inner_text()}")
      log(f"ahly card: {page.locator('#ahly').inner_text().replace(chr(10),' ')[:120]}")
      log(f"verse/hadith: {page.locator('#ayah').inner_text()[:60]}")
      page.evaluate("lastInput=Date.now()-11*60000")
      page.wait_for_timeout(1600)
      sv = page.locator('#saver').is_visible()
      page.screenshot(path="shot-saver.png")
      page.evaluate("tvKey('R')")
      log(f"screensaver after idle: {sv}, closes on key: {not page.locator('#saver').is_visible()}")
      if not sv:
          failed = True
      page.screenshot(path="shot-home.png", full_page=True)
      rel = page.evaluate("(async()=>{const t=await (await fetch('https://raw.githubusercontent.com/sabryragab/ahly.tv/main/data/tv-religious.m3u')).text();return t.split('\\n').filter(l=>l.startsWith('#EXTINF')).map(l=>l.slice(l.lastIndexOf(',')+1)).slice(0,12).join(' | ')})()")
      log(f"islamic list starts with: {rel}")

      log(f"page errors: {errors if errors else 'none'}")
      b.close()


try:
    main()
except Exception:
    import traceback
    log("CRASH: " + traceback.format_exc())
    failed = True
sys.exit(1 if failed else 0)

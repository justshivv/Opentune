// The home page's live parts: the hero's player, the header, the latest release and its downloads,
// the star count and the iPhone build. Everything here enhances a page that already
// reads fine without it; reveals are CSS-only (site.css).
(() => {
  const $ = (id) => document.getElementById(id);

  /* The hero's player: a few songs in turn, the hero taking each cover's colours as the
     app does. It runs by itself while it's on screen (not with reduced motion), and the
     three buttons work. The first song is in the page already, so it reads without this. */
  (() => {
    const np = $("np"), hero = $("hero");
    if (!np || !hero) return;
    const SONGS = [
      { id: "weeknd", t: "Blinding Lights", a: "The Weeknd", alt: "The cover of After Hours by The Weeknd", c: ["#b3122a", "#d08a2e", "#5a1a2a"], at: 64 },
      { id: "aujla", t: "Softly", a: "Karan Aujla", alt: "The cover of Making Memories by Karan Aujla", c: ["#7d5bd1", "#c8876a", "#2f2466"], at: 47 },
      { id: "rockstar", t: "Kun Faya Kun", a: "A.R. Rahman", alt: "The cover of the Rockstar soundtrack", c: ["#e2532f", "#f0a03a", "#8a1c22"], at: 92 },
      { id: "sza", t: "Kill Bill", a: "SZA", alt: "The cover of SOS by SZA", c: ["#1f6fb5", "#3fb0c8", "#0b3f86"], at: 31 },
      { id: "dualipa", t: "Future Nostalgia", a: "Dua Lipa", alt: "The cover of Future Nostalgia by Dua Lipa", c: ["#d99a3a", "#c2453a", "#1f6a5c"], at: 75 },
    ];
    const LENGTH = 200, EVERY = 4800;
    const art = $("np-art"), title = $("np-title"), artist = $("np-artist"), fill = $("np-fill"), now = $("np-now"), left = $("np-left"), play = $("np-play");
    const covers = [art.querySelector("img")];
    const clock = (s) => Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0");
    const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
    let at = 0, pos = SONGS[0].at, playing = !still, seen = true, timer = 0, swap = 0;
    const cover = (i) => {
      if (!covers[i]) {
        const img = new Image(560, 560);
        img.alt = SONGS[i].alt;
        img.decoding = "async";
        img.src = "img/covers/" + SONGS[i].id + ".webp";
        art.appendChild(img);
        covers[i] = img;
      }
      return covers[i];
    };
    const paint = () => {
      fill.style.width = (pos / LENGTH) * 100 + "%";
      now.textContent = clock(pos);
      left.textContent = "-" + clock(LENGTH - pos);
    };
    function show(i) {
      at = (i + SONGS.length) % SONGS.length;
      const song = SONGS[at], img = cover(at);
      const light = () => {
        covers.forEach((c) => c && c.classList.toggle("on", c === img));
        song.c.forEach((c, n) => hero.style.setProperty("--c" + (n + 1), c));
      };
      // The colours follow the cover, so they wait for it.
      if (img.complete) light(); else img.addEventListener("load", light, { once: true });
      np.classList.add("swap");
      clearTimeout(swap);
      swap = setTimeout(() => { title.textContent = song.t; artist.textContent = song.a; np.classList.remove("swap"); }, 160);
      pos = song.at;
      paint();
      cover(at + 1 < SONGS.length ? at + 1 : 0);
    }
    const schedule = () => {
      clearInterval(timer);
      if (!playing || !seen) return;
      let ticks = 0;
      timer = setInterval(() => {
        pos = Math.min(LENGTH, pos + 1);
        paint();
        if (++ticks * 1000 >= EVERY) { show(at + 1); ticks = 0; }
      }, 1000);
    };
    $("np-next").addEventListener("click", () => { show(at + 1); schedule(); });
    $("np-prev").addEventListener("click", () => { show(at - 1); schedule(); });
    play.addEventListener("click", () => {
      playing = !playing;
      np.classList.toggle("paused", !playing);
      play.setAttribute("aria-label", playing ? "Pause the preview" : "Play the preview");
      schedule();
    });
    if (still) { np.classList.add("paused"); play.setAttribute("aria-label", "Play the preview"); }
    if ("IntersectionObserver" in window) new IntersectionObserver(([e]) => { seen = e.isIntersecting; schedule(); }).observe(np);
    document.addEventListener("visibilitychange", () => { seen = !document.hidden; schedule(); });
    paint();
    schedule();
  })();

  /* Header: a little more solid once the page has moved. */
  const nav = $("nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 8);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* Releases. releases.json is written next to this page each time the site deploys, and
     the site deploys after every release, so it names the newest build without an edit.
     GitHub's API is asked as well for download counts, when its hourly limit allows. */
  const LATEST_PAGE = "https://github.com/justshivv/Opentune/releases/latest";
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const mb = (bytes) => (bytes / 1048576).toFixed(1) + " MB";
  const ago = (iso) => {
    const d = (Date.now() - new Date(iso)) / 864e5;
    return d < 1 ? "today" : d < 2 ? "yesterday" : d < 30 ? Math.floor(d) + " days ago" : d < 60 ? "a month ago" : Math.floor(d / 30) + " months ago";
  };
  // GitHub's generated notes end each line with "by @someone in <link>"; only the change is kept.
  const cleanNote = (l) => l.replace(/^[-*•] /, "").replace(/\*\*/g, "").replace(/\(?https?:\/\/\S+\)?/g, "")
    .replace(/\s+by\s+@[\w-]+(\s+in)?\s*$/i, "").replace(/\s+in\s*$/i, "").trim();
  const notesOf = (rel) => {
    if (Array.isArray(rel.highlights) && rel.highlights.length) return rel.highlights.map(cleanNote).filter(Boolean);
    return (rel.body || "").split("\n").map((l) => l.trim()).filter((l) => /^[-*•] /.test(l))
      .map(cleanNote).filter(Boolean).slice(0, 4);
  };
  const apkOf = (rel) => {
    const assets = rel.assets || [];
    return assets.find((a) => /universal\.apk$/.test(a.name)) || assets.find((a) => /\.apk$/.test(a.name));
  };

  function renderReleases(list, live) {
    const box = $("rel-list");
    box.textContent = "";
    list.slice(0, 1).forEach((rel, i) => {
      const apk = apkOf(rel);
      const url = apk && apk.browser_download_url;
      const notes = notesOf(rel);
      const when = new Date(rel.published_at);
      const dated = isNaN(when) ? "" : when.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) + " · " + ago(rel.published_at);
      const downloads = (rel.assets || []).filter((a) => /\.apk$/.test(a.name)).reduce((n, a) => n + (a.download_count || 0), 0);
      const row = document.createElement("article");
      row.className = "rel";
      row.innerHTML =
        '<div class="ver"><b>' + esc(rel.tag_name) + (i === 0 ? '<span class="new">Latest</span>' : "") + "</b>" + (dated ? "<small>" + dated + "</small>" : "") + "</div>" +
        '<div class="notes"><h3>' + esc(rel.name || rel.tag_name) + "</h3>" +
        (notes.length ? "<ul>" + notes.map((n) => "<li>" + esc(n) + "</li>").join("") + "</ul>" : '<p class="muted">The full notes are on GitHub.</p>') + "</div>" +
        '<div class="side">' + (url ? '<a class="btn" href="' + esc(url) + '">APK' + (apk.size ? ' <span class="sub">' + mb(apk.size) + "</span>" : "") + "</a>" : "") +
        (rel.html_url ? '<a href="' + esc(rel.html_url) + '">Release notes</a>' : "") +
        (live && downloads ? '<span class="muted">' + downloads.toLocaleString("en-US") + " downloads</span>" : "") + "</div>";
      box.appendChild(row);
    });

    const rel = list[0];
    const apk = apkOf(rel);
    const url = apk && apk.browser_download_url;
    const notes = notesOf(rel);
    $("ver-badge").textContent = rel.tag_name;
    if (notes.length) $("ver-note").textContent = notes[0];
    // Download buttons point straight at the newest universal APK; without one, GitHub's latest page.
    document.querySelectorAll("[data-latest-apk]").forEach((link) => { link.href = url || LATEST_PAGE; });
    const size = $("dl-size");
    size.hidden = !(url && apk.size);
    if (url && apk.size) size.textContent = mb(apk.size);
    const when = new Date(rel.published_at);
    $("android-version").textContent = rel.tag_name + (isNaN(when) ? "" : ", released " + ago(rel.published_at)) +
      (apk && apk.size ? " · universal APK, " + mb(apk.size) : "") + ".";
    // Desktop buttons go straight to this release's files once it has them.
    const file = (re) => (rel.assets || []).find((a) => re.test(a.name));
    const desktop = { windows: file(/windows-x64-setup\.exe$/) || file(/\.msi$/), mac: file(/macos-[^.]+\.dmg$/) || file(/\.dmg$/), linux: file(/\.AppImage$/) };
    document.querySelectorAll("[data-desktop]").forEach((link) => {
      const a = desktop[link.dataset.desktop];
      link.href = a ? a.browser_download_url : (rel.html_url || LATEST_PAGE);
      const sub = link.querySelector(".sub");
      if (a && a.size && !sub) link.insertAdjacentHTML("beforeend", ' <span class="sub">' + mb(a.size) + "</span>");
    });
    $("rel-intro").textContent = "The newest of " + list.length + (list.length === 1 ? " published build" : " published builds") + ", straight from GitHub Releases.";
    $("rel-source").innerHTML = (live ? "Read live from GitHub." : "As of this site's last update, which follows every release.") +
      ' <a href="https://github.com/justshivv/Opentune/releases">See every release on GitHub</a>';
  }

  const published = (list) => (Array.isArray(list) ? list.filter((r) => !r.draft && !r.prerelease) : []);
  const getJson = (url, opts) => fetch(url, opts).then((r) => (r.ok ? r.json() : Promise.reject(r.status)));
  let shown = false;
  getJson("releases.json", { cache: "no-cache" }).then(published)
    .then((list) => { if (list.length) { renderReleases(list, false); shown = true; } })
    .catch(() => {})
    .then(() => getJson("https://api.github.com/repos/justshivv/Opentune/releases?per_page=10"))
    .then(published)
    .then((list) => { if (list.length) { renderReleases(list, true); shown = true; } })
    .catch(() => {})
    .then(() => {
      if (shown) return;
      $("rel-source").innerHTML = 'Couldn\'t load the list just now. <a href="https://github.com/justshivv/Opentune/releases">See every release on GitHub</a>.';
    });

  getJson("https://api.github.com/repos/justshivv/Opentune")
    .then((repo) => {
      if (!(repo.stargazers_count > 0)) return;
      const s = $("stars");
      s.textContent = "★ " + repo.stargazers_count.toLocaleString("en-US");
      s.hidden = false;
    }).catch(() => {});

  // ios/source.json is written at deploy time once an iPhone build exists.
  getJson("ios/source.json", { cache: "no-cache" }).then((src) => {
    const v = src && src.apps && src.apps[0] && src.apps[0].versions && src.apps[0].versions[0];
    if (!v || !v.version) return;
    $("ios-ver").textContent = "Current build " + v.version + (v.size ? " · " + mb(v.size) : "") + " · iOS " + String(v.minOSVersion || "16.0").replace(/\.0$/, "") + " or newer.";
  }).catch(() => {});

})();

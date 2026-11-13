// The home page's live parts: the header line, the feature tour, release downloads,
// the activity calendar and gentle reveals. Everything here enhances a page that already
// reads fine without it.
(() => {
  const $ = (id) => document.getElementById(id);
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* Header: a hairline once the page has moved. */
  const nav = $("nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 8);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* Feature tour: pick a feature, its screen crossfades in. */
  const tour = $("tour");
  if (tour) {
    const items = [...tour.querySelectorAll(".tour-item")];
    const shots = [...document.querySelectorAll(".tour-stage img")];
    const select = (item) => {
      items.forEach((it) => {
        const on = it === item;
        it.setAttribute("aria-current", String(on));
        it.querySelector("button").setAttribute("aria-expanded", String(on));
      });
      shots.forEach((img) => img.classList.toggle("on", img.dataset.shot === item.dataset.shot));
    };
    items.forEach((it, i) => {
      const button = it.querySelector("button");
      button.setAttribute("aria-expanded", String(i === 0));
      button.addEventListener("click", () => select(it));
      button.addEventListener("keydown", (e) => {
        const step = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : 0;
        if (!step) return;
        e.preventDefault();
        const next = items[(i + step + items.length) % items.length];
        next.querySelector("button").focus();
        select(next);
      });
    });
  }

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
    list.slice(0, 3).forEach((rel, i) => {
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
    $("rel-intro").textContent = list.length + (list.length === 1 ? " published build" : " published builds") + ", straight from GitHub Releases.";
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

  /* A year of work on GitHub, one square a day, newest on the right. */
  function renderCalendar(days) {
    const grid = $("cal-grid"), months = $("cal-months");
    grid.textContent = ""; months.textContent = "";
    const first = new Date(days[0].date + "T00:00:00");
    for (let k = 0; k < first.getDay(); k++) { const pad = document.createElement("i"); pad.style.visibility = "hidden"; grid.appendChild(pad); }
    const dateFmt = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });
    const monthName = new Intl.DateTimeFormat("en-GB", { month: "short" });
    let total = 0, busiest = null, lastMonth = -1, week = 0;
    days.forEach((d, i) => {
      const date = new Date(d.date + "T00:00:00");
      week = Math.floor((i + first.getDay()) / 7);
      if ((date.getDay() === 0 || i === 0) && date.getMonth() !== lastMonth && date.getDate() <= 7) {
        const m = document.createElement("span");
        m.textContent = monthName.format(date);
        m.style.gridColumn = String(week + 1);
        months.appendChild(m);
        lastMonth = date.getMonth();
      }
      const cell = document.createElement("i");
      cell.dataset.l = String(d.level);
      cell.title = (d.count === 0 ? "No contributions" : d.count + (d.count === 1 ? " contribution" : " contributions")) + " on " + dateFmt.format(date);
      grid.appendChild(cell);
      total += d.count;
      if (!busiest || d.count > busiest.count) busiest = d;
    });
    $("cal-total").textContent = total.toLocaleString("en-US");
    $("cal-note").textContent = "contributions in the last year" + (busiest && busiest.count > 0 ? ", busiest on " + dateFmt.format(new Date(busiest.date + "T00:00:00")) + " with " + busiest.count : "");
    const scroller = $("cal-scroll"), cal = $("cal"), weeks = week + 1;
    const fit = () => {
      const gap = scroller.clientWidth > 640 ? 4 : 3;
      const c = Math.max(10, Math.min(16, Math.floor((scroller.clientWidth - gap * (weeks - 1)) / weeks)));
      cal.style.setProperty("--c", c + "px");
      cal.style.setProperty("--g", gap + "px");
      scroller.scrollLeft = scroller.scrollWidth;
    };
    fit();
    addEventListener("resize", fit, { passive: true });
  }
  getJson("https://github-contributions-api.jogruber.de/v4/justshivv?y=last")
    .then((data) => { if (data && Array.isArray(data.contributions) && data.contributions.length) renderCalendar(data.contributions); else throw 0; })
    .catch(() => {
      $("cal-total").innerHTML = '<a href="https://github.com/justshivv/Opentune/commits/main">See the history on GitHub</a>';
      $("cal-note").textContent = "The activity calendar couldn't be loaded just now.";
      $("cal-scroll").hidden = true;
    });

  /* Reveals: headings settle in as they reach the screen. Without the script they're simply there. */
  if (!still && "IntersectionObserver" in window) {
    const targets = document.querySelectorAll(".reveal");
    const io = new IntersectionObserver((entries) => entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
    }), { rootMargin: "0px 0px -10% 0px" });
    // Anything already on screen stays visible from the first frame.
    targets.forEach((t) => { if (t.getBoundingClientRect().top < innerHeight) t.classList.add("in"); io.observe(t); });
    document.documentElement.classList.add("reveal-ready");
  }
})();

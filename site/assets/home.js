// The home page's live parts: the header glass, the latest release and its downloads,
// the star count and the iPhone build. Everything here enhances a page that already
// reads fine without it; reveals are CSS-only (site.css).
(() => {
  const $ = (id) => document.getElementById(id);

  /* Header: glass once the page has moved. */
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

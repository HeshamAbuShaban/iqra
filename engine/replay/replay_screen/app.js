/* The replay screen's behaviour. No frameworks, one data object (window.REPLAY)
 * written by replay_export.py. Paint rules mirror the reader's honest ones:
 * a word takes its recorded verdict at its recorded decision time; words and
 * pages the record does not cover show nothing rather than something. */

(function () {
  var R = window.REPLAY;
  if (!R) return;

  var canvas = document.getElementById('page');
  var ctx = canvas.getContext('2d');
  var slider = document.getElementById('slider');
  var playBtn = document.getElementById('playBtn');
  var speedBtn = document.getElementById('speedBtn');
  var tEl = document.getElementById('t');
  var lockEl = document.getElementById('lock');
  var feedEl = document.getElementById('feed');
  var causeEl = document.getElementById('cause');
  var diagEl = document.getElementById('diag');
  var recEl = document.getElementById('recname');
  var metaEl = document.getElementById('recmeta');
  var honestEl = document.getElementById('honest');

  var pageNames = Object.keys(R.pages).map(Number).sort(function (a, b) { return a - b; });
  var curPage = pageNames[0];
  var lastT = R.lastT;
  var t = 0;
  var playing = false;
  var speed = 1; // slider-units per tick; base playback = ~1 s per 0.25 s tick

  recEl.textContent = R.name;
  metaEl.textContent = R.meta;
  honestEl.textContent = R.honest;

  var speeds = [1, 2, 4, 8];
  speedBtn.onclick = function () {
    speed = speeds[(speeds.indexOf(speed) + 1) % speeds.length];
    speedBtn.textContent = speed + '\u00d7';
  };

  /* ---- timeline items ------------------------------------------------ */
  // Verdicts, sorted by their recorded decision time: this is the timeline
  // the record actually holds, and the feed replays it in that order.
  var verdicts = R.words
    .map(function (w, i) { return { w: w, i: i }; })
    .filter(function (v) { return typeof v.w.wt === 'number'; })
    .sort(function (a, b) { return a.w.wt - b.w.wt; });

  // Words without wt (UNKNOWN/SKIPPED are decided as no-verdict): paint them
  // when the lock first stands on their ayah in the frames.
  var lockFirst = {}; // "surah:ayah" -> first frame t with that lock
  R.frames.forEach(function (f) {
    if (f.s == null || f.lock == null) return;
    var k = f.s + ':' + f.lock;
    if (!(k in lockFirst)) lockFirst[k] = f.t;
  });
  R.words.forEach(function (w) {
    var parts = w.key.split(':');
    w.paintAt = (typeof w.wt === 'number')
      ? w.wt
      : (lockFirst[parts[0] + ':' + parts[1]] != null ? lockFirst[parts[0] + ':' + parts[1]] + 40 : Infinity);
  });

  // Lock trajectory: frame index per time; binary search the last frame <= t.
  function frameAt(tt) {
    var lo = 0, hi = R.frames.length - 1, best = R.frames[0];
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (R.frames[mid].t <= tt) { best = R.frames[mid]; lo = mid + 1; } else hi = mid - 1;
    }
    return best;
  }

  // Feed rows marked done, so scrubbing backwards rebuilds the list.
  var feedRows = [];
  function rebuildFeed() {
    feedRows = [];
    verdicts.forEach(function (v) {
      if (t != null && v.w.wt <= t) feedRows.push(v);
    });
    feedRows.reverse();
    var html = '';
    for (var i = 0; i < feedRows.length && i < 60; i++) {
      var w = feedRows[i].w;
      html += '<div class="row"><span class="t">' + (w.wt / 1000).toFixed(1) + 's</span>'
        + '<span class="key">' + w.key + '</span>'
        + '<span class="st-' + w.st + '">' + w.st + '</span>'
        + (typeof w.wu === 'number' ? '<span class="wu">wu=' + w.wu + '</span>' : '')
        + '</div>';
    }
    feedEl.innerHTML = html;
  }

  /* ---- painting ------------------------------------------------------ */
  var cmap = { WRONG: '#e0625a', CORRECT: '#2bb6a0', UNKNOWN: '#e09112', SKIPPED: '#847c6b' };

  function drawPage() {
    var page = R.pages[curPage];
    var img = page.imgEl || null;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (img) ctx.drawImage(img, 0, 0);
    else {
      // No bitmap supplied: render words as text, the LineText path.
      ctx.fillStyle = '#505050'; ctx.direction = 'rtl';
      ctx.font = '26px "Amiri","Scheherazade New",serif';
      page.textLines.forEach(function (tl) {
        ctx.fillText(tl.text, canvas.width - 30, tl.y);
      });
    }
    var f = frameAt(t);
    // Lock box: the locked ayah's words get the gold wash the reader gives
    // the standing ayah (RECITATION_AYAH), drawn even before verdicts land.
    var lockPrefix = (f.s != null ? f.s : '') + ':' + (f.lock != null ? f.lock : '');
    for (var key in page.boxes) {
      var st = null;
      var w = wordState[key];
      if (w && w.paintAt <= t) st = w.st;
      var inLock = key.indexOf(lockPrefix + ':') === 0;
      if (!st && !inLock) continue;
      page.boxes[key].forEach(function (b) {
        if (st) {
          ctx.globalAlpha = st === 'WRONG' ? 0.6 : 0.16 + 0.2 * (st === 'CORRECT' ? 0.4 : 0);
          ctx.fillStyle = cmap[st];
          ctx.fillRect(b[0], b[1], b[2] - b[0], b[3] - b[1]);
          ctx.globalAlpha = 1;
        }
        if (inLock) {
          ctx.strokeStyle = '#d9b36b'; ctx.lineWidth = 2;
          ctx.strokeRect(b[0] + 1, b[1] + 1, b[2] - b[0] - 2, b[3] - b[1] - 2);
        }
      });
    }
    // Clickable hit boxes live under the canvas as absolutely-positioned divs,
    // so a click knows its word key exactly as the reader does.
  }

  /* Status of every word at time t, painted via its own record row. */
  var wordState = {};
  R.words.forEach(function (w) { wordState[w.key] = w; });

  function updateLockHeader() {
    var f = frameAt(t);
    var cov = function (x) { return (typeof x === 'number' && x >= 0) ? (x * 100).toFixed(0) + '%' : '\u2014'; };
    lockEl.innerHTML = 'surah <b>' + (f.s || '\u2014') + '</b> &nbsp; lock <b>' + (f.lock || '\u2014') + '</b>'
      + ' &nbsp; here ' + cov(f.here) + ' &nbsp; next ' + cov(f.next)
      + ' &nbsp; syms ' + (f.syms != null ? f.syms : '\u2014');
  }

  var st = { i: 0 }; // slider index
  function setT(ms) {
    t = Math.max(0, Math.min(lastT, ms));
    slider.value = Math.round(t / lastT * 1000);
    tEl.textContent = (t / 1000).toFixed(1) + ' s';
    var f = frameAt(t);
    var lockPage = pageOfAyah(f.s, f.lock);
    if (lockPage && lockPage !== curPage) curPage = lockPage;
    drawPage();
    updateLockHeader();
    rebuildFeed();
  }

  // pageOfAyah: from the mushaf page word keys.
  var ayahPages = {};
  Object.keys(R.pages).forEach(function (pn) {
    var boxes = R.pages[pn].boxes;
    Object.keys(boxes).forEach(function (k) {
      var p = k.split(':'); var ayah = p[0] + ':' + p[1];
      if (!(ayah in ayahPages)) ayahPages[ayah] = Number(pn);
    });
  });
  function pageOfAyah(s, a) { return ayahPages[s + ':' + a]; }

  /* ---- playback ------------------------------------------------------ */
  var raf = null; var lastRaf = 0;
  // Real-time multiple: 1× walks the recorded polls at the pace they ran.
  function tick(now) {
    if (!playing) return;
    if (lastRaf) setT(t + (now - lastRaf) * speed);
    lastRaf = now;
    if (t < lastT) raf = requestAnimationFrame(tick); else stop();
  }
  function play() {
    if (playing) return;
    if (t >= lastT) setT(0);
    playing = true; playBtn.textContent = 'Pause'; lastRaf = 0;
    raf = requestAnimationFrame(tick);
  }
  function stop() { playing = false; playBtn.textContent = 'Play'; lastRaf = 0; if (raf) cancelAnimationFrame(raf); }
  playBtn.onclick = function () { playing ? stop() : play(); };

  slider.oninput = function () { setT(Number(slider.value) / 1000 * lastT); };
  document.onkeydown = function (e) {
    if (e.code === 'Space') { e.preventDefault(); playing ? stop() : play(); }
    else if (e.key === 'ArrowRight') setT(t + 5000);
    else if (e.key === 'ArrowLeft') setT(t - 5000);
    else if (e.key === 'ArrowUp') setT(t + 1000);
    else if (e.key === 'ArrowDown') setT(t - 1000);
    else if (e.key.toLowerCase() === 'w') {
      var cand = verdicts.filter(function (v) { return v.w.wt > t; });
      if (e.shiftKey) {
        var before = verdicts.filter(function (v) { return v.w.wt < t; });
        if (before.length) setT(before[before.length - 1].w.wt);
      } else if (cand.length) setT(cand[0].w.wt);
    }
  };
  nextBtn.onclick = function () {
    var v = verdicts.find(function (v) { return v.w.wt > t; });
    if (v) setT(v.w.wt);
  };
  prevBtn.onclick = function () {
    var before = verdicts.filter(function (v) { return v.w.wt < t; });
    if (before.length) setT(before[before.length - 1].w.wt);
  };

  /* ---- page switcher ------------------------------------------------- */
  var pageBar = document.createElement('div');
  pageBar.style.marginBottom = '6px';
  document.getElementById('lock').parentNode.insertBefore(pageBar, document.getElementById('lock'));
  pageNames.forEach(function (pn) {
    var b = document.createElement('button');
    b.textContent = 'page ' + pn;
    b.onclick = function () { curPage = pn; drawPage(); };
    pageBar.appendChild(b);
  });

  /* ---- click-to-cause ------------------------------------------------ */
  canvas.onmousemove = function (ev) {
    var r = canvas.getBoundingClientRect();
    var x = (ev.clientX - r.left) * (canvas.width / r.width);
    var y = (ev.clientY - r.top) * (canvas.height / r.height);
    var page = R.pages[curPage];
    var hit = null;
    for (var key in page.boxes) {
      for (var i = 0; i < page.boxes[key].length; i++) {
        var b = page.boxes[key][i];
        if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) { hit = key; break; }
      }
      if (hit) break;
    }
    canvas.style.cursor = hit ? 'pointer' : 'default';
  };
  canvas.onclick = function (ev) {
    var r = canvas.getBoundingClientRect();
    var x = (ev.clientX - r.left) * (canvas.width / r.width);
    var y = (ev.clientY - r.top) * (canvas.height / r.height);
    var page = R.pages[curPage];
    for (var key in page.boxes) {
      for (var i = 0; i < page.boxes[key].length; i++) {
        var b = page.boxes[key][i];
        if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) { showCause(key); return; }
      }
    }
  };

  function showCause(key) {
    var w = R.words.find(function (x) { return x.key === key; });
    var c = (R.causes || {})[key];
    var html = '<div>' + key + ' &nbsp; ' + (w ? w.st : 'no row') + '</div>';
    if (w && typeof w.wt === 'number') html += '<div class="window">decided at ' + (w.wt / 1000).toFixed(1) + ' s</div>';
    var advs = (R.advisories || []).filter(function (a) { return a.key === key; });
    if (advs.length) html += '<div class="window">notes: ' + advs.map(function (a) { return a.kind + (a.count > 1 ? '\u00d7' + a.count : ''); }).join(', ') + '</div>';
    if (!c) { html += '<div class="window">no reconstruction in this record for this word.</div>'; causeEl.innerHTML = html; return; }
    if (c.error) { html += '<div class="nr">NOT RECONSTRUCTABLE (' + c.error + ')</div>'; causeEl.innerHTML = html; return; }
    html += '<div class="window">judged window [' + c.lo + ':' + c.hi + ']</div>';
    html += '<table>';
    c.units.forEach(function (u) {
      html += '<tr><td>' + u[0] + '</td><td>' + (u[2] ? '<span class="charge">charged \u2190 ' + u[1] + '</span>'
        : (u[1] ? '<span class="match">' + u[1] + '</span>' : '<span class="window">(no symbol)</span>')) + '</td></tr>';
    });
    html += '</table>';
    causeEl.innerHTML = html;
  }

  /* ---- log book ------------------------------------------------------ */
  diagEl.textContent = (R.diag || []).join('\n');

  /* Pre-load page images, then start. */
  var pending = 0;
  pageNames.forEach(function (pn) {
    var page = R.pages[pn];
    if (!page.img) return;
    pending++;
    var el = new Image();
    el.onload = function () { page.imgEl = el; page.img = null; if (--pending === 0) setT(0); drawPage(); };
    el.src = page.img;
  });

  setT(0);
})();

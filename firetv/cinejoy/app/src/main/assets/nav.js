// Select mode for the kiosk: the D-pad moves a focus ring between the page's links, buttons, inputs and player
// iframes, like a TV app, and a full-screen video is driven directly (play/pause, seek). Injected once per document
// by MainActivity, which then calls __cjNav.move / ok / media and acts on the object they return:
//   {act: 'tap', x, y, w}   tap there (CSS px of the viewport, w = innerWidth)
//   {act: 'osd', osd}       show that text (player state)
//   {act: 'reveal', osd}    hover over the video so the site draws its player controls
//   {act: 'none'}           nothing for this key here (the caller falls back)
if (!window.__cjNav) window.__cjNav = (function () {
  var SEL = 'a[href],button,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],' +
    '[role=switch],input:not([type=hidden]),select,textarea,summary,iframe,[tabindex]:not([tabindex="-1"]),[onclick]';
  var OPP = { up: 'down', down: 'up', left: 'right', right: 'left' };
  var cur = null, ring = null, visible = false;
  var back = new WeakMap();      // element -> {dir: element to return to}, so Down then Up comes back
  var unreachable = new WeakSet();  // scrolled to once and still not on screen (slides moved off by a transform)

  function makeRing() {
    if (ring && ring.isConnected) return ring;
    ring = document.createElement('div');
    var s = ring.style;
    s.position = 'fixed'; s.pointerEvents = 'none'; s.zIndex = '2147483647'; s.boxSizing = 'border-box';
    s.border = '3px solid #95FF50'; s.borderRadius = '12px'; s.opacity = '0';
    s.boxShadow = '0 0 0 2px rgba(0,0,0,.55), 0 0 22px rgba(149,255,80,.55)';
    s.transition = 'left .12s ease, top .12s ease, width .12s ease, height .12s ease, opacity .12s ease';
    document.documentElement.appendChild(ring);
    return ring;
  }

  function place() {
    if (!ring) return;
    if (!visible || !cur || !cur.isConnected) { ring.style.opacity = '0'; return; }
    var r = cur.getBoundingClientRect(), pad = 4, s = ring.style;
    s.left = (r.left - pad) + 'px'; s.top = (r.top - pad) + 'px';
    s.width = (r.width + 2 * pad) + 'px'; s.height = (r.height + 2 * pad) + 'px';
    s.opacity = onScreen(r) ? '1' : '0';
  }
  var placing = false;
  function schedulePlace() {
    if (placing || !visible) return;
    placing = true;
    requestAnimationFrame(function () { placing = false; place(); });
  }
  addEventListener('scroll', schedulePlace, true);
  addEventListener('resize', schedulePlace);
  // When the player starts, the button that started it must not keep the focus (or OK would press it again).
  document.addEventListener('play', function (e) { if (e.target === bigVideo()) { cur = null; place(); } }, true);

  function onScreen(r) { return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; }

  // checkVisibility walks the ancestors too: a carousel slide faded to opacity 0 hides its buttons as well.
  function shown(el) {
    if (!el || !el.isConnected || el === ring || el.disabled) return false;
    return el.checkVisibility ? el.checkVisibility({ opacityProperty: true, visibilityProperty: true }) : true;
  }

  // What a tap at the element's centre would hit is the element (or inside it), not an overlay above it.
  function hittable(el, r) {
    var x = Math.min(Math.max((r.left + r.right) / 2, 1), innerWidth - 1);
    var y = Math.min(Math.max((r.top + r.bottom) / 2, 1), innerHeight - 1);
    var hit = document.elementFromPoint(x, y);
    return !hit || hit === el || el.contains(hit) || hit.contains(el);
  }

  function candidates() {
    var W = innerWidth, H = innerHeight, all = document.querySelectorAll(SEL), list = [], set = new Set();
    for (var i = 0; i < all.length; i++) {
      var el = all[i], r = el.getBoundingClientRect();
      if (r.width < 6 || r.height < 6 || unreachable.has(el)) continue;
      if (r.bottom < -1.5 * H || r.top > 2.5 * H || r.right < -1.5 * W || r.left > 2.5 * W) continue;
      // A clickable the size of the page (a hero banner's background link) is not a target; its buttons are.
      if (el.tagName !== 'IFRAME' && r.width * r.height > 0.35 * W * H) continue;
      if (!shown(el) || (onScreen(r) && !hittable(el, r))) continue;
      list.push({ el: el, r: r });
      set.add(el);
    }
    // A wrapper the size of a candidate inside it (a div around a link) goes; the inner one stays.
    var drop = new Set();
    for (var j = 0; j < list.length; j++) {
      var c = list[j];
      for (var a = c.el.parentElement, d = 0; a && d < 6; a = a.parentElement, d++) {
        if (!set.has(a)) continue;
        var ar = a.getBoundingClientRect();
        if (Math.abs(ar.width - c.r.width) < 4 && Math.abs(ar.height - c.r.height) < 4) drop.add(a);
      }
    }
    return list.filter(function (c) { return !drop.has(c.el); });
  }

  function gap(a1, a2, b1, b2) { return b2 < a1 ? a1 - b2 : (b1 > a2 ? b1 - a2 : 0); }

  // Distance to c going dir from r; -1 when c is not that way. Overlap with the current column (up/down) or row
  // (left/right) matters most, so the neighbour in line wins over a nearer item off to the side.
  function score(r, c, dir) {
    var rx = (r.left + r.right) / 2, ry = (r.top + r.bottom) / 2;
    var cx = (c.left + c.right) / 2, cy = (c.top + c.bottom) / 2;
    var tol = 0.5 * Math.min(r.height, c.height), tolx = 0.5 * Math.min(r.width, c.width);
    if (dir === 'down') {
      if (cy <= ry + 1 || c.top < r.bottom - tol) return -1;
      return Math.max(0, c.top - r.bottom) + 3 * gap(r.left, r.right, c.left, c.right) + 0.1 * Math.abs(cx - rx);
    }
    if (dir === 'up') {
      if (cy >= ry - 1 || c.bottom > r.top + tol) return -1;
      return Math.max(0, r.top - c.bottom) + 3 * gap(r.left, r.right, c.left, c.right) + 0.1 * Math.abs(cx - rx);
    }
    if (dir === 'right') {
      if (cx <= rx + 1 || c.left < r.right - tolx) return -1;
      return Math.max(0, c.left - r.right) + 6 * gap(r.top, r.bottom, c.top, c.bottom) + 0.3 * Math.abs(cy - ry);
    }
    if (cx >= rx - 1 || c.right > r.left + tolx) return -1;  // left
    return Math.max(0, r.left - c.right) + 6 * gap(r.top, r.bottom, c.top, c.bottom) + 0.3 * Math.abs(cy - ry);
  }

  // Focuses el, scrolling it into view at once (smooth scrolling is costly on a TV and makes the ring lag).
  // False, and el is skipped from now on, when it still is not on screen afterwards: moved off by a transform.
  function focus(el) {
    var r = el.getBoundingClientRect(), H = innerHeight;
    if (r.top < 0.15 * H || r.bottom > 0.9 * H) el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
    else if (r.left < 0 || r.right > innerWidth) el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    r = el.getBoundingClientRect();
    if (!onScreen(r) || !hittable(el, r)) { unreachable.add(el); return false; }
    cur = el;
    makeRing();
    place();
    return true;
  }

  function remember(from, to, dir) {
    var b = back.get(to) || {};
    b[OPP[dir]] = from;
    back.set(to, b);
  }

  // Where to start: a Play / Watch / Resume button on screen if there is one, else the nearest to (x, y).
  function start(x, y) {
    var list = candidates(), best = null, bestD = Infinity;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], on = onScreen(c.r);
      if (on && /^\s*(play|watch|resume|continue)\b/i.test(c.el.textContent || c.el.getAttribute('aria-label') || '')) return c.el;
      var d = Math.hypot(Math.max(c.r.left - x, 0, x - c.r.right), Math.max(c.r.top - y, 0, y - c.r.bottom)) + (on ? 0 : 1e5);
      if (d < bestD) { bestD = d; best = c.el; }
    }
    return best;
  }

  function live(el) { return el && el.isConnected && shown(el) && onScreen(el.getBoundingClientRect()); }

  // ---- full-screen video ----------------------------------------------------------------------

  function bigVideo() {
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect();
      // A muted looping trailer behind a hero banner is not the player.
      if (r.width * r.height > 0.5 * innerWidth * innerHeight && v.readyState > 0 && !(v.muted && v.loop)) return v;
    }
    return null;
  }
  function clock(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (sec < 10 ? '0' : '') + sec;
  }
  function status(v, prefix) {
    var t = clock(v.currentTime) + (isFinite(v.duration) ? ' / ' + clock(v.duration) : '');
    return (prefix || (v.paused ? '❚❚' : '▶')) + '   ' + t;
  }
  function toggle(v) {
    if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause();
    return { act: 'osd', osd: status(v, v.paused ? '❚❚' : '▶') };
  }
  function seek(v, by) {
    var end = isFinite(v.duration) ? v.duration - 1 : Infinity;
    v.currentTime = Math.max(0, Math.min(end, v.currentTime + by));
    return { act: 'osd', osd: status(v, (by < 0 ? '« ' : '» ') + Math.abs(by) + 's') };
  }
  // The player owns the keys while nothing on top of it (its own controls) has the focus.
  function playerKeys() {
    var v = bigVideo();
    return v && !live(cur) ? v : null;
  }

  return {
    move: function (dir, x, y) {
      visible = true;
      var v = playerKeys();
      if (v) {
        if (dir === 'left' || dir === 'right') return seek(v, dir === 'left' ? -10 : 10);
        var s = start(innerWidth / 2, innerHeight * 0.85);   // the player's controls sit at the bottom
        if (s && focus(s)) return { act: 'moved' };
        return { act: 'reveal', osd: status(v) };
      }
      var from = cur;
      if (!live(from)) {
        var s0 = start(x, y);
        if (s0 && focus(s0)) return { act: 'moved' };
        return { act: 'none' };
      }
      var b = back.get(from);
      if (b && b[dir] && live(b[dir]) && focus(b[dir])) return { act: 'moved' };
      var r = from.getBoundingClientRect(), list = candidates(), scored = [];
      for (var i = 0; i < list.length; i++) {
        if (list[i].el === from || from.contains(list[i].el)) continue;
        var sc = score(r, list[i].r, dir);
        if (sc >= 0) scored.push([sc, list[i].el]);
      }
      scored.sort(function (a, c) { return a[0] - c[0]; });
      for (var k = 0; k < scored.length && k < 6; k++) {
        if (focus(scored[k][1])) { remember(from, scored[k][1], dir); return { act: 'moved' }; }
      }
      if (bigVideo()) { cur = null; place(); return { act: 'moved' }; }   // off the controls: back to the player
      return { act: 'none' };
    },
    ok: function (x, y) {
      visible = true;
      var v = playerKeys();
      if (v) return toggle(v);
      if (!live(cur)) { var s = start(x, y); if (!s || !focus(s)) return { act: 'none' }; }
      var r = cur.getBoundingClientRect();
      var l = Math.max(r.left, 0), t = Math.max(r.top, 0), rr = Math.min(r.right, innerWidth), bb = Math.min(r.bottom, innerHeight);
      return { act: 'tap', x: (l + rr) / 2, y: (t + bb) / 2, w: innerWidth };
    },
    // 'toggle' or 'seek' (by seconds) for the remote's media keys; {act:'none'} when no video fills the screen.
    media: function (cmd, by) {
      var v = bigVideo();
      if (!v) return { act: 'none' };
      return cmd === 'seek' ? seek(v, by) : toggle(v);
    },
    show: function () { visible = true; makeRing(); place(); },
    hide: function () { visible = false; place(); },
    // A new page (SvelteKit navigates without reloading): start from nothing, the next arrow picks afresh.
    reset: function () { cur = null; place(); },
    // Is a video playing? For keeping the screen on.
    playing: function () {
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) if (!vs[i].paused && !vs[i].ended && vs[i].readyState > 2) return true;
      return false;
    }
  };
})();

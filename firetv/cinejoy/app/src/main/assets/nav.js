// Select mode for the kiosk: the D-pad moves a focus ring between the page's links, buttons, inputs and player
// iframes, like a TV app. Injected by MainActivity before every call (it is idempotent), so it survives full page
// loads; SvelteKit's in-page navigations keep it. Coordinates are CSS px of the top-level viewport.
if (!window.__cjNav) window.__cjNav = (function () {
  var SEL = 'a[href],button,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],' +
    '[role=switch],input:not([type=hidden]),select,textarea,summary,iframe,[tabindex]:not([tabindex="-1"]),[onclick]';
  var LIME = '#95FF50';
  var cur = null, ring = null, visible = false, until = 0;

  function makeRing() {
    if (ring && ring.isConnected) return ring;
    ring = document.createElement('div');
    var s = ring.style;
    s.position = 'fixed'; s.pointerEvents = 'none'; s.zIndex = '2147483647'; s.boxSizing = 'border-box';
    s.border = '3px solid ' + LIME; s.borderRadius = '12px'; s.opacity = '0';
    s.boxShadow = '0 0 0 2px rgba(0,0,0,.55), 0 0 22px rgba(149,255,80,.55)';
    s.transition = 'left .14s ease, top .14s ease, width .14s ease, height .14s ease, opacity .15s ease';
    document.documentElement.appendChild(ring);
    return ring;
  }

  function usable(el, r) {
    if (r.width < 6 || r.height < 6) return false;
    if (el === ring || el.disabled) return false;
    // A clickable the size of the page (a hero banner's background link) is not a target; its buttons are.
    // Player iframes are kept however big they are.
    if (el.tagName !== 'IFRAME' && r.width * r.height > 0.35 * innerWidth * innerHeight) return false;
    var st = getComputedStyle(el);
    if (st.visibility === 'hidden' || st.display === 'none' || parseFloat(st.opacity) === 0) return false;
    return !el.closest('[aria-hidden="true"],[inert]');
  }

  // Candidates near the screen (a few screens around it, so the next row or carousel item off-screen counts).
  // A wrapper exactly the size of a candidate inside it (a div around a link) is dropped for the inner one.
  function candidates() {
    var W = innerWidth, H = innerHeight, all = document.querySelectorAll(SEL), out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i], r = el.getBoundingClientRect();
      if (r.bottom < -2 * H || r.top > 3 * H || r.right < -2 * W || r.left > 3 * W) continue;
      if (!usable(el, r)) continue;
      out.push({ el: el, r: r });
    }
    return out.filter(function (c) {
      for (var j = 0; j < out.length; j++) {
        var d = out[j];
        if (d.el !== c.el && c.el.contains(d.el) && Math.abs(d.r.width - c.r.width) < 4
            && Math.abs(d.r.height - c.r.height) < 4) return false;
      }
      return true;
    });
  }

  function gap(a1, a2, b1, b2) { return b2 < a1 ? a1 - b2 : (b1 > a2 ? b1 - a2 : 0); }

  // Distance to c going dir from r; -1 when c is not that way. Rows matter most for left/right, columns
  // (overlap) for up/down, so a slightly nearer item in another row never wins over the neighbour.
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

  function hoverEvents(el, enter) {
    if (!el || !el.isConnected) return;
    var o = { bubbles: true, cancelable: true, view: window };
    try {
      el.dispatchEvent(new PointerEvent(enter ? 'pointerover' : 'pointerout', o));
      el.dispatchEvent(new MouseEvent(enter ? 'mouseover' : 'mouseout', o));
      el.dispatchEvent(new MouseEvent(enter ? 'mouseenter' : 'mouseleave', { bubbles: false, view: window }));
    } catch (e) {}
  }

  function place() {
    if (!ring) return;
    if (!visible || !cur || !cur.isConnected) { ring.style.opacity = '0'; return; }
    var r = cur.getBoundingClientRect(), pad = 4, s = ring.style;
    s.left = (r.left - pad) + 'px'; s.top = (r.top - pad) + 'px';
    s.width = (r.width + 2 * pad) + 'px'; s.height = (r.height + 2 * pad) + 'px';
    s.opacity = (r.bottom < 0 || r.top > innerHeight) ? '0' : '1';
  }

  // Follow the element while smooth scrolling settles, then only on scroll and resize.
  function follow(ms) {
    until = Math.max(until, Date.now() + ms);
    (function tick() { place(); if (Date.now() < until) requestAnimationFrame(tick); })();
  }
  addEventListener('scroll', function () { if (visible) follow(150); }, true);
  addEventListener('resize', function () { if (visible) follow(150); });

  function setCur(el) {
    if (el !== cur) { hoverEvents(cur, false); cur = el; hoverEvents(cur, true); }
    makeRing();
    var r = el.getBoundingClientRect(), H = innerHeight, W = innerWidth;
    if (r.top < 0.18 * H || r.bottom > 0.9 * H) {
      el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    } else if (r.left < 0 || r.right > W) {
      el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    }
    follow(700);
  }

  // Where to start: the candidate nearest the given point (the cursor's spot), preferring ones on screen.
  function start(x, y) {
    var best = null, bestD = Infinity, list = candidates();
    for (var i = 0; i < list.length; i++) {
      var r = list[i].r, on = r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
      var d = Math.hypot(Math.max(r.left - x, 0, x - r.right), Math.max(r.top - y, 0, y - r.bottom)) + (on ? 0 : 1e5);
      if (d < bestD) { bestD = d; best = list[i].el; }
    }
    return best;
  }

  function pageScroll(dir) {
    var el = document.scrollingElement || document.documentElement, dy = 0.6 * innerHeight;
    if (dir === 'down') el.scrollBy({ top: dy, behavior: 'smooth' });
    else if (dir === 'up') el.scrollBy({ top: -dy, behavior: 'smooth' });
  }

  return {
    // Moves focus one step; returns 'moved', 'started' or 'scrolled'.
    move: function (dir, x, y) {
      visible = true;
      if (!cur || !cur.isConnected || !usable(cur, cur.getBoundingClientRect())) {
        var s = start(x, y);
        if (s) { setCur(s); return 'started'; }
        pageScroll(dir);
        return 'scrolled';
      }
      var r = cur.getBoundingClientRect(), best = null, bestS = Infinity, list = candidates();
      for (var i = 0; i < list.length; i++) {
        if (list[i].el === cur || cur.contains(list[i].el)) continue;
        var sc = score(r, list[i].r, dir);
        if (sc >= 0 && sc < bestS) { bestS = sc; best = list[i].el; }
      }
      if (best) { setCur(best); return 'moved'; }
      pageScroll(dir);
      follow(700);
      return 'scrolled';
    },
    // Centre of the focused element's visible part, for a native tap: [x, y, innerWidth]; null when none.
    target: function (x, y) {
      visible = true;
      if (!cur || !cur.isConnected) { var s = start(x, y); if (!s) return null; setCur(s); }
      var r = cur.getBoundingClientRect();
      var l = Math.max(r.left, 0), t = Math.max(r.top, 0), rr = Math.min(r.right, innerWidth), b = Math.min(r.bottom, innerHeight);
      if (rr <= l || b <= t) return null;
      return [(l + rr) / 2, (t + b) / 2, innerWidth];
    },
    show: function () { visible = true; makeRing(); follow(200); },
    hide: function () { visible = false; hoverEvents(cur, false); place(); }
  };
})();

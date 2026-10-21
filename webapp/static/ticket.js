/* Shared ticket helpers: barcode rendering, count-up timer, spin, wake lock and the
   offline copy of the tickets (localStorage). Used by ticket.html and offline.html. */
(function (w) {
  "use strict";
  var STORE = "vrnOffline.v1";
  var OPTS = "barcodeOpts";

  function looksB64(code) {
    return /^[A-Za-z0-9+/]+={0,2}$/.test(code) && code.length % 4 === 0;
  }

  function barcodeOpts(code) {
    var opts = { sym: "azteccode", b64: looksB64(code) };
    try { var saved = JSON.parse(localStorage.getItem(OPTS) || "null"); if (saved) opts = saved; } catch (e) {}
    return opts;
  }

  function saveBarcodeOpts(opts) {
    try { localStorage.setItem(OPTS, JSON.stringify(opts)); } catch (e) {}
  }

  /* Draw `code` into the canvas with id `canvasId`; errors go to `errEl`. */
  function drawBarcode(canvasId, code, opts, errEl) {
    var text = code, parse = false;
    if (opts.b64) {
      // Binary payloads -> ^NNN escapes so the bytes survive.
      text = Array.prototype.map.call(atob(code), function (c) {
        return "^" + ("00" + c.charCodeAt(0)).slice(-3);
      }).join("");
      parse = true;
    }
    try {
      w.bwipjs.toCanvas(canvasId, { bcid: opts.sym, text: text, parse: parse, scale: 4 });
      if (errEl) errEl.textContent = "";
    } catch (e) {
      if (errEl) errEl.textContent = "Darstellung fehlgeschlagen: " + e;
    }
  }

  /* Count-up timer (as TicketDetailCountUpView). */
  // Slide-up card (.sheet-wrap > .sheet): closes by its button, a tap outside, Escape, or a
  // swipe down (the card follows the finger; a quarter of its height closes it).
  function sheet(wrap) {
    if (!wrap) return;
    var card = wrap.querySelector(".sheet"), y0 = null;
    function close() { wrap.hidden = true; card.style.transform = ""; }
    wrap.addEventListener("click", function (e) { if (e.target === wrap) close(); });
    wrap.querySelector(".sheet-close").addEventListener("click", close);
    document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !wrap.hidden) close(); });
    card.addEventListener("pointerdown", function (e) {
      if (e.target.closest("button")) return;
      y0 = e.clientY; card.setPointerCapture(e.pointerId); card.style.transition = "none";
    });
    card.addEventListener("pointermove", function (e) {
      if (y0 !== null) card.style.transform = "translateY(" + Math.max(0, e.clientY - y0) + "px)";
    });
    function end(e) {
      if (y0 === null) return;
      var d = Math.max(0, e.clientY - y0); y0 = null;
      card.style.transition = "transform .15s ease-out";
      if (d > card.offsetHeight / 4) close(); else card.style.transform = "";
    }
    card.addEventListener("pointerup", end);
    card.addEventListener("pointercancel", end);
  }

  function countUp(el) {
    if (!el) return;
    var t0 = Date.now();
    setInterval(function () {
      var s = Math.floor((Date.now() - t0) / 1000);
      el.textContent = ("0" + Math.floor(s / 60)).slice(-2) + ":" + ("0" + s % 60).slice(-2);
    }, 1000);
  }

  /* Tap spins the code card 360° clockwise; the next tap spins it counter-clockwise. */
  function spin(box) {
    if (!box) return;
    function toggle() { box.classList.toggle("turned"); }
    box.addEventListener("click", toggle);
    box.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
    });
  }

  /* Keep the screen on while a ticket is shown (where supported). */
  function wake() {
    if (navigator.wakeLock) navigator.wakeLock.request("screen").catch(function () {});
  }

  /* ---- Offline copy ---------------------------------------------------- */
  function loadOffline() {
    try { return JSON.parse(localStorage.getItem(STORE) || "null"); } catch (e) { return null; }
  }

  /* Merge a server snapshot: keep QR codes we already have for tickets whose new
     entry lacks one (e.g. a transient QRCode error). */
  function saveOffline(snap) {
    if (!snap || !snap.tickets) return;
    var old = loadOffline();
    if (old && old.user && snap.user && old.user.sub === snap.user.sub) {
      var byId = {};
      (old.tickets || []).forEach(function (t) { byId[t.id] = t; });
      snap.tickets.forEach(function (t) {
        var prev = byId[t.id];
        if (!t.qr && prev && prev.qr) { t.qr = prev.qr; t.kind = prev.kind; }
      });
    }
    try { localStorage.setItem(STORE, JSON.stringify(snap)); } catch (e) {}
  }

  /* Fetch the offline copy in the background (after the page has rendered). */
  function refreshOffline(url) {
    if (!w.fetch) return;
    w.fetch(url, { credentials: "same-origin", cache: "no-store" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (snap) { if (snap && snap.tickets) saveOffline(snap); })
      .catch(function () {});
  }

  function clearOffline() {
    try { localStorage.removeItem(STORE); } catch (e) {}
  }

  w.VRN = {
    barcodeOpts: barcodeOpts, saveBarcodeOpts: saveBarcodeOpts, drawBarcode: drawBarcode,
    countUp: countUp, spin: spin, wake: wake, sheet: sheet,
    loadOffline: loadOffline, saveOffline: saveOffline, refreshOffline: refreshOffline, clearOffline: clearOffline,
  };
})(window);

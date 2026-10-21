/* Bottom sheet over the map (Fahrplan): drag it between three positions.
 *   peek  only the part named by data-peek is visible (the search form, a trip summary)
 *   half  about half the screen
 *   full  the whole area between app bar and tab bar; the content scrolls
 * Below "full" the whole sheet is a drag handle (taps on rows still work); in "full" only
 * the grab bar is, so the content can scroll. Fires "sheetchange" with the visible height.
 */
(function (w) {
  "use strict";
  var el = document.getElementById("sheet");
  if (!el) return;
  var grab = el.querySelector(".bsheet-grab");
  var body = el.querySelector(".bsheet-body");
  var peekEl = el.dataset.peek ? el.querySelector(el.dataset.peek) : null;
  var ORDER = ["full", "half", "peek"];
  var state = el.dataset.snap || "peek";
  var y = 0;  // current translateY in px (0 = full)

  function snaps() {
    var h = el.offsetHeight;
    var peek = h - 90;
    if (peekEl) {
      var visible = peekEl.getBoundingClientRect().bottom - el.getBoundingClientRect().top + body.scrollTop + 14;
      peek = h - Math.max(70, Math.min(visible, h));
    }
    var half = Math.round(h * 0.35);
    return { full: 0, half: Math.min(half, peek), peek: Math.max(0, peek) };
  }

  function move(px, animate) {
    y = px;
    el.classList.toggle("dragging", !animate);
    el.style.transform = "translateY(" + px + "px)";
  }

  function set(name, animate) {
    state = name;
    var s = snaps();
    move(s[name], animate !== false);
    el.dataset.state = name;
    if (name !== "full") body.scrollTop = 0;
    // Let the transition finish before others measure the visible part.
    setTimeout(announce, animate === false ? 0 : 260);
  }

  function visible() { return Math.max(0, el.offsetHeight - y); }

  function announce() {
    el.dispatchEvent(new CustomEvent("sheetchange", { bubbles: true, detail: { state: state, visible: visible() } }));
  }

  // ---- Dragging -------------------------------------------------------------------
  var drag = null;
  var NO_DRAG = "input, textarea, select, .suggest, .leaflet-container";

  el.addEventListener("pointerdown", function (e) {
    if (e.button > 0 || (e.target.closest && e.target.closest(NO_DRAG))) return;
    if (state === "full" && !grab.contains(e.target)) return;  // content scrolls
    drag = { id: e.pointerId, startY: e.clientY, startPos: y, moved: false, samples: [[e.timeStamp, e.clientY]] };
    if (e.pointerType === "mouse") e.preventDefault();  // no text selection while dragging
  });

  // On the window: a mouse (unlike a finger) isn't held to the sheet once it moves off it.
  w.addEventListener("pointermove", function (e) {
    if (!drag || e.pointerId !== drag.id) return;
    var dy = e.clientY - drag.startY;
    if (!drag.moved) {
      if (Math.abs(dy) < 6) return;
      drag.moved = true;
    }
    var s = snaps();
    var pos = drag.startPos + dy;
    if (pos < 0) pos = pos / 4;  // a little resistance past the top
    move(Math.min(pos, s.peek + 40), false);
    drag.samples.push([e.timeStamp, e.clientY]);
    if (drag.samples.length > 5) drag.samples.shift();
  });

  function end(e) {
    if (!drag || e.pointerId !== drag.id) return;
    var d = drag;
    drag = null;
    if (!d.moved) return;
    // A flick goes one step in its direction; otherwise snap to the nearest position.
    var first = d.samples[0], last = d.samples[d.samples.length - 1];
    var v = (last[1] - first[1]) / Math.max(1, last[0] - first[0]);  // px/ms, + = down
    var s = snaps();
    var target;
    if (v < -0.4) {        // up: the nearest position above (ORDER runs top to bottom)
      target = "full";
      for (var i = ORDER.length - 1; i >= 0; i--) { if (s[ORDER[i]] < y - 4) { target = ORDER[i]; break; } }
    } else if (v > 0.4) {  // down: the nearest position below
      target = "peek";
      for (var k = 0; k < ORDER.length; k++) { if (s[ORDER[k]] > y + 4) { target = ORDER[k]; break; } }
    } else {
      target = ORDER.reduce(function (a, b) { return Math.abs(s[b] - y) < Math.abs(s[a] - y) ? b : a; });
    }
    set(target);
    // Swallow the click that ends a drag (it would open a result row); it follows pointerup at once.
    el.addEventListener("click", swallow, true);
    setTimeout(function () { el.removeEventListener("click", swallow, true); }, 80);
  }

  function swallow(ev) { ev.preventDefault(); ev.stopPropagation(); }
  w.addEventListener("pointerup", end);
  w.addEventListener("pointercancel", end);

  // Tap on the grab bar: up one step, from full back to half.
  grab.addEventListener("click", function () {
    set(state === "peek" ? "half" : state === "half" ? "full" : "half");
  });

  w.addEventListener("resize", function () { set(state, false); });

  set(state, false);
  el.classList.add("ready");

  w.BSheet = {
    set: set,
    get state() { return state; },
    visible: visible,
  };
})(window);

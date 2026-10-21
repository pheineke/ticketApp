/* Fahrplan map (OpenStreetMap via Leaflet), filling the screen behind the bottom sheet.
 * - #map-data (detail page): one line per leg in its mode's colour, footpaths dotted.
 * - #map-pins (search page): origin/destination pins.
 * - data-pick: long-press (or right-click) a point -> "mappick" event with lat/lon.
 * Views are fitted into the part of the map the sheet leaves free.
 */
(function (w) {
  "use strict";
  var el = document.getElementById("map");
  if (!el || !w.L) return;

  // Keep in sync with the .m-<mode> colours in style.css.
  var COLORS = {
    train: "#3d3d3d", sbahn: "#00965e", ubahn: "#0065ae", tram: "#b5007a", bus: "#0072bc",
    ship: "#0094c6", taxi: "#e0a800", bike: "#5d9b2c", walk: "#8c8c8c", other: "#6b6b6b"
  };
  var VIEW_KEY = "vrn-fahrplan-view";

  var map = L.map(el, { zoomControl: false, attributionControl: false });
  L.control.zoom({ position: "topright" }).addTo(map);
  // Top left: the bottom of the map is under the sheet, and the credit must stay visible.
  L.control.attribution({ position: "topleft", prefix: false }).addTo(map);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    // The app sends no Referer by default; OSM's tile policy asks for one.
    referrerPolicy: "strict-origin-when-cross-origin",
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>-Mitwirkende'
  }).addTo(map);

  function sheetHeight() { return w.BSheet ? w.BSheet.visible() : 0; }

  /* Fit bounds into the area above the sheet. */
  function fit(bounds, maxZoom) {
    if (!bounds.isValid()) return;
    map.fitBounds(bounds, {
      paddingTopLeft: [28, 48],
      paddingBottomRight: [28, Math.min(sheetHeight(), el.offsetHeight * 0.7) + 28],
      maxZoom: maxZoom || 16,
    });
  }

  function pinIcon(kind) {
    return L.divIcon({ className: "mpin " + kind, iconSize: [20, 20], iconAnchor: [10, 10] });
  }

  // ---- Route (detail page) ------------------------------------------------------------
  var routeBounds = null;
  var dataEl = document.getElementById("map-data");
  if (dataEl) {
    var legs = JSON.parse(dataEl.textContent);
    routeBounds = L.latLngBounds([]);
    legs.forEach(function (leg) {
      if (!leg.coords || leg.coords.length < 2) return;
      var color = COLORS[leg.mode] || COLORS.other;
      var walk = leg.mode === "walk";
      // White casing under the line keeps it readable on busy map areas.
      if (!walk) L.polyline(leg.coords, { color: "#fff", weight: 9, opacity: 0.9 }).addTo(map);
      L.polyline(leg.coords, { color: color, weight: walk ? 4 : 5, opacity: 0.95, dashArray: walk ? "1 8" : null, lineCap: "round" })
        .bindTooltip(walk ? "Fußweg" : (leg.line || "") + " " + leg.from + " → " + leg.to, { sticky: true })
        .addTo(map);
      leg.coords.forEach(function (c) { routeBounds.extend(c); });
      if (walk) return;
      (leg.stops || []).forEach(function (s) {
        L.circleMarker(s.coord, { radius: 3, color: color, weight: 2, fillColor: "#fff", fillOpacity: 1 })
          .bindTooltip(s.name).addTo(map);
      });
      [[leg.start, leg.from], [leg.end, leg.to]].forEach(function (p) {
        if (p[0]) L.circleMarker(p[0], { radius: 6, color: color, weight: 3, fillColor: "#fff", fillOpacity: 1 })
          .bindTooltip(p[1]).addTo(map);
      });
    });
    var first = legs[0], last = legs[legs.length - 1];
    if (first && (first.coords[0] || first.start)) L.marker(first.coords[0] || first.start, { icon: pinIcon("from"), keyboard: false }).bindTooltip(first.from).addTo(map);
    if (last && (last.coords[last.coords.length - 1] || last.end)) L.marker(last.coords[last.coords.length - 1] || last.end, { icon: pinIcon("to"), keyboard: false }).bindTooltip(last.to).addTo(map);
  }

  // ---- Origin / destination pins (search page) ---------------------------------------
  var pins = {};

  function setPin(kind, coord, label) {
    if (pins[kind]) { map.removeLayer(pins[kind]); delete pins[kind]; }
    if (!coord) return;
    pins[kind] = L.marker(coord, { icon: pinIcon(kind), keyboard: false, title: label || "" }).addTo(map);
    if (label) pins[kind].bindTooltip(label);
  }

  function fitPins() {
    var pts = ["from", "to"].filter(function (k) { return pins[k]; }).map(function (k) { return pins[k].getLatLng(); });
    if (pts.length === 2) fit(L.latLngBounds(pts), 15);
    else if (pts.length === 1) fit(L.latLngBounds([pts[0], pts[0]]), 15);
  }

  var pinsEl = document.getElementById("map-pins");
  if (pinsEl) {
    var given = JSON.parse(pinsEl.textContent) || {};
    Object.keys(given).forEach(function (k) { setPin(k, given[k].coord, given[k].name); });
  }

  // ---- Initial view: the route, the pins, else where the map was last ----------------
  function initialView() {
    if (routeBounds && routeBounds.isValid()) return fit(routeBounds);
    if (pins.from || pins.to) return fitPins();
    var saved = null;
    try { saved = JSON.parse(localStorage.getItem(VIEW_KEY)); } catch (e) { /* none */ }
    if (saved && saved.c) map.setView(saved.c, saved.z);
    else map.setView([49.44, 8.2], 10);  // VRN area
  }
  initialView();
  if (!dataEl) {
    map.on("moveend", function () {
      try { localStorage.setItem(VIEW_KEY, JSON.stringify({ c: map.getCenter(), z: map.getZoom() })); } catch (e) { /* private mode */ }
    });
  }
  // The sheet settles after the map: refit once it has its final height.
  var settled = false;
  document.addEventListener("sheetchange", function () {
    map.invalidateSize();
    if (!settled) { settled = true; initialView(); }
  });

  // ---- Long-press to pick a point ------------------------------------------------------
  var pick = null;   // marker of the pressed point
  if (el.dataset.pick) {
    var press = null, lastFire = 0;
    var cancel = function () { if (press) { clearTimeout(press.timer); press = null; } };
    var fire = function (x, y) {
      var r = el.getBoundingClientRect();
      var ll = map.containerPointToLatLng(L.point(x - r.left, y - r.top));
      lastFire = Date.now();
      if (navigator.vibrate) navigator.vibrate(15);
      el.dispatchEvent(new CustomEvent("mappick", { bubbles: true, detail: { lat: ll.lat, lon: ll.lng } }));
    };
    el.addEventListener("pointerdown", function (e) {
      if (!e.isPrimary || (e.pointerType === "mouse" && e.button !== 0)) { cancel(); return; }
      if (e.target.closest(".leaflet-control")) return;
      var x = e.clientX, y = e.clientY;
      cancel();
      press = { x: x, y: y, timer: setTimeout(function () { press = null; fire(x, y); }, 550) };
    });
    el.addEventListener("pointermove", function (e) {
      if (press && Math.abs(e.clientX - press.x) + Math.abs(e.clientY - press.y) > 10) cancel();
    });
    ["pointerup", "pointercancel", "pointerleave"].forEach(function (t) { el.addEventListener(t, cancel); });
    map.on("movestart zoomstart", cancel);
    // Right-click on desktop; on Android a long-press also sends this (ignore the duplicate).
    el.addEventListener("contextmenu", function (e) {
      e.preventDefault();
      if (Date.now() - lastFire > 800) fire(e.clientX, e.clientY);
    });
  }

  function showPick(coord) {
    if (pick) map.removeLayer(pick);
    pick = L.marker(coord, { icon: L.divIcon({ className: "mpin pick", iconSize: [22, 22], iconAnchor: [11, 11] }), keyboard: false }).addTo(map);
    // Keep the point visible above the place card.
    var p = map.latLngToContainerPoint(coord);
    var free = el.offsetHeight - (document.getElementById("placecard") || { offsetHeight: 0 }).offsetHeight - 40;
    if (p.y > free) map.panBy([0, p.y - free / 2]);
  }

  function clearPick() { if (pick) { map.removeLayer(pick); pick = null; } }

  w.FPMap = { map: map, setPin: setPin, fitPins: fitPins, showPick: showPick, clearPick: clearPick };
})(window);

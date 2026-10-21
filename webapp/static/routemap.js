/* Route map for a connection: OpenStreetMap tiles via Leaflet, one line per leg in the
 * colour of its mode (footpaths dashed), stops as dots, start and destination marked. */
(function () {
  "use strict";
  var el = document.getElementById("map");
  var dataEl = document.getElementById("map-data");
  if (!el || !dataEl || !window.L) return;
  var legs = JSON.parse(dataEl.textContent);

  // Keep in sync with the .m-<mode> colours in style.css.
  var COLORS = {
    train: "#3d3d3d", sbahn: "#00965e", ubahn: "#0065ae", tram: "#b5007a", bus: "#0072bc",
    ship: "#0094c6", taxi: "#e0a800", bike: "#5d9b2c", walk: "#8c8c8c", other: "#6b6b6b"
  };

  var map = L.map(el, { zoomControl: true, attributionControl: true, tap: true });
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    // The app sends no Referer by default; OSM's tile policy asks for one.
    referrerPolicy: "strict-origin-when-cross-origin",
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>-Mitwirkende'
  }).addTo(map);

  var bounds = L.latLngBounds([]);
  legs.forEach(function (leg) {
    if (!leg.coords || leg.coords.length < 2) return;
    var color = COLORS[leg.mode] || COLORS.other;
    var walk = leg.mode === "walk";
    // White casing under the line keeps it readable on busy map areas.
    if (!walk) L.polyline(leg.coords, { color: "#fff", weight: 8, opacity: 0.9 }).addTo(map);
    L.polyline(leg.coords, { color: color, weight: walk ? 4 : 5, opacity: 0.95, dashArray: walk ? "2 8" : null })
      .bindTooltip(walk ? "Fußweg" : (leg.line || "") + " " + leg.from + " → " + leg.to, { sticky: true })
      .addTo(map);
    leg.coords.forEach(function (c) { bounds.extend(c); });
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

  function pin(coord, cls, label) {
    if (!coord) return;
    L.marker(coord, { icon: L.divIcon({ className: "mpin " + cls, iconSize: [18, 18] }), title: label, keyboard: false })
      .bindTooltip(label).addTo(map);
  }
  var first = legs[0], last = legs[legs.length - 1];
  if (first) pin(first.coords[0] || first.start, "from", first.from);
  if (last) pin(last.coords[last.coords.length - 1] || last.end, "to", last.to);

  if (bounds.isValid()) map.fitBounds(bounds, { padding: [24, 24] });
  else map.setView([49.44, 8.0], 9);
})();

/* Fahrplan search: stop suggestions while typing, "Mein Standort", swap, "Jetzt", the last
 * searches (this browser only), pins on the map, and the long-press place card.
 * Without JS the form still works: the server takes the best match for a typed name. */
(function () {
  "use strict";
  var form = document.getElementById("search");
  if (!form) return;
  var RECENT_KEY = "vrn-fahrplan-recent";
  var RECENT_MAX = 6;
  var sheet = window.BSheet, fpmap = window.FPMap;

  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function setSheet(state) { if (sheet && sheet.state !== state) sheet.set(state); }

  // ---- Origin / destination fields ---------------------------------------------------
  var places = {};

  function setupPlace(prefix) {
    var text = form.elements[prefix + "_name"];
    var id = form.elements[prefix];
    var list = text.parentNode.querySelector(".suggest");
    var timer = null, seq = 0, items = [], active = -1;

    function close() { list.hidden = true; list.innerHTML = ""; items = []; active = -1; }

    function highlight(i) {
      active = i;
      Array.prototype.forEach.call(list.children, function (li, k) { li.classList.toggle("on", k === i); });
    }

    function show(results) {
      list.innerHTML = "";
      items = results;
      active = -1;
      results.forEach(function (r, i) {
        var li = document.createElement("li");
        li.setAttribute("role", "option");
        var name = document.createElement("span");
        name.className = "n";
        name.textContent = r.name;
        var kind = document.createElement("span");
        kind.className = "k";
        kind.textContent = r.kind;
        li.appendChild(name);
        li.appendChild(kind);
        // mousedown, not click: fires before the input's blur closes the list.
        li.addEventListener("mousedown", function (e) { e.preventDefault(); choose(prefix, r, true); });
        li.addEventListener("mouseenter", function () { highlight(i); });
        list.appendChild(li);
      });
      list.hidden = results.length === 0;
    }

    text.addEventListener("input", function () {
      id.value = "";  // typed text no longer matches the chosen place
      if (fpmap) fpmap.setPin(prefix, null);
      clearTimeout(timer);
      var q = text.value.trim();
      if (q.length < 2) { close(); return; }
      timer = setTimeout(function () {
        var mine = ++seq;
        fetch("/fahrplan/orte?q=" + encodeURIComponent(q), { credentials: "same-origin" })
          .then(function (r) { return r.ok ? r.json() : []; })
          .then(function (res) { if (mine === seq && document.activeElement === text) show(res); })
          .catch(function () {});
      }, 220);
    });
    text.addEventListener("keydown", function (e) {
      if (list.hidden) return;
      if (e.key === "ArrowDown") { e.preventDefault(); highlight(Math.min(active + 1, items.length - 1)); }
      else if (e.key === "ArrowUp") { e.preventDefault(); highlight(Math.max(active - 1, 0)); }
      else if (e.key === "Enter" && active >= 0) { e.preventDefault(); choose(prefix, items[active], true); }
      else if (e.key === "Escape") { close(); }
    });
    text.addEventListener("blur", function () { setTimeout(close, 100); });
    // Typing needs room for the suggestions: open the card fully.
    text.addEventListener("focus", function () {
      setSheet("full");
      if (text.value && !id.value) text.select();
    });
    return { text: text, id: id, close: close };
  }

  /* Use a place (suggestion, map point, stop) as origin ("from") or destination ("to"). */
  function choose(prefix, place, fromList) {
    var f = places[prefix], other = places[prefix === "from" ? "to" : "from"];
    f.text.value = place.name;
    f.id.value = place.id;
    f.close();
    if (fpmap) fpmap.setPin(prefix, place.coord || null, place.name);
    if (fromList && !other.id.value && !other.text.value.trim()) {
      other.text.focus();
      return;
    }
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    setSheet("peek");
    if (fpmap) setTimeout(fpmap.fitPins, 280);
  }

  places.from = setupPlace("from");
  places.to = setupPlace("to");
  var from = places.from, to = places.to;

  // ---- Buttons ------------------------------------------------------------------
  form.querySelector(".swap").addEventListener("click", function () {
    var t = from.text.value, i = from.id.value;
    from.text.value = to.text.value; from.id.value = to.id.value;
    to.text.value = t; to.id.value = i;
    if (fpmap) swapPins();
  });

  /* Swap the origin and destination pins (read back from the map's markers). */
  function swapPins() {
    var m = fpmap.map, found = {};
    m.eachLayer(function (layer) {
      var icon = layer.options && layer.options.icon;
      if (icon && /mpin (from|to)/.test(icon.options.className || "")) {
        found[icon.options.className.indexOf("from") > 0 ? "from" : "to"] = { coord: layer.getLatLng(), name: layer.options.title };
      }
    });
    fpmap.setPin("from", found.to ? found.to.coord : null, found.to && found.to.name);
    fpmap.setPin("to", found.from ? found.from.coord : null, found.from && found.from.name);
  }

  form.querySelector(".now").addEventListener("click", function () {
    var d = new Date();
    form.elements.date.value = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    form.elements.time.value = pad(d.getHours()) + ":" + pad(d.getMinutes());
    form.querySelector("input[name=arr][value='0']").checked = true;
  });

  var locate = form.querySelector(".locate");
  if (!("geolocation" in navigator)) {
    locate.hidden = true;
  } else {
    locate.addEventListener("click", function () {
      locate.classList.add("busy");
      navigator.geolocation.getCurrentPosition(function (pos) {
        locate.classList.remove("busy");
        var lat = pos.coords.latitude, lon = pos.coords.longitude;
        choose("from", { name: "Mein Standort", id: "coord:" + lat.toFixed(5) + "," + lon.toFixed(5), coord: [lat, lon] }, true);
      }, function () {
        locate.classList.remove("busy");
        alert("Standort nicht verfügbar. Bitte die Standortfreigabe für diese Seite erlauben.");
      }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
    });
  }

  form.addEventListener("submit", function (e) {
    if (!from.text.value.trim() || !to.text.value.trim()) {
      e.preventDefault();
      (from.text.value.trim() ? to.text : from.text).focus();
    }
  });

  // ---- Long-press place card ------------------------------------------------------------
  var card = document.getElementById("placecard");
  if (card && fpmap) {
    var cardName = card.querySelector(".pc-name");
    var cardKind = card.querySelector(".pc-kind");
    var near = card.querySelector(".pc-near");
    var nearList = card.querySelector(".pc-stops");
    var current = null, pressed = null, cardSeq = 0, pickedAt = 0;

    var select = function (place, kind) {
      current = place;
      cardName.textContent = place.name;
      cardKind.textContent = kind;
      fpmap.showPick(place.coord);
      Array.prototype.forEach.call(nearList.children, function (li) { li.classList.toggle("on", li.dataset.id === place.id); });
    };

    var closeCard = function () {
      card.hidden = true;
      fpmap.clearPick();
      current = null;
    };

    document.addEventListener("mappick", function (e) {
      var lat = e.detail.lat, lon = e.detail.lon, mine = ++cardSeq;
      pickedAt = Date.now();
      pressed = { id: "coord:" + lat.toFixed(5) + "," + lon.toFixed(5), name: "Punkt auf der Karte", coord: [lat, lon] };
      setSheet("peek");
      card.hidden = false;
      near.hidden = true;
      nearList.innerHTML = "";
      select(pressed, "Ort auf der Karte");
      cardName.textContent = "…";
      fetch("/fahrplan/hier?lat=" + lat.toFixed(6) + "&lon=" + lon.toFixed(6), { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (mine !== cardSeq || card.hidden) return;
          // Keep our coord id (exactly the pressed point), with the address as its name.
          pressed.name = (res.place && res.place.name) || pressed.name;
          if (current === pressed) cardName.textContent = pressed.name;
          (res.stops || []).forEach(function (s) {
            var li = document.createElement("li");
            li.dataset.id = s.id;
            var n = document.createElement("span");
            n.className = "n";
            n.textContent = s.name;
            var d = document.createElement("span");
            d.className = "d";
            d.textContent = s.distance != null ? s.distance + " m" : "";
            li.appendChild(n);
            li.appendChild(d);
            li.addEventListener("click", function () {
              if (current && current.id === s.id) select(pressed, "Ort auf der Karte");
              else select({ id: s.id, name: s.name, coord: s.coord }, "Haltestelle");
            });
            nearList.appendChild(li);
          });
          near.hidden = !nearList.children.length;
        })
        .catch(function () { if (mine === cardSeq) cardName.textContent = pressed.name; });
    });

    card.querySelector(".close").addEventListener("click", closeCard);
    // A tap elsewhere on the map closes the card (but not the release that ended the long-press).
    fpmap.map.on("click", function () { if (!card.hidden && Date.now() - pickedAt > 700) closeCard(); });

    Array.prototype.forEach.call(card.querySelectorAll("[data-use]"), function (btn) {
      btn.addEventListener("click", function () {
        if (!current) return;
        var use = btn.dataset.use, place = current;
        closeCard();
        choose(use, place, false);
        // Both ends set: search right away.
        if (from.id.value && to.id.value) form.submit();
      });
    });
  }

  // ---- Früher / Später: add the next trips to the list instead of reloading --------
  var list = document.querySelector(".jlist");
  function loadMore(link, atEnd) {
    if (link.classList.contains("busy")) return;
    link.classList.add("busy");
    fetch(link.href, { credentials: "same-origin" })
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, "text/html");
        var have = {};
        Array.prototype.forEach.call(list.querySelectorAll(".jrow"), function (r) { have[r.dataset.key] = true; });
        var fresh = Array.prototype.filter.call(doc.querySelectorAll(".jlist .jrow"), function (r) {
          return !have[r.dataset.key];
        });
        var scroller = list.closest(".bsheet-body") || document.scrollingElement;
        var before = scroller.scrollHeight;
        var anchor = atEnd ? null : list.firstChild;  // earlier trips go above the first row, in order
        fresh.forEach(function (r) {
          r.classList.add("fresh");
          list.insertBefore(document.importNode(r, true), anchor);
        });
        // Prepending: keep the rows the user was looking at in place.
        if (!atEnd) scroller.scrollTop += scroller.scrollHeight - before;
        var next = doc.getElementById(link.id);
        if (next) link.href = next.href;
        if (!fresh.length) link.classList.add("done");
      })
      .catch(function () { location.href = link.href; })  // fall back to the plain page
      .then(function () { link.classList.remove("busy"); });
  }
  [["earlier", false], ["later", true]].forEach(function (cfg) {
    var link = document.getElementById(cfg[0]);
    if (!link || !list) return;
    link.addEventListener("click", function (e) { e.preventDefault(); loadMore(link, cfg[1]); });
  });

  // ---- Recent searches (this browser only) -------------------------------------
  function loadRecent() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY)) || []; } catch (e) { return []; }
  }

  function saveRecent(list) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch (e) { /* private mode */ }
  }

  // A result page: remember this search (not points on the map, they're one-offs).
  if (document.querySelector(".jrow") && from.id.value && to.id.value &&
      from.id.value.indexOf("coord:") !== 0 && to.id.value.indexOf("coord:") !== 0) {
    var cur = { from: from.id.value, fromName: from.text.value, to: to.id.value, toName: to.text.value };
    saveRecent([cur].concat(loadRecent().filter(function (r) {
      return !(r.from === cur.from && r.to === cur.to);
    })).slice(0, RECENT_MAX));
  }

  var recent = document.getElementById("recent");
  var entries = loadRecent();
  if (recent && entries.length) {
    var box = recent.querySelector(".recent");
    entries.forEach(function (r) {
      var a = document.createElement("a");
      a.className = "rchip";
      a.href = "/fahrplan?" + new URLSearchParams({ from: r.from, from_name: r.fromName, to: r.to, to_name: r.toName });
      a.textContent = r.fromName + " → " + r.toName;
      box.appendChild(a);
    });
    var clear = document.createElement("button");
    clear.type = "button";
    clear.className = "btn small outline";
    clear.textContent = "Verlauf löschen";
    clear.addEventListener("click", function () { saveRecent([]); recent.hidden = true; });
    box.appendChild(clear);
    recent.hidden = false;
  }
})();

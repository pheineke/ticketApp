/* Fahrplan search form: stop suggestions while typing, "Mein Standort", swap, "Jetzt",
 * and the last searches (kept in this browser only). Without JS the form still works:
 * the server picks the best stop-finder match for a typed name. */
(function () {
  "use strict";
  var form = document.getElementById("search");
  if (!form) return;
  var RECENT_KEY = "vrn-fahrplan-recent";
  var RECENT_MAX = 6;

  function pad(n) { return (n < 10 ? "0" : "") + n; }

  // ---- Suggestions ----------------------------------------------------------
  function setupPlace(prefix) {
    var text = form.elements[prefix + "_name"];
    var id = form.elements[prefix];
    var list = text.parentNode.querySelector(".suggest");
    var timer = null, seq = 0, items = [], active = -1;

    function close() { list.hidden = true; list.innerHTML = ""; items = []; active = -1; }

    function choose(item) {
      text.value = item.name;
      id.value = item.id;
      close();
    }

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
        li.addEventListener("mousedown", function (e) { e.preventDefault(); choose(r); });
        li.addEventListener("mouseenter", function () { highlight(i); });
        list.appendChild(li);
      });
      list.hidden = results.length === 0;
    }

    text.addEventListener("input", function () {
      id.value = "";  // typed text no longer matches the chosen place
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
      else if (e.key === "Enter" && active >= 0) { e.preventDefault(); choose(items[active]); }
      else if (e.key === "Escape") { close(); }
    });
    text.addEventListener("blur", function () { setTimeout(close, 100); });
    text.addEventListener("focus", function () { if (text.value && !id.value) text.select(); });
    return { text: text, id: id };
  }

  var from = setupPlace("from");
  var to = setupPlace("to");

  // ---- Buttons ------------------------------------------------------------------
  form.querySelector(".swap").addEventListener("click", function () {
    var t = from.text.value, i = from.id.value;
    from.text.value = to.text.value; from.id.value = to.id.value;
    to.text.value = t; to.id.value = i;
  });

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
        from.text.value = "Mein Standort";
        from.id.value = "coord:" + pos.coords.latitude.toFixed(5) + "," + pos.coords.longitude.toFixed(5);
        to.text.focus();
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

  // ---- Recent searches (this browser only) -------------------------------------
  function loadRecent() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY)) || []; } catch (e) { return []; }
  }

  function saveRecent(list) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch (e) { /* private mode */ }
  }

  // A result page: remember this search (not "Mein Standort", that changes).
  if (document.querySelector(".jrow") && from.id.value && to.id.value && from.id.value.indexOf("coord:") !== 0) {
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

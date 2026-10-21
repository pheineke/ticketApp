"""
VRN journey planner: the Mentz EFA API behind https://www.vrn.de/mng/ ("mobile navigator").

Reverse-engineered from the mng web app (scripts/app-*.js, module mng-comm.itd.xmljson):
the API base is https://www.vrn.de/mngvrn and every endpoint answers JSON with
outputFormat=rapidJSON. No key, cookie or special client is needed, but there are no
CORS headers either, so the browser goes through our server.

  XML_STOPFINDER_REQUEST  name_sf=<text>, type_sf=any
      -> {locations: [{id, name, disassembledName, type (stop|poi|street|address|…),
                       coord: [lat, lon], parent: {name}, matchQuality, isBest, productClasses}]}
  XML_TRIP_REQUEST2       name_origin / name_destination = an id from the stop finder
                          (global stop id "de:07312:91310", DIVA "6091310", poiID…) with
                          type_*=any, or "<lon>:<lat>:WGS84[dd.ddddd]" with type_*=coord;
                          itdDate=YYYYMMDD, itdTime=HHMM (local time),
                          itdTripDateTimeDepArr=dep|arr
      -> {journeys: [{interchanges, legs: [...], fare: {tickets: [...]}}]}

  A leg: origin/destination (name, coord, departure/arrivalTime{Planned,Estimated} in UTC,
  properties.platformName), transportation (product.class, disassembledName = line,
  destination.name = direction), stopSequence (intermediate stops), coords (the route as
  [lat, lon], with itPathListActive=1), infos (disruption notes), distance (footpaths).

Free text as name_origin often fails with "multiple matches", so origins and destinations
are always resolved through the stop finder first.
"""
from __future__ import annotations

import html
import re
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

BASE = "https://www.vrn.de/mngvrn"
COORD_FMT = "WGS84[dd.ddddd]"
TZ = ZoneInfo("Europe/Berlin")

# EFA product classes -> display mode (colour and icon in the UI).
MODES = {
    0: "train", 13: "train", 14: "train", 15: "train", 16: "train",
    1: "sbahn",
    2: "ubahn",
    3: "tram", 4: "tram",
    5: "bus", 6: "bus", 7: "bus", 10: "bus", 17: "bus", 19: "bus",
    8: "other", 9: "ship", 11: "other", 12: "other",
    99: "walk", 100: "walk",
    105: "taxi", 107: "bike",
}
LONG_DISTANCE = {"ICE", "IC", "EC", "ECE", "TGV", "RJ", "RJX", "NJ", "EN", "FLX", "ICD"}
# Vehicle icon per mode (static/icons/mode_<icon>.svg).
ICONS = {"bus": "bus", "taxi": "bus", "tram": "tram", "walk": "walk"}
MODE_LABELS = {"train": "Zug", "sbahn": "S-Bahn", "ubahn": "U-Bahn", "tram": "Straßenbahn", "bus": "Bus",
               "ship": "Schiff", "walk": "Fußweg", "taxi": "Taxi", "bike": "Fahrrad", "other": "Sonstiges"}
LOCATION_TYPES = {"stop": "Haltestelle", "poi": "Ort", "street": "Straße", "address": "Adresse",
                  "singlehouse": "Adresse", "suburb": "Ort", "locality": "Ort"}


class EfaError(Exception):
    pass


def coord_id(lat: float, lon: float) -> str:
    """The id we use for a coordinate (e.g. the phone's location)."""
    return f"coord:{lat:.5f},{lon:.5f}"


def _place_params(prefix: str, place_id: str) -> dict:
    if place_id.startswith("coord:"):
        lat, lon = (float(x) for x in place_id[6:].split(","))
        return {f"name_{prefix}": f"{lon:.5f}:{lat:.5f}:{COORD_FMT}", f"type_{prefix}": "coord"}
    return {f"name_{prefix}": place_id, f"type_{prefix}": "any"}


def _call(get, endpoint: str, params: dict, timeout: int) -> tuple[dict, str]:
    params = {"outputFormat": "rapidJSON", "coordOutputFormat": COORD_FMT, "language": "de", **params}
    r = get(f"{BASE}/{endpoint}", params=params, timeout=timeout)
    if r.status_code != 200:
        raise EfaError(f"VRN-Fahrplanauskunft antwortet mit {r.status_code}.")
    try:
        return r.json(), r.url
    except ValueError:
        raise EfaError("Unerwartete Antwort der VRN-Fahrplanauskunft.") from None


def stops(get, query: str, timeout: int = 15, limit: int = 8) -> list[dict]:
    """Stop/address/POI suggestions for free text."""
    data, _ = _call(get, "XML_STOPFINDER_REQUEST", {
        "type_sf": "any", "name_sf": query, "anyObjFilter_sf": 0, "locationServerActive": 1,
        "vrnsuggestMacro": "vrn_suggest", "anyMaxSizeHitList": limit,
    }, timeout)
    out = []
    for loc in data.get("locations") or []:
        if not loc.get("id") or not loc.get("name"):
            continue
        out.append({
            "id": loc["id"], "name": loc["name"],
            "short": loc.get("disassembledName") or loc["name"],
            "place": (loc.get("parent") or {}).get("name") or "",
            "type": loc.get("type") or "",
            "kind": LOCATION_TYPES.get(loc.get("type"), "Ort"),
            "best": bool(loc.get("isBest")),
        })
    return out[:limit]


def _time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(TZ)
    except ValueError:
        return None


def _delay(planned: datetime | None, estimated: datetime | None) -> int | None:
    if planned is None or estimated is None:
        return None
    return round((estimated - planned).total_seconds() / 60)


def _point(p: dict, kind: str) -> dict:
    """A leg end or intermediate stop. kind: 'departure' or 'arrival'."""
    props = p.get("properties") or {}
    planned = _time(p.get(f"{kind}TimePlanned"))
    estimated = _time(p.get(f"{kind}TimeEstimated"))
    parent = p.get("parent") or {}
    return {
        "name": p.get("name") or parent.get("name") or "?",
        "id": parent.get("id") or p.get("id"),
        "platform": props.get("platformName") or props.get("platform") or "",
        "time": planned, "rt": estimated, "delay": _delay(planned, estimated),
        "coord": p.get("coord"),
        "cancelled": bool(p.get("isCancelled")),
    }


_TAG = re.compile(r"<[^>]+>")


def _plain(text: str | None) -> str:
    """Disruption texts come as HTML; we show them as text."""
    return re.sub(r"\s+", " ", html.unescape(_TAG.sub(" ", text or ""))).strip()


def _leg(leg: dict) -> dict:
    tr = leg.get("transportation") or {}
    product = tr.get("product") or {}
    mode = MODES.get(product.get("class"), "other")
    status = leg.get("realtimeStatus") or []
    origin, dest = _point(leg.get("origin") or {}, "departure"), _point(leg.get("destination") or {}, "arrival")
    seq = leg.get("stopSequence") or []
    # stopSequence includes the leg's own origin and destination.
    between = [_point(s, "departure" if s.get("departureTimePlanned") else "arrival") for s in seq[1:-1]]
    infos = []
    for info in leg.get("infos") or []:
        title = _plain(info.get("title") or info.get("subtitle") or info.get("urlText"))
        text = _plain(info.get("content"))
        if title or text:
            infos.append({"title": title, "text": text if text != title else ""})
    train_type = (tr.get("properties") or {}).get("trainType") or ""
    line = tr.get("disassembledName") or tr.get("number") or ""
    label = tr.get("number") or line or product.get("name") or ""
    if train_type and label.isdigit():  # long-distance trains: number "9553", trainType "ICE"
        label = f"{train_type} {label}"
    return {
        "mode": mode,
        "icon": ICONS.get(mode, "train"),
        "mode_label": MODE_LABELS[mode] if mode != "other" else (product.get("name") or MODE_LABELS["other"]),
        "line": line if mode != "walk" else "",
        # As vrn.de labels lines: "SWK 105" (operator + line), "RE1", "S2".
        "label": label if mode != "walk" else "",
        # Deutschlandticket / Semesterticket aren't valid on these.
        "long_distance": train_type.upper() in LONG_DISTANCE or product.get("class") in (14, 15, 16),
        "name": tr.get("name") or product.get("name") or "",
        "direction": (tr.get("destination") or {}).get("name") or "",
        "operator": (tr.get("operator") or {}).get("name") or "",
        "description": tr.get("description") or "",
        "origin": origin, "destination": dest, "stops": between,
        "minutes": round((leg.get("duration") or 0) / 60),
        "distance": leg.get("distance"),
        "coords": [c for c in (leg.get("coords") or []) if isinstance(c, list) and len(c) >= 2],
        "infos": infos,
        "realtime": "MONITORED" in status,
        "cancelled": any("CANCELLED" in s for s in status) or origin["cancelled"] or dest["cancelled"],
    }


def _journey(j: dict) -> dict | None:
    legs = [_leg(l) for l in j.get("legs") or []]
    if not legs:
        return None
    # Minutes to change between two rides (walks between them carry their own time).
    for prev, leg in zip(legs, legs[1:]):
        if prev["mode"] != "walk" and leg["mode"] != "walk":
            arr = prev["destination"]["rt"] or prev["destination"]["time"]
            dep = leg["origin"]["rt"] or leg["origin"]["time"]
            leg["transfer"] = round((dep - arr).total_seconds() / 60) if arr and dep else None
    first, last = legs[0]["origin"], legs[-1]["destination"]
    dep = first["rt"] or first["time"]
    arr = last["rt"] or last["time"]
    tickets = (j.get("fare") or {}).get("tickets") or []
    # "ab 2,62 €" as on vrn.de: the cheapest adult single ticket (2nd class) for the whole trip.
    singles = [t.get("priceBrutto") for t in tickets
               if t.get("person") == "ADULT" and t.get("travellerClass") == "SECOND"
               and t.get("timeValidity") == "SINGLE" and t.get("isSupplement") != "YES"
               and (t.get("priceBrutto") or 0) > 0]
    return {
        "dep": first["time"], "dep_rt": first["rt"], "dep_delay": first["delay"],
        "arr": last["time"], "arr_rt": last["rt"], "arr_delay": last["delay"],
        "minutes": round((arr - dep).total_seconds() / 60) if dep and arr else None,
        "changes": j.get("interchanges") or 0,
        "legs": legs,
        "rides": [l for l in legs if l["mode"] != "walk"],
        "cancelled": any(l["cancelled"] for l in legs),
        "realtime": any(l["realtime"] for l in legs),
        "infos": sum(len(l["infos"]) for l in legs),
        "price": min(singles) if singles else None,
        "long_distance": [l["label"] for l in legs if l.get("long_distance")],
    }


def trips(get, origin: str, destination: str, when: datetime, arrive: bool = False,
          timeout: int = 25) -> dict:
    """Connections from origin to destination around `when` (local time).

    Returns {"journeys": [...], "url": request URL}. Journeys are sorted by departure;
    the few EFA adds from before the requested time (or after it, for arrival searches)
    are dropped unless nothing else is left.
    """
    params = {
        **_place_params("origin", origin), **_place_params("destination", destination),
        "itdDate": when.strftime("%Y%m%d"), "itdTime": when.strftime("%H%M"),
        "itdTripDateTimeDepArr": "arr" if arrive else "dep",
        "useRealtime": 1, "itPathListActive": 1, "useUT": 1, "useProxFootSearch": 1,
        "calcNumberOfTrips": 6, "locationServerActive": 1,
    }
    data, url = _call(get, "XML_TRIP_REQUEST2", params, timeout)
    errors = [m for m in data.get("systemMessages") or [] if m.get("type") == "error"]
    journeys = [x for x in (_journey(j) for j in data.get("journeys") or []) if x]
    if not journeys and errors:
        raise EfaError(_message(errors[0]))
    journeys.sort(key=lambda j: (j["dep"] or when, j["arr"] or when))
    when = when.replace(tzinfo=TZ) if when.tzinfo is None else when
    if arrive:
        kept = [j for j in journeys if j["arr"] and j["arr"] <= when + timedelta(minutes=1)]
    else:
        kept = [j for j in journeys if j["dep"] and j["dep"] >= when - timedelta(minutes=1)]
    return {"journeys": kept or journeys, "url": url, "raw": data}


def _message(err: dict) -> str:
    text = err.get("text") or ""
    if "no connection" in text or err.get("code") in (-4000,):
        return "Keine Verbindung gefunden."
    if "multiple matches" in text:
        return "Start oder Ziel ist nicht eindeutig. Bitte aus den Vorschlägen wählen."
    if "no matches" in text or "not found" in text:
        return "Start oder Ziel wurde nicht gefunden."
    return f"Keine Verbindung gefunden ({text or err.get('code')})."


def now_local() -> datetime:
    return datetime.now(timezone.utc).astimezone(TZ)

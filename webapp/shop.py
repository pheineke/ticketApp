"""
myVRN ticket shop and ticket entitlements ("Berechtigungsnachweise"), reverse-engineered from
the app (net.mentz.ticketing / net.mentz.personalization in classes2.dex) and the web shop on
shop.myvrn.de (React, static/js/ShopContext.*.js).

All calls carry the user's bearer token (the web shop uses its cookie session instead).

  Products (public)    GET  MS_STATIC/products/VRN/VRN/dv_2.0.json   the app's assortment
  Entitlements         GET  Personalization/OData/TicketEntitlement?$expand=Ticket&$select=…
                            {value: [{Id, Status, Approved, EligibleFrom, EligibleUntil,
                                      Annotation, Usage, Created, Ticket: [{Key, Name, Tariff,
                                      AreaNumber, PriceLevel, ZoneKey, ZoneName, Usage}]}]}
                            The tickets an entitlement unlocks (the Deutschland-Semesterticket
                            is not in the public assortment).
  Universities         GET  Dfn/GetAuthorities  -> {value: [{EntityId, DisplayNames: [{Language, Name}]}]}
  Hochschul-Login      browser: /Dfn/Federate?entityId=<EntityId>&returnUrl=<url>
                            SAML login at the university (DFN-AAI) on the shop's cookie session;
                            approves a FederatedActivation entitlement.
  Order                POST TicketShop/Shop/CreateOrder {Purchase: [...], CommonOptions: [...], Voucher}
                            -> {Orders} (done) | {RedirectUrl} (payment) | {Error}
"""
from __future__ import annotations

import base64
import json
import time
from urllib.parse import quote
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Europe/Berlin")

BASE = "https://shop.myvrn.de/"
PRODUCTS_URL = BASE + "MS_STATIC/products/VRN/VRN/dv_2.0.json"
# The app always selects these fields: without $select the answer would include the uploaded
# Document, which a user token may not read (403).
ENTITLEMENTS_URL = (BASE + "Personalization/OData/TicketEntitlement?$expand=Ticket"
                    "&$select=ID,UserID,Created,Annotation,EligibleUntil,EligibleFrom,Approved,Usage,Status")
AUTHORITIES_URL = BASE + "Dfn/GetAuthorities"
CREATE_ORDER_URL = BASE + "TicketShop/Shop/CreateOrder"
# The web shop's way (AuthenticationPrivatePersonalizationService): a POST that reads.
# Usage 0 = WITH_AUTHORIZATION (entitlements), 1 = BAHN_CARD (discounts). -> {Count, Values: [...]}
ENTITLEMENTS_POST_URL = BASE + "Personalization/Entitlement/TicketEntitlements"
# On the shop's root (the web shop's PERSO base), not under TicketShop/ like the app's other shop calls.
INVOICE_URL = BASE + "Pdfconverter/Invoice"
ENTITLEMENTS_POST_BODY = {"Usage": 0, "Index": 0, "MaxNumber": 20, "ExpandEntitlementDocument": False,
                          "AnnotationIsAscending": True, "CreatedIsAscending": False}

# TicketEntitlement.Status (net.mentz.ticketing.data.personalization.TicketEntitlement.Status)
# and the app's labels (gullivr_ui de.json: entitlement_status_*).
STATUS_LABELS = {"approved": "Genehmigt", "pending": "In Bearbeitung (bis zu 3 Werktage)", "refused": "Abgelehnt",
                 "locked": "Gesperrt", "unknown": "Unbekannt"}
# The shop's login page continues to returnUrl after signing in.
LOGIN_URL = BASE + "authentication/login?organization=vrn&client=vrn&clientUsage=App&returnUrl="
ENTITLEMENTS_PAGE = "/authentication/private/tickets/entitlements"


def federate_path(entity_id: str, return_url: str = ENTITLEMENTS_PAGE) -> str:
    """The shop-relative path that starts the Hochschul-Login for a university."""
    return f"/Dfn/Federate?entityId={quote(entity_id, safe='')}&returnUrl={quote(return_url, safe='')}"


def verify_url(entity_id: str, return_url: str = ENTITLEMENTS_PAGE) -> str:
    """Hochschul-Login from outside the shop: sign in at shop.myvrn.de, then straight on to the
    university (Dfn/Federate runs on the shop's own cookie session, which only the shop's pages
    have), then back to return_url (absolute URLs to other sites work too)."""
    return LOGIN_URL + quote(federate_path(entity_id, return_url), safe="")


def authorities(data) -> list[dict]:
    """Universities from Dfn/GetAuthorities: [{id, name}] sorted by name."""
    items = data.get("value", data) if isinstance(data, dict) else data
    out = []
    for a in items or []:
        names = {n.get("Language"): n.get("Name") for n in a.get("DisplayNames") or []}
        if a.get("EntityId"):
            out.append({"id": a["EntityId"], "name": names.get("de") or names.get("en") or a["EntityId"]})
    return sorted(out, key=lambda a: a["name"])


def snapshot(call) -> dict:
    """Raw read-only answers for analysis (debug): entitlements, universities, products.

    `call(method, url, payload=None)` is app.api_call bound to the session (adds the bearer token).
    """
    out = {"at": time.time()}
    for key, url in (("entitlements", ENTITLEMENTS_URL), ("authorities", AUTHORITIES_URL),
                     ("products", PRODUCTS_URL)):
        rec = call("GET", url)
        out[key] = {"status": rec.get("status"), "json": rec.get("json"), "text": (rec.get("text") or "")[:4000]}
    rec = call("POST", ENTITLEMENTS_POST_URL, ENTITLEMENTS_POST_BODY)
    out["entitlements_post"] = {"status": rec.get("status"), "json": rec.get("json"), "text": (rec.get("text") or "")[:4000]}
    return out


def invoice_pdf(rec: dict) -> tuple[bytes | None, str | None]:
    """Pdfconverter/Invoice {TicketOrderId: [order Id], IsGuestUser} -> {Success, Invoice, Error}:
    the order's invoice, a base64 PDF (web shop CommonOrderInvoicePdf). -> (pdf, error)."""
    data = rec.get("json") if isinstance(rec.get("json"), dict) else {}
    text = data.get("Invoice") or ""
    if not text:
        err = data.get("Error") or {}
        return None, err.get("Message") or rec.get("status") or "keine Rechnung"
    try:
        pdf = base64.b64decode(text)
    except (ValueError, TypeError):
        pdf = b""
    if not pdf.startswith(b"%PDF-"):
        return None, "Unbekanntes Format: " + str(text)[:40]
    return pdf, None


# ---- Deutschland-Semesterticket -----------------------------------------------------------
# A subscription bought once per semester (then renewed monthly at 0 €), unlocked by a
# DEUTSCHLAND_TICKET_STUDENT entitlement that the Hochschul-Login creates for the semester's
# timeframe (eduPersonEntitlement urn:geant:dfn.de:<uni>:entitlement:dticket:timeframe:<from>-<to>).
SEMESTER_TICKET = "9996"
SEMESTER_NAME = "Deutschland-Semesterticket"
STUDENT_ANNOTATION = "DEUTSCHLAND_TICKET_STUDENT"
# Status 2 = approved (as seen on approved entitlements; the app's enum: unknown, pending, approved, refused, locked).
STATUS_NAMES = {0: "unknown", 1: "pending", 2: "approved", 3: "refused", 4: "locked"}


def ticket_type(ent: dict) -> str | None:
    try:
        return json.loads(ent.get("Annotation") or "{}").get("TicketType")
    except ValueError:
        return None


def student_entitlement(items: list, now: datetime) -> tuple[dict | None, dict | None]:
    """(entitlement valid now, most recent student entitlement) among the user's entitlements."""
    student = [e for e in items or [] if ticket_type(e) == STUDENT_ANNOTATION]
    student.sort(key=lambda e: e.get("EligibleFrom") or "", reverse=True)
    current = None
    for e in student:
        f, u = _ts(e.get("EligibleFrom")), _ts(e.get("EligibleUntil"))
        if e.get("Approved") and f and u and f <= now <= u:
            current = e
            break
    return current, (student[0] if student else None)


def student_entitlements(items: list) -> list[dict]:
    """All DEUTSCHLAND_TICKET_STUDENT entitlements, newest first, for display."""
    out = [e for e in items or [] if ticket_type(e) == STUDENT_ANNOTATION]
    return sorted(out, key=lambda e: e.get("Created") or "", reverse=True)


def semester_tickets(orders: list) -> list[dict]:
    """Semesterticket tickets in the order history, newest first, with their order."""
    out = []
    for o in orders or []:
        for t in o.get("Tickets") or []:
            if str(t.get("TicketId")) == SEMESTER_TICKET and not t.get("Canceled") and not t.get("Refunded"):
                out.append({"order": o, "ticket": t, "from": _ts(t.get("ValidFrom")), "to": _ts(t.get("ValidTo"))})
    out.sort(key=lambda x: x["from"] or datetime.min.replace(tzinfo=TZ), reverse=True)
    return out


def month_start(now: datetime) -> datetime:
    return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


def next_month(d: datetime) -> datetime:
    return (d.replace(day=28) + timedelta(days=4)).replace(day=1)


def build_semester_order(template: dict | None, person: dict | None, now: datetime) -> dict:
    """CreateOrder payload for this month's Semesterticket (0 €).

    Mirrors what the backend stored for earlier purchases (ListOrdersV2): ticket 9996 in the
    vrn tariff, valid from the start of the month (local time), price 0, monthly validity
    (TimeValidity 5). The web shop's order builder (ShopContext.$) supplies the field names.
    """
    t = (template or {}).get("ticket") or {}
    start = month_start(now)
    order = {
        "TicketId": SEMESTER_TICKET,
        "Ticket": t.get("TicketName") or SEMESTER_NAME,
        "ProductNumber": t.get("ProductNumber") or SEMESTER_TICKET,
        "TicketClass": t.get("TicketClass") or SEMESTER_TICKET,
        "Tariff": (t.get("Tariff") or "vrn").lower(),
        "AreaNumber": "1",                       # "deutschlandweit" (RelationId of the D-Ticket product)
        "DateTime": _iso(start),
        "DateTimeUntil": _iso(next_month(start)),
        "ValidMinutes": 0,
        "Price": 0.0,
        "VatRate": 7.0,
        "TimeValidity": t.get("TimeValidity") or 5,
    }
    if person and person.get("last") and person.get("first"):
        order["PurchaseUser"] = {k: v for k, v in {
            "LastName": person["last"], "FirstName": person["first"],
            "Dob": person.get("dob"), "Gender": person.get("gender"),
        }.items() if v}
    return {
        "Purchase": [order],
        # As the web shop sends them (ShopContext.Q): AppName = <organization>_<client>_<clientUsage>.
        "CommonOptions": [{"Key": "AppName", "Value": "vrn_vrn_App"}, {"Key": "ClientName", "Value": "vrn"},
                          {"Key": "OrganizationName", "Value": "vrn"}],
    }


def _ts(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(TZ)
    except ValueError:
        return None


def _iso(d: datetime) -> str:
    return d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def entitlements(call) -> tuple[list | None, str]:
    """The user's entitlements: the app's OData GET, else the web shop's POST. -> (items, status)."""
    rec = call("GET", ENTITLEMENTS_URL)
    if rec.get("code") == 200 and isinstance(rec.get("json"), dict):
        return rec["json"].get("value") or [], rec["status"]
    rec = call("POST", ENTITLEMENTS_POST_URL, ENTITLEMENTS_POST_BODY)
    data = rec.get("json")
    if rec.get("code") == 200 and isinstance(data, dict):
        return data.get("Values") or ([data["Value"]] if data.get("Value") else []), rec["status"]
    return None, rec.get("status") or "?"

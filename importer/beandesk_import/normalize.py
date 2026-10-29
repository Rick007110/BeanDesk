"""Turns Overpass elements into place records and filters out what is no laptop spot."""

from dataclasses import dataclass

CATEGORY_BY_AMENITY = {
    "cafe": "cafe",
    "library": "library",
    "coworking_space": "coworking",
}


@dataclass(frozen=True)
class PlaceRecord:
    osm_type: str
    osm_id: int
    category: str
    name: str
    address: str | None
    lat: float
    lng: float
    opening_hours: str | None
    tags: dict


def noise_reason(tags: dict) -> str | None:
    """Why a place should not be imported, or None. Pubs tagged as cafe are hidden by hand instead."""
    if tags.get("shop") == "cannabis" or tags.get("cannabis", "no") != "no":
        return "cannabis"
    if tags.get("access") in ("private", "no"):
        return "private"
    if tags.get("opening_hours", "").strip().lower() in ("closed", "off"):
        return "closed"
    return None


def normalize(element: dict) -> PlaceRecord | None:
    tags = element.get("tags") or {}
    category = CATEGORY_BY_AMENITY.get(tags.get("amenity", ""))
    name = (tags.get("name") or "").strip()
    if category is None or not name or noise_reason(tags):
        return None

    if "lat" in element:
        lat, lng = element["lat"], element["lon"]
    elif "center" in element:
        lat, lng = element["center"]["lat"], element["center"]["lon"]
    else:
        return None

    return PlaceRecord(
        osm_type=element["type"],
        osm_id=element["id"],
        category=category,
        name=name,
        address=address(tags),
        lat=lat,
        lng=lng,
        opening_hours=(tags.get("opening_hours") or "").strip() or None,
        tags=tags,
    )


def normalize_all(elements: list[dict]) -> list[PlaceRecord]:
    records = {}
    for element in elements:
        record = normalize(element)
        if record:
            records[(record.osm_type, record.osm_id)] = record
    return list(records.values())


def address(tags: dict) -> str | None:
    street = tags.get("addr:street")
    if not street:
        return None
    number = tags.get("addr:housenumber")
    return f"{street} {number}" if number else street


def osm_facts(tags: dict) -> dict:
    """Wifi and power as OSM states them; None when the tag is missing or unclear."""
    access = tags.get("internet_access")
    if access is None:
        wifi = None
    elif access == "no":
        wifi = False
    elif access in ("yes", "wifi") or "wlan" in access.split(";"):
        wifi = True
    else:
        wifi = None  # For example "terminal" or "wired".

    fee = tags.get("internet_access:fee")
    # "customers" means free for whoever buys something, which is free for our users.
    wifi_free = True if fee in ("no", "customers") else False if fee == "yes" else None

    power = {"yes": True, "no": False}.get(tags.get("power_supply", ""))
    return {"wifi": wifi, "wifi_free": wifi_free, "power": power}

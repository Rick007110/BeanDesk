import requests

from .cities import City

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
# Overpass asks clients to identify themselves.
USER_AGENT = "BeanDesk-import/0.1 (+https://github.com/Rick007110/BeanDesk)"


class OverpassError(RuntimeError):
    pass


def build_query(city: City, timeout_seconds: int = 180) -> str:
    name = city.name.replace("\\", "\\\\").replace('"', '\\"')
    return (
        f"[out:json][timeout:{timeout_seconds}];\n"
        f'area["boundary"="administrative"]["admin_level"="{city.admin_level}"]["name"="{name}"]->.city;\n'
        'nwr["amenity"~"^(cafe|library|coworking_space)$"](area.city);\n'
        # `center` gives ways and relations a single coordinate.
        "out center tags;\n"
    )


def fetch_elements(city: City, url: str = OVERPASS_URL) -> list[dict]:
    response = requests.post(
        url,
        data={"data": build_query(city)},
        headers={"User-Agent": USER_AGENT},
        timeout=300,
    )
    response.raise_for_status()
    payload = response.json()
    # Overpass reports timeouts and memory errors in a 200 response.
    remark = payload.get("remark", "")
    if "error" in remark.lower():
        raise OverpassError(remark)
    return payload.get("elements", [])

import tomllib
from dataclasses import dataclass
from pathlib import Path

CITIES_FILE = Path(__file__).resolve().parent.parent / "cities.toml"


@dataclass(frozen=True)
class City:
    slug: str
    name: str
    admin_level: int
    timezone: str


def load_cities(path: Path = CITIES_FILE) -> dict[str, City]:
    with path.open("rb") as f:
        data = tomllib.load(f)
    return {
        slug: City(slug=slug, name=c["name"], admin_level=c["admin_level"], timezone=c["timezone"])
        for slug, c in data.items()
    }


def get_city(slug: str) -> City:
    cities = load_cities()
    if slug not in cities:
        raise SystemExit(f"Unknown city '{slug}'. Known: {', '.join(sorted(cities))}")
    return cities[slug]

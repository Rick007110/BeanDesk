from dataclasses import dataclass

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .normalize import PlaceRecord

# Refuse to mark more than half of a city as deleted in one run: that is far more likely an
# Overpass hiccup (wrong area, partial result) than half the cafés closing in a week.
MIN_SEEN_RATIO = 0.5

UPSERT_PLACE = """
insert into public.places
    (osm_type, osm_id, city, category, name, address, location, opening_hours, osm_tags, updated_at)
values
    (%(osm_type)s, %(osm_id)s, %(city)s, %(category)s::public.place_category, %(name)s, %(address)s,
     extensions.st_setsrid(extensions.st_makepoint(%(lng)s, %(lat)s), 4326)::extensions.geography,
     %(opening_hours)s, %(tags)s, now())
on conflict (osm_type, osm_id) do update set
    city = excluded.city,
    category = excluded.category,
    name = excluded.name,
    address = excluded.address,
    location = excluded.location,
    opening_hours = excluded.opening_hours,
    osm_tags = excluded.osm_tags,
    deleted_at = null,
    updated_at = now()
-- Only touch rows that really changed, so updated_at stays useful for the app's delta sync.
where (places.city, places.category, places.name, places.address, places.opening_hours,
       places.osm_tags, places.deleted_at, extensions.st_astext(places.location))
   is distinct from
      (excluded.city, excluded.category, excluded.name, excluded.address, excluded.opening_hours,
       excluded.osm_tags, null, extensions.st_astext(excluded.location))
returning (xmax = 0) as inserted
"""

SOFT_DELETE_MISSING = """
update public.places p
set deleted_at = now(), updated_at = now()
where p.city = %(city)s
  and p.deleted_at is null
  and not exists (
      select 1 from unnest(%(types)s::text[], %(ids)s::bigint[]) as seen(osm_type, osm_id)
      where seen.osm_type = p.osm_type and seen.osm_id = p.osm_id
  )
"""

EXPORT_ROWS = """
select p.id, p.osm_type, p.osm_id, p.category::text as category, p.name, p.address,
       extensions.st_y(p.location::extensions.geometry) as lat,
       extensions.st_x(p.location::extensions.geometry) as lng,
       p.opening_hours, p.osm_tags,
       greatest(p.updated_at, c.updated_at) as updated_at,
       c.place_id is not null as has_community,
       c.wifi::text as community_wifi, c.power::text as community_power, c.quiet as community_quiet,
       c.laptop_policy::text as community_laptop_policy, c.closed_reports, c.last_report_at
from public.places p
left join public.place_community c on c.place_id = p.id
where p.city = %(city)s and p.deleted_at is null and not p.hidden
order by p.id
"""


class ImportAborted(RuntimeError):
    pass


@dataclass
class ImportResult:
    inserted: int = 0
    updated: int = 0
    unchanged: int = 0
    deleted: int = 0


def import_places(conn: psycopg.Connection, city: str, records: list[PlaceRecord], force: bool = False) -> ImportResult:
    """Upserts the places of one city and soft-deletes the ones OSM no longer has, in one transaction."""
    result = ImportResult()
    with conn.transaction(), conn.cursor() as cur:
        cur.execute(
            "select count(*) from public.places where city = %s and deleted_at is null",
            (city,),
        )
        existing = cur.fetchone()[0]
        if existing and len(records) < existing * MIN_SEEN_RATIO and not force:
            raise ImportAborted(
                f"Overpass returned {len(records)} places for {city}, but {existing} are active. "
                "Run with --force if this is correct."
            )

        for record in records:
            cur.execute(
                UPSERT_PLACE,
                {
                    "osm_type": record.osm_type,
                    "osm_id": record.osm_id,
                    "city": city,
                    "category": record.category,
                    "name": record.name,
                    "address": record.address,
                    "lat": record.lat,
                    "lng": record.lng,
                    "opening_hours": record.opening_hours,
                    "tags": Jsonb(record.tags),
                },
            )
            row = cur.fetchone()
            if row is None:
                result.unchanged += 1
            elif row[0]:
                result.inserted += 1
            else:
                result.updated += 1

        cur.execute(
            SOFT_DELETE_MISSING,
            {
                "city": city,
                "types": [r.osm_type for r in records],
                "ids": [r.osm_id for r in records],
            },
        )
        result.deleted = cur.rowcount
    return result


def export_rows(conn: psycopg.Connection, city: str) -> list[dict]:
    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute(EXPORT_ROWS, {"city": city})
        return cur.fetchall()

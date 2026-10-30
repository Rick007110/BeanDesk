"""Turns an OpenStreetMap extract (.osm.pbf) into the file the database imports with import_osm_file().

The output has the shape of an Overpass answer, so the same import rules apply (osm_places()):

    {"generated_at": "...", "source": "...", "cells": [[lat_index, lng_index], ...], "elements": [...]}

"cells" are the grid cells (0.02° by 0.03°) that touch the area of the extract: places in them that
the extract no longer has are closed by the import. Each element also gets "gemeente", the
municipality it lies in (an administrative boundary with admin_level 8 in the extract), which the
import uses as the place's city.

Needs pyosmium and shapely: pip install -e '.[extract]'
"""

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import osmium
from shapely import STRtree
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union
from shapely.prepared import prep

AMENITIES = {"cafe", "library", "coworking_space"}
CELL_LAT = 0.02
CELL_LNG = 0.03


def mean_point(points: list[tuple[float, float]]) -> tuple[float, float]:
    """The mean of a ring or line; a closed ring repeats its first node at the end, which counts once."""
    if len(points) > 1 and points[0] == points[-1]:
        points = points[:-1]
    return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)


class Collector(osmium.SimpleHandler):
    """Collects every cafe, library and coworking space with one coordinate each, and the
    municipalities."""

    def __init__(self) -> None:
        super().__init__()
        self.elements: list[dict] = []
        self.municipalities: list[tuple[str, object]] = []

    @staticmethod
    def _wanted(tags) -> bool:
        return tags.get("amenity") in AMENITIES

    def _add(self, osm_type: str, osm_id: int, lat: float, lon: float, tags) -> None:
        self.elements.append(
            {"type": osm_type, "id": osm_id, "lat": round(lat, 7), "lon": round(lon, 7), "tags": dict(tags)}
        )

    def node(self, n) -> None:
        if self._wanted(n.tags) and n.location.valid():
            self._add("node", n.id, n.location.lat, n.location.lon, n.tags)

    def way(self, w) -> None:
        if not self._wanted(w.tags):
            return
        points = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
        if points:
            # The mean of the nodes: close enough to Overpass's "center" for a building.
            self._add("way", w.id, *mean_point(points), w.tags)

    def area(self, a) -> None:
        # Ways are handled above; areas also come from multipolygon and boundary relations.
        if a.from_way():
            return
        if a.tags.get("boundary") == "administrative" and a.tags.get("admin_level") == "8" and a.tags.get("name"):
            self._add_municipality(a)
            return
        if not self._wanted(a.tags):
            return
        points = []
        for ring in a.outer_rings():
            # Every outer ring is closed: leave out its repeated first node.
            points += [(nd.lat, nd.lon) for nd in ring if nd.location.valid()][:-1]
        if points:
            self._add("relation", a.orig_id(), *mean_point(points), a.tags)


    def _add_municipality(self, a) -> None:
        polygons = []
        for outer in a.outer_rings():
            shell = [(nd.lon, nd.lat) for nd in outer if nd.location.valid()]
            holes = [[(nd.lon, nd.lat) for nd in inner if nd.location.valid()] for inner in a.inner_rings(outer)]
            if len(shell) >= 4:
                polygons.append(Polygon(shell, [hole for hole in holes if len(hole) >= 4]))
        if polygons:
            self.municipalities.append((a.tags["name"], unary_union(polygons)))


def assign_municipalities(elements: list[dict], municipalities: list[tuple[str, object]]) -> None:
    """Gives every element inside a municipality its name as "gemeente"."""
    if not municipalities:
        return
    tree = STRtree([shape for _, shape in municipalities])
    for element in elements:
        hits = tree.query(Point(element["lon"], element["lat"]), predicate="intersects")
        if len(hits) > 0:
            element["gemeente"] = municipalities[int(hits[0])][0]


def read_poly(path: Path):
    """Reads an Osmosis .poly file (as Geofabrik publishes them) into a shapely geometry."""
    lines = [line.strip() for line in path.read_text().splitlines()]
    polygons, holes, ring, name = [], [], None, None
    for line in lines[1:]:
        if not line:
            continue
        if line == "END":
            if ring is None:
                break
            (holes if name.startswith("!") else polygons).append(Polygon(ring))
            ring = None
        elif ring is None:
            name, ring = line, []
        else:
            lon, lat = (float(v) for v in line.split()[:2])
            ring.append((lon, lat))
    shape = unary_union(polygons)
    return shape.difference(unary_union(holes)) if holes else shape


def cells_touching(shape) -> list[list[int]]:
    """The search cells that overlap the shape, as [lat_index, lng_index]."""
    min_lng, min_lat, max_lng, max_lat = shape.bounds
    prepared = prep(shape)
    cells = []
    for i in range(math.floor(min_lat / CELL_LAT), math.floor(max_lat / CELL_LAT) + 1):
        for j in range(math.floor(min_lng / CELL_LNG), math.floor(max_lng / CELL_LNG) + 1):
            if prepared.intersects(box(j * CELL_LNG, i * CELL_LAT, (j + 1) * CELL_LNG, (i + 1) * CELL_LAT)):
                cells.append([i, j])
    return cells


def extract(pbf: Path, poly: Path, source: str) -> dict:
    collector = Collector()
    # Locations for way nodes; areas for multipolygon relations.
    collector.apply_file(str(pbf), locations=True)
    assign_municipalities(collector.elements, collector.municipalities)
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": source,
        "cells": cells_touching(read_poly(poly)),
        "elements": collector.elements,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pbf", type=Path)
    parser.add_argument("poly", type=Path, help="the boundary of the extract, e.g. netherlands.poly from Geofabrik")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--source", default="Geofabrik")
    args = parser.parse_args()

    data = extract(args.pbf, args.poly, args.source)
    args.out.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    print(f"{len(data['elements'])} places, {len(data['cells'])} cells, {args.out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()

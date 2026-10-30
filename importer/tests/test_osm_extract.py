"""Tests turning an OpenStreetMap extract into the import file, on a small handmade .osm file."""

from pathlib import Path

import pytest

osm_extract = pytest.importorskip("beandesk_import.osm_extract", reason="needs the 'extract' extras (pyosmium, shapely)")

FIXTURES = Path(__file__).resolve().parent / "fixtures"


@pytest.fixture(scope="module")
def data() -> dict:
    return osm_extract.extract(FIXTURES / "mini.osm", FIXTURES / "mini.poly", "test")


def test_keeps_laptop_spots_with_one_coordinate_each(data):
    by_id = {(e["type"], e["id"]): e for e in data["elements"]}
    # The pub is left out here; osm_places() in the database filters the rest (names, cannabis, ...).
    assert sorted(by_id) == [("node", 1), ("relation", 300), ("way", 100)]
    assert (by_id[("node", 1)]["lat"], by_id[("node", 1)]["lon"]) == (52.0911, 5.1201)
    # The middle of the building and of the multipolygon, not a corner.
    assert (by_id[("way", 100)]["lat"], by_id[("way", 100)]["lon"]) == pytest.approx((52.101, 5.101))
    assert (by_id[("relation", 300)]["lat"], by_id[("relation", 300)]["lon"]) == pytest.approx((52.052, 5.052))
    assert by_id[("way", 100)]["tags"] == {"amenity": "library", "name": "Bieb in een gebouw", "building": "yes"}


def test_cells_cover_the_boundary(data):
    cells = {tuple(c) for c in data["cells"]}
    # 52.00-52.15 N is lat_index 2600-2607, 5.0-5.2 E is lng_index 166-173.
    assert len(cells) == 8 * 8
    assert (2604, 170) in cells
    assert (2599, 170) not in cells

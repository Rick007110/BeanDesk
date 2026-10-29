from beandesk_import.cities import City, load_cities
from beandesk_import.overpass import build_query


def test_query_selects_the_municipality_and_all_categories():
    query = build_query(City("utrecht", "Utrecht", 8, "Europe/Amsterdam"))
    assert '["admin_level"="8"]["name"="Utrecht"]' in query
    assert 'nwr["amenity"~"^(cafe|library|coworking_space)$"](area.city);' in query
    assert "out center tags;" in query


def test_query_escapes_quotes_in_names():
    query = build_query(City("x", 'Den "Bosch"', 8, "Europe/Amsterdam"))
    assert '["name"="Den \\"Bosch\\""]' in query


def test_cities_file_is_valid():
    cities = load_cities()
    assert "utrecht" in cities
    assert all(c.admin_level == 8 and c.timezone == "Europe/Amsterdam" for c in cities.values())

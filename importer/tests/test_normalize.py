from beandesk_import.normalize import normalize_all, osm_facts


def test_filters_noise_and_keeps_laptop_spots(overpass_elements):
    records = normalize_all(overpass_elements)
    assert sorted((r.osm_type, r.osm_id) for r in records) == [
        ("node", 1001),
        ("node", 3003),
        ("relation", 8008),
        ("way", 2002),
    ]


def test_maps_categories_addresses_and_coordinates(overpass_elements):
    records = {r.osm_id: r for r in normalize_all(overpass_elements)}

    cafe = records[1001]
    assert cafe.category == "cafe"
    assert cafe.address == "Oudegracht 1"
    assert cafe.opening_hours == "Mo-Fr 08:00-18:00; Sa 09:00-17:00"

    library = records[2002]
    assert library.category == "library"
    assert library.address == "Neude"
    assert (library.lat, library.lng) == (52.0927, 5.1196)

    assert records[3003].category == "coworking"
    assert records[3003].address is None


def test_duplicate_elements_are_imported_once(overpass_elements):
    assert len(normalize_all(overpass_elements + overpass_elements[:1])) == 4


def test_osm_facts():
    assert osm_facts({}) == {"wifi": None, "wifi_free": None, "power": None}
    assert osm_facts({"internet_access": "wlan", "internet_access:fee": "no"}) == {
        "wifi": True,
        "wifi_free": True,
        "power": None,
    }
    assert osm_facts({"internet_access": "terminal;wlan"})["wifi"] is True
    assert osm_facts({"internet_access": "terminal"})["wifi"] is None
    assert osm_facts({"internet_access": "no"})["wifi"] is False
    assert osm_facts({"internet_access:fee": "customers"})["wifi_free"] is True
    assert osm_facts({"internet_access:fee": "yes"})["wifi_free"] is False
    assert osm_facts({"power_supply": "yes"})["power"] is True
    assert osm_facts({"power_supply": "no"})["power"] is False

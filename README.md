<img src="docs/brand/icon-rounded.svg" width="88" alt="BeanDesk-logo">

# BeanDesk

Android-app die laat zien welke koffiezaak, bibliotheek of coworkingruimte in de buurt geschikt is om met je laptop te werken: met wifi, stopcontacten en rust. Het plan staat in [Plan: Laptop-friendly koffie-app (Android)](https://claude.ai/artifact/APAtzUqy63MBkCzR6YKeRk).

## Hoe het in elkaar zit

De backend is het Supabase-project **BeanDesk** (Frankfurt). Alles wat met data gebeurt, staat als functie in de database:

- **Import:** `import_city()` haalt de zaken van een stad uit OpenStreetMap (via de `http`-extensie) en `import_osm_elements()` past de regels toe: wat geen werkplek is valt af, verdwenen zaken krijgen een `deleted_at`. `pg_cron` doet dit elke maandag om 03:17 UTC voor de steden met `active = true` in de tabel `cities`.
- **Stad-export:** `city_export()` bouwt per stad één JSON met alle zichtbare plekken en de laatste meldingen.
- **Zoeken:** de edge function `search` (`supabase/functions/search`) zoekt een plaats, adres of postcode op bij PDOK en geeft de plekken eromheen. Wie als eerste een gebied zoekt, laat het ophalen uit OpenStreetMap en opslaan; daarna komt het voor iedereen direct uit de database. Zie [Zoeken](#zoeken).
- **De app:** haalt die export in één keer op, bewaart hem in Room en filtert en sorteert daarna alleen op de telefoon. Meldingen gaan als anonieme gebruiker rechtstreeks naar `place_reports`.

De locatie van de gebruiker gaat dus nergens heen: bij zoeken verstuurt de app alleen de getypte tekst. De app zelf doet geen calls naar Overpass, PDOK of Google.

| Map | Inhoud |
| --- | --- |
| `app/` | Android-app: Kotlin, Jetpack Compose, MapLibre, Room, Ktor |
| `core/` | Logica zonder Android: openingstijden, filters, afstand, routelink en het inlezen van de stad-export. Kan later naar Kotlin Multiplatform als er een iOS-app komt. |
| `supabase/` | Migraties (schema, RLS-regels, triggers, import, export, de wekelijkse taak) en de edge function `search` |
| `importer/` | Kleine Python-CLI om die databasefuncties aan te roepen, plus de databasetests |
| `.github/workflows/` | CI en de releases met APK |
| `docs/brand/` | Logo, kleuren en het script dat alle logobestanden maakt |

## Aan de slag

### Android-app

Nodig: een recente Android Studio en JDK 21.

1. Open de map in Android Studio en start de app op een emulator of telefoon. Hij gebruikt het Supabase-project uit `gradle.properties`; de anon key is bedoeld om in apps te staan.
2. Zonder internet of om iets los van de echte data te proberen: zet `beandesk.useSampleData=true`. Dan toont een debug-build twaalf verzonnen zaken in Utrecht (`app/src/debug/assets/sample/utrecht.json`) en blijven meldingen op de telefoon.

De logica in `core` test je zo:

```bash
./gradlew :core:test
```

### Releases

Elke push naar `main` zet een pre-release met een installeerbare APK onder [Releases](https://github.com/Rick007110/BeanDesk/releases); de nieuwste tien blijven staan. Een tag zoals `v0.2.0` levert een gewone release op, een tag met achtervoegsel zoals `v0.2.0-test.1` een pre-release. Zonder tag kan het ook: start de workflow "Release" met de hand (Actions → Release → Run workflow) en vink "release" aan. Dan wordt de versie uit `beandesk.versionName` in `gradle.properties` een gewone release, met de tag erbij. Open de APK op je telefoon of sleep hem op een emulator. Elke build heeft een hoger versienummer en dezelfde debug-sleutel (`app/debug.keystore`, niet geheim). Daardoor installeer je hem over de vorige heen, ook over een build uit Android Studio.

Voor de Play Store komt er later een release-build met een eigen uploadsleutel; de debug-sleutel is daar niet voor bedoeld.

### Supabase

Het project `hsjufnfhhiowwaiyvcvx` draait in Frankfurt; de migraties in `supabase/migrations` staan erin, met dezelfde versienummers. Nieuwe migraties zet je erbij met:

```bash
supabase link --project-ref hsjufnfhhiowwaiyvcvx
```

```bash
supabase db push
```

De edge function zet je neer met:

```bash
supabase functions deploy search
```

Migratie `20261030000000_search_areas` is via de SQL-editor in het dashboard gedraaid. Hij staat daarom niet in de migratiegeschiedenis van het project; `supabase db push` probeert hem dan opnieuw, en dat mislukt omdat de tabellen al bestaan. Markeer hem één keer als gedaan met `supabase migration repair --status applied 20261030000000`.

Anoniem inloggen moet aan staan (Authentication → Sign In / Providers → "Allow anonymous sign-ins"); daarzonder werkt het melden niet. In `supabase/config.toml` staat het voor lokaal gebruik al aan.

Handige SQL in de SQL-editor:

```sql
select public.import_city('utrecht');                           -- nu importeren
update public.cities set active = true where slug = 'groningen';  -- stad toevoegen aan de wekelijkse import
update public.places set hidden = true where id = 123;            -- een kroeg die als café getagd is verbergen
```

### Python-CLI en databasetests

```bash
cd importer && python -m venv .venv && . .venv/bin/activate && pip install -e '.[test]'
```

Met `DATABASE_URL` (in Supabase de connection string "Session pooler") roep je de databasefuncties aan:

```bash
python -m beandesk_import import utrecht
```

```bash
python -m beandesk_import export utrecht --out-dir out
```

Met `--overpass-file` importeer je een opgeslagen Overpass-antwoord in plaats van de live API.

De tests draaien met `pytest` en testen de SQL-functies zelf. Ze hebben `BEANDESK_TEST_DATABASE_URL` nodig, met een PostGIS-database die weg mag: ze gooien de schema's leeg en zetten de migraties opnieuw neer.

## Stad-export

Versie 1 bevat per stad de tijdzone, een middelpunt voor de kaart, de OSM-attributie en per zaak:

- naam, categorie, adres en coördinaten;
- de ruwe `opening_hours`;
- `osm`: wifi, gratis wifi en stopcontacten volgens OSM;
- `community`: de laatste meldingen van gebruikers.

De databasefunctie `city_export()` schrijft het formaat, `core/.../export/CityExport.kt` leest het. Een test in `importer/tests/test_database.py` vergelijkt de uitvoer met het voorbeeld waarop de Kotlin-tests draaien, zodat beide kanten gelijk blijven. De app vraagt hem op via `GET /rest/v1/rpc/city_export?p_city=utrecht`.

## Zoeken

Het zoekveld boven de lijst doet twee dingen:

- **Typen** filtert de getoonde plekken direct op naam en adres, op de telefoon. Hoofdletters en accenten maken niet uit.
- **Zoeken** (de zoektoets of de kaart "Zoek rond …") vraagt de edge function om de plekken rond een plaats, adres of postcode. Het chipje met de plaatsnaam brengt je terug naar de stad.

De edge function:

1. zoekt de tekst één keer op bij de [PDOK Locatieserver](https://api.pdok.nl/bzk/locatieserver/search/v3_1/ui/) en bewaart het antwoord in `geocode_cache`. Een plaats krijgt een straal van 4 km, een straat 2 km, een adres of postcode 1,5 km;
2. deelt de wereld op in vakjes van 0,02° bij 0,03° (ongeveer 2 bij 2 km) en kijkt in `osm_cells` welke vakjes in die straal nog nooit, of langer dan 30 dagen geleden, uit OpenStreetMap zijn gehaald;
3. haalt alleen die vakjes op bij Overpass (hooguit 48 per keer, de dichtstbijzijnde eerst) en slaat ze op met `import_osm_area()`, met dezelfde regels als de stadsimport. overpass-api.de blokkeert het netwerk van Supabase (elk verzoek krijgt 406), dus de functie vraagt het eerst twee keer aan de server van VK (maps.mail.ru), daarna aan overpass.private.coffee en pas als laatste aan overpass-api.de;
4. antwoordt met `area_export()`: hetzelfde formaat als de stad-export, dichtstbijzijnde eerst, plus `radius_m` en `search`.

Na een melding vraagt de app de functie om het vakje van die plek opnieuw op te halen, als dat langer dan een uur geleden is. Zo komen verbeteringen in OpenStreetMap snel binnen. Alle gebruikers samen starten hooguit 30 Overpass-aanvragen per 10 minuten (`osm_fetches`). Lukt Overpass niet, dan antwoordt de functie met wat er al in de database staat en probeert hij het bij de volgende zoekopdracht opnieuw.

Zoekt iemand een gebied dat al is opgehaald, dan kost dat alleen een databasevraag. De app bewaart de laatste tien gezochte gebieden ook op de telefoon.

De tests van de functie draaien met Node (22.18 of nieuwer):

```bash
node --test supabase/functions/search/search.test.ts
```

## Keuzes die afwijken van het plan of het aanvullen

- **Openingstijden:** OpeningHoursParser kan alleen parsen, niet bepalen of een zaak open is. Daarom staat er een eigen evaluator in `core`, zonder Java-afhankelijkheid. Die snapt de gangbare vormen (dagen, tijden, over middernacht, `off`, `24/7`) en negeert feestdagen. Alles daarbuiten toont de app als "onbekend".
- **Namen:** de tabellen heten `places` en `place_reports` in plaats van `cafes`, omdat ook bibliotheken en coworkingruimtes meedoen. Kolomnamen zijn Engels.
- **Meldingen:** wat gebruikers melden staat in `place_community`, los van de OSM-data, en wordt door een trigger bijgewerkt. Nu wint de laatste melding; in fase 2 komt de gewogen laptop-score.
- **Misbruik:** gebruikers kunnen `trust_score`, `user_id` en `reported_at` niet zelf zetten. Er geldt een daglimiet: 20 meldingen voor anonieme accounts en 60 voor gewone. Dat zijn startwaarden.
- **Locatie:** de app vraagt de locatie pas na een korte uitleg bovenaan de lijst, en alleen tijdens gebruik. Een globale locatie (Android 12+) werkt ook; de app legt dan uit waarom een precieze beter is. De locatie gaat nergens heen: de app sorteert en rekent afstanden op de telefoon uit.
- **Melden:** de eerste melding maakt via Supabase een anoniem account aan (supabase-kt); daarna gaan meldingen rechtstreeks naar `place_reports`. De app past een melding meteen op de telefoon toe, en bij het volgende verversen staat hij bij iedereen.
- **Stad-export uit de database:** de export wordt bij elke aanvraag live opgebouwd (zo'n 65 KB voor Utrecht) in plaats van als bestand in Storage. Dat scheelt een exportstap en een dagelijkse taak. Bij veel gebruikers kan er een cache of CDN voor.
- **Kaart:** tijdens de ontwikkeling gebruikt de app de gratis stijl van OpenFreeMap. Kies vóór de launch een tegelprovider.
- **applicationId:** `nl.beandesk.app`. Wil je een ander, verander het dan vóór de eerste release in de Play Store.

## Nog te doen voor de MVP

- [x] Eigen locatie: permissie alleen tijdens gebruik (ook globaal), blauwe stip, sorteren op afstand
- [x] Snel melden: anoniem inloggen (supabase-kt) en het meldscherm
- [x] Supabase-project met de zaken van Utrecht en een wekelijkse import
- [ ] Delta-sync: alleen gewijzigde zaken ophalen in plaats van de hele export
- [ ] Detailpagina met openingstijden per dag
- [x] Zoeken op naam, plaats, adres of postcode, met opslag van nieuwe gebieden in de database
- [ ] Overpass: de openbare servers zitten vaak vol. Bij veel gebruikers een eigen Overpass-instantie of een betaalde dienst
- [ ] Tegelprovider kiezen en de kaartstijl instellen
- [ ] CAPTCHA (hCaptcha of Turnstile) bij anoniem inloggen, vóór de launch; tijdens het testen staat CAPTCHA-bescherming in Supabase uit
- [ ] Opruimen van oude anonieme accounts zonder meldingen
- [ ] Privacyverklaring en "account verwijderen" in de app
- [ ] Seeding: 30-50 zaken in een of twee buurten

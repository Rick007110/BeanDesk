<img src="docs/brand/icon-rounded.svg" width="88" alt="BeanDesk-logo">

# BeanDesk

Android-app die laat zien welke koffiezaak, bibliotheek of coworkingruimte in de buurt geschikt is om met je laptop te werken: met wifi, stopcontacten en rust. Het plan staat in [Plan: Laptop-friendly koffie-app (Android)](https://claude.ai/artifact/APAtzUqy63MBkCzR6YKeRk).

## Hoe het in elkaar zit

Het import-script haalt de zaken van een stad uit OpenStreetMap en zet ze in Supabase (Postgres + PostGIS). Daarna schrijft het per stad één JSON-bestand, de stad-export, naar Supabase Storage. De app downloadt dat bestand, bewaart het in Room en zoekt en filtert daarna alleen op de telefoon. De locatie van de gebruiker gaat dus nergens heen, en de app doet geen calls naar Overpass of Google.

| Map | Inhoud |
| --- | --- |
| `app/` | Android-app: Kotlin, Jetpack Compose, MapLibre, Room, Ktor |
| `core/` | Logica zonder Android: openingstijden, filters, afstand, routelink en het inlezen van de stad-export. Kan later naar Kotlin Multiplatform als er een iOS-app komt. |
| `supabase/` | Databaseschema, RLS-regels, triggers en de lokale Supabase-config |
| `importer/` | Python-script voor de OSM-import en de stad-export |
| `.github/workflows/` | CI, de releases met APK en het schema dat de stad-data ververst |
| `docs/brand/` | Logo, kleuren en het script dat alle logobestanden maakt |

## Aan de slag

### Android-app

Nodig: een recente Android Studio en JDK 21.

1. Open de map in Android Studio en start de app op een emulator of telefoon.
2. Een debug-build toont standaard twaalf verzonnen voorbeeldzaken in Utrecht (`app/src/debug/assets/sample/utrecht.json`). Daarmee probeer je de kaart, de lijst, de filters en de routeknop zonder Supabase.
3. Staat je eigen stad-export klaar, zet dan in `~/.gradle/gradle.properties` de URL en zet de voorbeelddata uit. Zo komen ze niet in de repo:
   ```properties
   beandesk.cityExportBaseUrl=https://<project-ref>.supabase.co/storage/v1/object/public/city-exports
   beandesk.useSampleData=false
   ```

De logica in `core` test je zo:

```bash
./gradlew :core:test
```

### Releases

Elke push naar `main` zet een pre-release met een installeerbare APK onder [Releases](https://github.com/Rick007110/BeanDesk/releases); de nieuwste tien blijven staan. Een tag zoals `v0.2.0` levert een gewone release op. Open de APK op je telefoon of sleep hem op een emulator. Elke build heeft een hoger versienummer en dezelfde debug-sleutel (`app/debug.keystore`, niet geheim). Daardoor installeer je hem over de vorige heen, ook over een build uit Android Studio.

Zolang de repository-variabele `BEANDESK_CITY_EXPORT_BASE_URL` leeg is, bevatten de builds de voorbeelddata. Zet je hem op de URL van je stad-export, dan gebruiken ze die.

Voor de Play Store komt er later een release-build met een eigen uploadsleutel; de debug-sleutel is daar niet voor bedoeld.

### Supabase

1. Maak een project aan in een EU-regio.
2. Zet het schema neer: tabellen, RLS, triggers en de publieke bucket `city-exports`.
   ```bash
   supabase link --project-ref <project-ref>
   ```
   ```bash
   supabase db push
   ```
3. Zet in het dashboard onder Authentication het anoniem inloggen aan. In `supabase/config.toml` staat het voor lokaal gebruik al aan.

### Import-script

```bash
cd importer && python -m venv .venv && . .venv/bin/activate && pip install -e '.[test]'
```

Zet `DATABASE_URL` op de connection string van Supabase. Neem de variant "Session pooler": de directe verbinding werkt alleen over IPv6, en GitHub Actions heeft geen IPv6.

```bash
python -m beandesk_import import utrecht
```

```bash
python -m beandesk_import export utrecht --out-dir out --upload
```

Voor `--upload` zijn ook `SUPABASE_URL` en `SUPABASE_SERVICE_ROLE_KEY` nodig. Steden staan in `importer/cities.toml`.

De tests draaien met `pytest`. De databasetests hebben `BEANDESK_TEST_DATABASE_URL` nodig, met een PostGIS-database die weg mag: ze gooien de schema's leeg en zetten de migraties opnieuw neer.

### Stad-data automatisch verversen

De workflow "Stad-data" maakt elke dag een nieuwe export, zodat meldingen van gebruikers in de app komen. Op maandag importeert hij eerst opnieuw uit OSM. Zet hiervoor de repository-secrets `SUPABASE_DB_URL`, `SUPABASE_URL` en `SUPABASE_SERVICE_ROLE_KEY`. Welke steden meedoen, staat in `CITIES` in de workflow.

## Stad-export

Versie 1 bevat per stad de tijdzone, een middelpunt voor de kaart, de OSM-attributie en per zaak:

- naam, categorie, adres en coördinaten;
- de ruwe `opening_hours`;
- `osm`: wifi, gratis wifi en stopcontacten volgens OSM;
- `community`: de laatste meldingen van gebruikers.

`core/.../export/CityExport.kt` leest het formaat, `importer/beandesk_import/export.py` schrijft het. Een test in `importer/tests/test_export.py` vergelijkt de uitvoer met het voorbeeld waarop de Kotlin-tests draaien, zodat beide kanten gelijk blijven.

## Keuzes die afwijken van het plan of het aanvullen

- **Openingstijden:** OpeningHoursParser kan alleen parsen, niet bepalen of een zaak open is. Daarom staat er een eigen evaluator in `core`, zonder Java-afhankelijkheid. Die snapt de gangbare vormen (dagen, tijden, over middernacht, `off`, `24/7`) en negeert feestdagen. Alles daarbuiten toont de app als "onbekend".
- **Namen:** de tabellen heten `places` en `place_reports` in plaats van `cafes`, omdat ook bibliotheken en coworkingruimtes meedoen. Kolomnamen zijn Engels.
- **Meldingen:** wat gebruikers melden staat in `place_community`, los van de OSM-data, en wordt door een trigger bijgewerkt. Nu wint de laatste melding; in fase 2 komt de gewogen laptop-score.
- **Misbruik:** gebruikers kunnen `trust_score`, `user_id` en `reported_at` niet zelf zetten. Er geldt een daglimiet: 20 meldingen voor anonieme accounts en 60 voor gewone. Dat zijn startwaarden.
- **Kaart:** tijdens de ontwikkeling gebruikt de app de gratis stijl van OpenFreeMap. Kies vóór de launch een tegelprovider.
- **applicationId:** `nl.beandesk.app`. Wil je een ander, verander het dan vóór de eerste release in de Play Store.

## Nog te doen voor de MVP

- [ ] Eigen locatie: permissie alleen tijdens gebruik (ook globaal), blauwe stip, sorteren op afstand
- [ ] Snel melden: anoniem inloggen (supabase-kt) en het meldscherm
- [ ] Delta-sync: alleen wijzigingen sinds `updated_at` ophalen
- [ ] Detailpagina met openingstijden per dag
- [ ] Zoeken op naam of buurt
- [ ] Tegelprovider kiezen en de kaartstijl instellen
- [ ] Privacyverklaring en "account verwijderen" in de app
- [ ] Seeding: 30-50 zaken in een of twee buurten

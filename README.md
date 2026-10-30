<img src="docs/brand/icon-rounded.svg" width="88" alt="BeanDesk-logo">

# BeanDesk

Android-app die laat zien welke koffiezaak, bibliotheek of coworkingruimte in de buurt geschikt is om met je laptop te werken: met wifi, stopcontacten en rust. Het plan staat in [Plan: Laptop-friendly koffie-app (Android)](https://claude.ai/artifact/APAtzUqy63MBkCzR6YKeRk).

## Hoe het in elkaar zit

De backend is het Supabase-project **BeanDesk** (Frankfurt). Alles wat met data gebeurt, staat als functie in de database:

- **Import:** elke nacht maakt de GitHub Action `osm-data.yml` uit de Geofabrik-extract van Nederland een bestand met alle cafés, bibliotheken en coworkings, met de gemeente waarin ze liggen (`importer/beandesk_import/osm_extract.py`). Om 05:23 UTC haalt de database het op met `import_osm_file()` (`pg_cron`, via de `http`-extensie) en past de regels toe: wat geen werkplek is valt af, verdwenen zaken krijgen een `deleted_at`. De gemeente wordt de stad van een plek, als die in de tabel `cities` staat.
- **Stad-export:** `city_export()` bouwt per stad één JSON met alle zichtbare plekken en de laatste meldingen.
- **Zoeken:** de edge function `search` (`supabase/functions/search`) zoekt een plaats, adres of postcode op bij PDOK en geeft de plekken eromheen uit de database. Zie [Zoeken](#zoeken).
- **De app:** haalt die export in één keer op, bewaart hem in Room en filtert en sorteert daarna alleen op de telefoon. Meldingen gaan als anonieme gebruiker rechtstreeks naar `place_reports`.

De locatie van de gebruiker gaat dus nergens heen: bij zoeken verstuurt de app alleen de getypte tekst. De app zelf doet geen calls naar PDOK of Google.

| Map | Inhoud |
| --- | --- |
| `app/` | Android-app: Kotlin, Jetpack Compose, MapLibre, Room, Ktor |
| `core/` | Logica zonder Android: openingstijden, filters, afstand, routelink en het inlezen van de stad-export. Kan later naar Kotlin Multiplatform als er een iOS-app komt. |
| `supabase/` | Migraties (schema, RLS-regels, triggers, import, export, de nachtelijke taken) en de edge function `search` |
| `importer/` | Kleine Python-CLI om die databasefuncties aan te roepen (ook voor seeding), plus de databasetests |
| `docs/seeding/` | Checklist om een buurt te vullen met echte bezoeken |
| `docs/vps.md` | De backend naar de eigen VPS: wat er draait, wat de app aanroept, hoe je het controleert |
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

Een paar migraties zijn met de hand in de SQL-editor van het dashboard gedraaid (`20261030000000`, `20261030160000` en `20261030200000`) en daarna in de migratiegeschiedenis gezet, zodat `supabase db push` ze niet opnieuw probeert. Plak je later weer een migratie met de hand, markeer hem dan met `supabase migration repair --status applied <versie>`.

Anoniem inloggen moet aan staan (Authentication → Sign In / Providers → "Allow anonymous sign-ins"); daarzonder werkt het melden niet. In `supabase/config.toml` staat het voor lokaal gebruik al aan.

Handige SQL in de SQL-editor:

```sql
select public.import_osm_file();                                  -- de laatste extract nu importeren
insert into public.cities (slug, name) values ('breda', 'Breda');  -- een stad met een eigen export; de naam is de gemeente
update public.places set hidden = true where id = 123;             -- een kroeg die als café getagd is verbergen
```

### Python-CLI en databasetests

```bash
cd importer && python -m venv .venv && . .venv/bin/activate && pip install -e '.[test]'
```

Met `DATABASE_URL` (in Supabase de connection string "Session pooler") roep je de databasefuncties aan:

```bash
python -m beandesk_import import-extract nl-places.json
```

```bash
python -m beandesk_import export utrecht --out-dir out
```

`nl-places.json` staat onder de release [osm-nl](https://github.com/Rick007110/BeanDesk/releases/tag/osm-nl); de database haalt hem elke nacht zelf op.

De tests draaien met `pytest` en testen de SQL-functies zelf. Ze hebben `BEANDESK_TEST_DATABASE_URL` nodig, met een PostGIS-database die weg mag: ze gooien de schema's leeg en zetten de migraties opnieuw neer.

## Stad-export

Versie 1 bevat per stad de tijdzone, een middelpunt voor de kaart, de OSM-attributie en per zaak:

- naam, categorie, adres en coördinaten;
- de ruwe `opening_hours`;
- `osm`: wifi, gratis wifi en stopcontacten volgens OSM;
- `community`: de laatste meldingen van gebruikers.

De databasefunctie `city_export()` schrijft het formaat, `core/.../export/CityExport.kt` leest het. Een test in `importer/tests/test_database.py` vergelijkt de uitvoer met het voorbeeld waarop de Kotlin-tests draaien, zodat beide kanten gelijk blijven. De app vraagt hem op via `GET /rest/v1/rpc/city_export?p_city=utrecht`.

## Zoeken

Het zoekveld boven de lijst:

- **Typen** filtert de getoonde plekken direct op naam en adres, op de telefoon. Hoofdletters en accenten maken niet uit.
- **Twee seconden niet typen** (of de zoektoets) zoekt de tekst in heel Nederland, in deze volgorde:
  1. een plaats, straat, adres of postcode, als elk getypt woord daarin voorkomt ("Amersfoort", "biltstraat utrecht"): de zaken in de buurt;
  2. een naam, eventueel met plaats of postcode zoals OpenStreetMap die kent ("Starbucks", "coffeecompany breda"); spaties en leestekens maken niet uit;
  3. een naam met een plaats die OpenStreetMap niet bij de zaak heeft: de zaken met die naam rond die plaats;
  4. anders niets, in plaats van een lijst die niets met de tekst te maken heeft.

  Het veld leegmaken of het chipje met de plaatsnaam brengt je terug naar de stad.

De edge function zoekt de tekst één keer op bij de [PDOK Locatieserver](https://api.pdok.nl/bzk/locatieserver/search/v3_1/ui/) en bewaart het antwoord in `geocode_cache`. Een plaats krijgt een straal van 4 km, een straat 2 km, een adres of postcode 1,5 km. Daarna antwoordt hij met `area_export()` (of `find_by_name()` voor een naam): hetzelfde formaat als de stad-export, dichtstbijzijnde eerst, plus `radius_m` en `search`. Heel Nederland staat al in de database, dus er wordt niets opgehaald uit OpenStreetMap. De app bewaart de laatste tien gezochte gebieden ook op de telefoon.

De tests van de functie draaien met Node (22.18 of nieuwer):

```bash
node --test supabase/functions/search/search.test.ts
```

## Keuzes die afwijken van het plan of het aanvullen

- **Openingstijden:** OpeningHoursParser kan alleen parsen, niet bepalen of een zaak open is. Daarom staat er een eigen evaluator in `core`, zonder Java-afhankelijkheid. Die snapt de gangbare vormen (dagen, tijden, over middernacht, `off`, `24/7`) en negeert feestdagen. Alles daarbuiten toont de app als "onbekend". Op de plek staat de hele week, met vandaag gemarkeerd; noemt de tekst feestdagen of schoolvakanties, dan staat er een opmerking onder. Tijden die de app niet kan lezen, toont hij zoals ze in OpenStreetMap staan.
- **Namen:** de tabellen heten `places` en `place_reports` in plaats van `cafes`, omdat ook bibliotheken en coworkingruimtes meedoen. Kolomnamen zijn Engels.
- **Meldingen:** wat gebruikers melden staat in `place_community`, los van de OSM-data, en wordt door een trigger bijgewerkt. Per feit wint de laatste melding; in fase 2 komt de gewogen laptop-score. Verdwijnt een melding (bijvoorbeeld met een account), dan rekent de trigger de plek opnieuw uit.
- **Drukte per dagdeel:** "rustig" en "druk" tellen ook per dagdeel, in Nederlandse tijd: ochtend tot 12:00, middag tot 17:00, avond, en zaterdag en zondag samen als weekend. Alleen meldingen van de laatste twaalf maanden tellen. Twee op de drie bepalen of een dagdeel rustig of druk is, anders is het wisselend. Het staat in `place_community.quiet_by_daypart`, omdat de export als anon draait en `place_reports` niet mag lezen.
- **Niet recent bevestigd:** is de laatste melding over een plek ouder dan een jaar, dan staat "Niet recent bevestigd" op de kaart in de lijst en op de plek, met de datum.
- **Profiel:** via het poppetje rechtsboven. Daar staan je eigen meldingen en sterren (de database laat je alleen je eigen rijen lezen), weergave (licht, donker of zoals de telefoon), taal, het app-icoon, de privacyverklaring en "account verwijderen". Dat laatste roept `delete_my_account()` aan; meldingen en sterren gaan mee (`on delete cascade`).
- **Taal:** Nederlands en Engels. Standaard volgt de app de taal van de telefoon. Vanaf Android 13 bewaart Android de keuze (en staat hij ook in de Android-instellingen van de app); op oudere versies doet de app dat zelf. De tests zetten de app op Nederlands, ook op de Engelse emulator in CI.
- **Misbruik:** gebruikers kunnen `trust_score`, `user_id` en `reported_at` niet zelf zetten. Er geldt een daglimiet: 20 meldingen voor anonieme accounts en 60 voor gewone. Dat zijn startwaarden.
- **Locatie:** de app vraagt de locatie pas na een korte uitleg bovenaan de lijst, en alleen tijdens gebruik. Een globale locatie (Android 12+) werkt ook; de app legt dan uit waarom een precieze beter is. De locatie gaat nergens heen: de app sorteert en rekent afstanden op de telefoon uit.
- **Melden:** de eerste melding maakt via Supabase een anoniem account aan (supabase-kt); daarna gaan meldingen rechtstreeks naar `place_reports`. De app past een melding meteen op de telefoon toe, en bij het volgende verversen staat hij bij iedereen.
- **Stad-export uit de database:** de export wordt bij elke aanvraag live opgebouwd (zo'n 65 KB voor Utrecht) in plaats van als bestand in Storage. Dat scheelt een exportstap en een dagelijkse taak. Bij veel gebruikers kan er een cache of CDN voor.
- **Kaart:** OpenFreeMap: gratis, zonder sleutel, en commercieel gebruik mag. Bij de donkere weergave de stijl `dark`. Laadt OpenFreeMap niet, dan valt de kaart terug op VersaTiles, een tweede gratis dienst. De adressen staan in `gradle.properties`.
- **Delta-sync:** de app haalt de hele stad de eerste keer en daarna eens per week. Daartussen haalt hij met `city_changes()` alleen de plekken die veranderd zijn sinds de vorige download, plus de ids van plekken die weg zijn. Lukt dat niet (bijvoorbeeld een database zonder die functie), dan haalt hij alsnog de hele stad.
- **Geen Overpass:** heel Nederland komt elke nacht uit de Geofabrik-extract. De openbare Overpass-servers zaten vaak vol (en één blokkeerde Supabase), en een eigen server kost een VPS met gigabytes; de extract maakt beide overbodig. Verbeteringen in OpenStreetMap zijn daardoor hooguit een dag oud.
- **Steden:** een plek hoort bij een stad als hij in die gemeente ligt (de grens uit OpenStreetMap, `admin_level` 8). De stad-export (`city_export()`) is er voor de steden in de tabel `cities`; zoeken werkt overal.
- **Opruimen:** een anoniem account ontstaat bij de eerste melding. Komt die melding nooit aan, dan blijft er een leeg account over; `delete_idle_anonymous_accounts()` verwijdert die na 30 dagen, elke nacht via pg_cron. Meldt zo'n telefoon later toch iets, dan maakt de app een nieuw account.
- **Seeding:** bezoeken uit de checklist gaan via `seed_reports()` de database in als meldingen van één seed-account, met de tijd van het bezoek. Gebruikers kunnen niet als dat account melden.
- **applicationId:** `nl.beandesk.app`. Wil je een ander, verander het dan vóór de eerste release in de Play Store.

## Nog te doen voor de MVP

- [x] Eigen locatie: permissie alleen tijdens gebruik (ook globaal), blauwe stip, sorteren op afstand
- [x] Snel melden: anoniem inloggen (supabase-kt) en het meldscherm
- [x] Supabase-project met de zaken van heel Nederland en een nachtelijke import
- [x] Delta-sync: alleen gewijzigde zaken ophalen in plaats van de hele export
- [x] Detailpagina met openingstijden per dag
- [x] Zoeken op naam, plaats, adres of postcode, met opslag van nieuwe gebieden in de database
- [x] Overpass: geschrapt; heel Nederland komt elke nacht uit de Geofabrik-extract
- [x] Tegelprovider kiezen en de kaartstijl instellen: OpenFreeMap, licht en donker, met VersaTiles als reserve
- [ ] Backend naar de eigen VPS (zelf-gehoste Supabase), zie [docs/vps.md](docs/vps.md)
- [ ] CAPTCHA bij anoniem inloggen: uitgesteld, want de backend gaat van Supabase naar de eigen VPS; daar opnieuw bekijken
- [x] Opruimen van oude anonieme accounts zonder meldingen
- [x] Privacyverklaring en "account verwijderen" in de app (laat de privacyverklaring vóór de launch nog nakijken en zet er een contactadres in)
- [ ] Seeding: 30-50 zaken in een of twee buurten. De checklist voor Amsterdam Binnenstad en het importcommando staan klaar ([docs/seeding](docs/seeding)); de bezoeken zelf nog doen

## Ideeën voor later

Door Rick gekozen uit voorstellen; nog niet gebouwd.

**Melden**
- [ ] Wifi-snelheid meten bij een melding (download in Mbit/s, tegen onze eigen server)
- [ ] Rust meten: 5 seconden het geluidsniveau meten (alleen het getal in decibel gaat mee)
- [ ] Geschikt om te bellen: vraag bij melden en een filter "Bellen kan"
- [ ] Laptopregels: tijdslimiet, minimale besteding, laptopvrije tafels of uren
- [ ] Toilet en eten: vragen bij melden en filters
- [ ] Prijs van een cappuccino, en filteren op goedkoop
- [ ] Openingstijden corrigeren: de gemelde tijden gaan voor die uit OpenStreetMap
- [ ] Met een groep: grote tafels melden, en een filter "Voor groepen"
- [ ] Melden zonder internet: de melding wacht en gaat vanzelf als je weer online bent
- [ ] Nieuwe plek voorstellen (naam, plek op de kaart, soort), zichtbaar na controle

**Vinden**
- [ ] Filters "Open na 18:00" en "Open op zondag"
- [ ] Sorteren op afstand, sterren of langst open
- [ ] Reistijd in de lijst ("6 min fietsen · 15 min lopen"), uit de afstand
- [ ] Recente zoekopdrachten onder de zoekbalk
- [ ] Startpunt: de stad waar je bent, in plaats van altijd Utrecht; een eigen stad kiezen kan ook
- [ ] Rustiger kaart: gesloten plekken grijs, markers samengevoegd bij uitzoomen

**Erbij**
- [ ] Plek delen (naam, adres en kaartlink) via WhatsApp, Signal en dergelijke
- [ ] Widget: dichtstbijzijnde open plek met wifi
- [ ] Sluitingswaarschuwing: na "Ik werk hier" een melding 30 minuten voor sluitingstijd
- [ ] Bijdrage-teller in je profiel: hoeveel plekken je bijwerkte
- [ ] Snelkoppelingen op het app-icoon: "Dichtstbij open" en "Zoeken"

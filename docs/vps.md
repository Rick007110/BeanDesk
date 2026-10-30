# BeanDesk op de eigen VPS

Dit document is voor Rick en voor de Claude Code op de VPS. Het beschrijft hoe de backend van
BeanDesk van Supabase (cloud) naar de eigen VPS gaat, wat de app van die backend verwacht, en hoe
je controleert dat alles werkt.

## Wat er op de VPS komt

**Zelf-gehoste Supabase** in Docker, met daarvoor **Caddy** voor HTTPS.

Waarom niet alleen een losse Postgres: de app praat met de backend via supabase-kt. De database
leunt op wat Supabase eromheen levert:

- de rollen `anon`, `authenticated` en `service_role`, en `auth.uid()` in de RLS-regels;
- anoniem inloggen;
- de REST-API die databasefuncties als `/rest/v1/rpc/...` aanbiedt;
- een edge function voor het zoeken.

Zelf-gehoste Supabase levert precies die onderdelen. Dan veranderen in de app alleen het adres en
de anon key, en draaien de migraties ongewijzigd.

| Onderdeel | Wat het doet voor BeanDesk |
| --- | --- |
| Postgres (image `supabase/postgres`) | De database; heeft PostGIS, pg_cron en de `http`-extensie die de migraties gebruiken |
| PostgREST | `/rest/v1`: de export, meldingen en sterren |
| GoTrue (Auth) | `/auth/v1`: anoniem inloggen en tokens verversen |
| Edge runtime | `/functions/v1/search`: zoeken op plaats, adres of naam |
| Kong | Eén ingang (poort 8000) voor al het bovenstaande |
| Studio | Het dashboard; niet open naar internet |
| Caddy | HTTPS met een automatisch certificaat, naar Kong |

De bestaande Postgres op de VPS is hier niet voor nodig: Supabase brengt een eigen
Postgres-container mee. Bezet die bestaande Postgres poort 5432 op de host, zet de Supabase-poorten
dan op andere nummers en alleen op `127.0.0.1`.

## Nodig vooraf (Rick)

- **Een (sub)domein** voor de API, zoals `api.beandesk.nl`, met een A-record naar het IP van de
  VPS. Android en de app willen HTTPS. Zonder domein kan `<ip-met-streepjes>.sslip.io` ook, maar
  een eigen naam is netter en blijft hetzelfde als de VPS ooit verandert.
- **Ruimte op de VPS:** de Supabase-containers gebruiken samen zo'n 2 à 3 GB geheugen; 4 GB of meer
  is prettig. Schijf: een paar GB voor de images, de database zelf is klein (ruim 7.000 plekken).
- **Waar de VPS staat** (aanbieder en land): dat komt in de privacyverklaring van de app.

## Stappen (Claude Code op de VPS)

Werk met de officiële handleiding: <https://supabase.com/docs/guides/self-hosting/docker>. De namen
van instellingen hieronder zijn die van de `docker/.env.example` in de Supabase-repository; kijk na
of ze in de versie die je neemt nog zo heten.

1. **Docker** met de compose-plugin.
2. **Supabase neerzetten** in `/opt/beandesk` (de map `docker/` uit
   <https://github.com/supabase/supabase>), met een eigen `.env`:
   - Nieuwe geheimen: `POSTGRES_PASSWORD`, `JWT_SECRET` (minstens 32 tekens), en daarmee
     ondertekend `ANON_KEY` en `SERVICE_ROLE_KEY` (de handleiding legt uit hoe), plus
     `DASHBOARD_USERNAME` en `DASHBOARD_PASSWORD`.
   - `API_EXTERNAL_URL` en `SUPABASE_PUBLIC_URL`: `https://<het domein>`.
   - Anoniem inloggen aan: `ENABLE_ANONYMOUS_USERS=true`. E-mail en telefoon zijn nog niet nodig:
     `ENABLE_EMAIL_SIGNUP=false`, `ENABLE_PHONE_SIGNUP=false`.
   - Tokens een uur geldig (`JWT_EXPIRY=3600`), zoals nu.
   - Hoogstens 30 anonieme aanmeldingen per uur per IP-adres, zoals nu
     (GoTrue: `GOTRUE_RATE_LIMIT_ANONYMOUS_USERS=30`).
   - De database- en poolerpoorten alleen op `127.0.0.1`, en niet op 5432 als die bezet is.
3. **Caddy** voor `https://<het domein>` naar `127.0.0.1:8000` (Kong). De firewall laat alleen 22,
   80 en 443 door. Studio gaat ook via Kong, met de dashboard-login; open het liefst alleen via een
   SSH-tunnel.
4. **De migraties** uit deze repository, in volgorde, met de Supabase CLI vanuit een clone van
   `main`:

   ```bash
   supabase db push --db-url "postgresql://postgres:<POSTGRES_PASSWORD>@127.0.0.1:<poort>/postgres"
   ```

   Daarna moeten de extensies `postgis`, `pg_cron` en `http` er zijn, en deze geplande taken
   (`select jobname, schedule from cron.job`):
   - `beandesk-import-netherlands`, `23 5 * * *`: elke nacht de plekken van heel Nederland;
   - `beandesk-delete-idle-anonymous-accounts`, `43 4 * * *`: lege anonieme accounts na 30 dagen weg.
5. **De plekken** één keer meteen inladen; daarna gaat het elke nacht vanzelf:

   ```sql
   select public.import_osm_file();
   ```

   Dat haalt `nl-places.json` op van de GitHub-release `osm-nl` en duurt ongeveer een minuut. Erna
   staan er ruim 7.000 plekken in `public.places`.
6. **De zoekfunctie:** zet `supabase/functions/search/` uit deze repository (`index.ts`,
   `handler.ts`, `lib.ts`) in `volumes/functions/search/` en herstart de functions-container. Die
   krijgt `SUPABASE_URL` en `SUPABASE_SERVICE_ROLE_KEY` al van de compose-configuratie.
7. **Back-ups:** elke nacht `pg_dump -Fc` van de database `postgres` naar bijvoorbeeld
   `/var/backups/beandesk`, de laatste 14 bewaren, en liefst ook een kopie buiten de VPS.

## Wat de app aanroept

Elke aanroep heeft de header `apikey: <ANON_KEY>`. `Authorization: Bearer ...` bevat de anon key,
of het token van de gebruiker na inloggen.

| Wat | Aanroep | Als |
| --- | --- | --- |
| Stad downloaden | `GET /rest/v1/rpc/city_export?p_city=utrecht` | anon |
| Alleen wijzigingen | `GET /rest/v1/rpc/city_changes?p_city=utrecht&p_since=<generated_at>` | anon |
| Zoeken | `POST /functions/v1/search` met `{"q": "amersfoort"}` | anon |
| Anoniem inloggen | `POST /auth/v1/signup` met `{}` (supabase-kt `signInAnonymously`), daarna token verversen | anon |
| Melden | `POST /rest/v1/place_reports` | gebruiker |
| Sterren | `POST /rest/v1/place_ratings?on_conflict=place_id,user_id` (upsert) | gebruiker |
| Eigen meldingen | `GET /rest/v1/place_reports?select=place_id,reported_at,...,places(name)` en hetzelfde voor `place_ratings` | gebruiker |
| Account verwijderen | `POST /rest/v1/rpc/delete_my_account` | gebruiker |

## Controleren

Met `URL=https://<het domein>` en `KEY=<ANON_KEY>`:

```bash
curl -s "$URL/rest/v1/rpc/city_export?p_city=utrecht" -H "apikey: $KEY" -H "Authorization: Bearer $KEY" | head -c 300
```

```bash
curl -s "$URL/rest/v1/rpc/city_changes?p_city=utrecht&p_since=$(date -u +%Y-%m-%dT%H:%M:%SZ)" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
```

```bash
curl -s -X POST "$URL/functions/v1/search" -H "apikey: $KEY" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d '{"q": "amersfoort"}' | head -c 300
```

```bash
curl -s -X POST "$URL/auth/v1/signup" -H "apikey: $KEY" -H "Content-Type: application/json" -d '{}'
```

Wat er uit moet komen:

- De stad-export begint met `{"version": 1, "city": "utrecht", ...}` en heeft plekken.
- `city_changes` geeft `"places": []` of een paar plekken, en `"removed": []`.
- Zoeken geeft plekken rond Amersfoort met `"search": {"kind": "woonplaats", ...}`.
- Aanmelden geeft een `access_token` met `"is_anonymous": true`. Met dat token als Bearer moet
  een melding lukken:

  ```bash
  curl -s -X POST "$URL/rest/v1/place_reports" -H "apikey: $KEY" -H "Authorization: Bearer <access_token>" -H "Content-Type: application/json" -d '{"place_id": <een id uit de export>, "wifi": "fast"}'
  ```

  Daarna moet `POST /rest/v1/rpc/delete_my_account` met hetzelfde token dat account weer
  verwijderen.

## Terug naar Rick

Alleen deze twee, die in de app komen:

- de API-URL (`https://<het domein>`);
- de `ANON_KEY` (die is openbaar en hoort in de app).

Nooit delen of in de repository zetten: `SERVICE_ROLE_KEY`, `JWT_SECRET`, `POSTGRES_PASSWORD` en
de dashboard-login.

## De gegevens van nu

In Supabase cloud staan alleen testgegevens: één anoniem account (Ricks telefoon), 7 meldingen en
2 sterren. De plekken komen elke nacht opnieuw uit de import. Daarom begint de VPS leeg en vult
de import hem; er hoeft niets over.

## Daarna, in de app

- `beandesk.supabaseUrl` en `beandesk.supabaseAnonKey` in `gradle.properties` naar de VPS.
- In de privacyverklaring "Supabase (in Frankfurt)" vervangen door de aanbieder en het land van de
  VPS.
- Een nieuwe release. Oudere builds praten nog met Supabase cloud; laat dat project draaien tot de
  nieuwe build op de telefoon staat, en pauzeer het dan.

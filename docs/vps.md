# BeanDesk op de eigen VPS

Dit document is voor Rick en voor de Claude Code op de VPS. Het beschrijft hoe de backend van
BeanDesk op de eigen VPS komt, wat de app ervan verwacht, en hoe je controleert dat alles werkt.

## Wat er draait

| Onderdeel | Waar | Wat het doet |
| --- | --- | --- |
| Postgres met PostGIS | al op de VPS, database `beandesk` | Alle plekken, meldingen en accounts. Alle regels staan als functies in de database (`database/migrations`). |
| De API (`backend/`, Node) | Docker, alleen op `127.0.0.1:8080` | Wat de app aanroept (zie onder). Doet ook de nachtelijke taken. |
| Caddy | Docker, poorten 80 en 443 | HTTPS voor `<naam>.duckdns.org`, met een gratis certificaat van Let's Encrypt, door naar de API. |
| DuckDNS-cron | crontab van de VPS | Houdt `<naam>.duckdns.org` op het IP-adres van de VPS. |

De API doet elke nacht zelf twee dingen:
- **04:43 UTC:** anonieme accounts die 30 dagen niets meldden, verwijderen.
- **05:23 UTC:** heel Nederland importeren uit `nl-places.json`. Dat bestand maakt de GitHub
  Action `osm-data.yml` elke nacht om 02:23.

Is de database bij de start nog leeg, dan importeert hij meteen.

Postgres en de API-poort zijn niet bereikbaar van buiten; alleen Caddy is dat.

## Nodig vooraf

- **Rick:** een DuckDNS-subdomein (duckdns.org, inloggen met GitHub of Google) en het token dat
  DuckDNS daarbij toont. Het subdomein wijst naar het **IPv4-adres van de VPS**. Vul IPv6 alleen
  in als de VPS een werkend IPv6-adres heeft; anders leeg laten.
- **Op de VPS:**
  - Debian of Ubuntu, met de Postgres die er al is (versie 14 of nieuwer);
  - Docker met de compose-plugin;
  - git;
  - poorten 80 en 443 open voor iedereen.

## Stappen

1. **DuckDNS bijhouden.** In de crontab van root, met de naam en het token van Rick:

   ```
   */5 * * * * curl -fsS "https://www.duckdns.org/update?domains=<naam>&token=<token>&ip=" -o /dev/null
   ```

   Heeft de VPS een werkend IPv6-adres, zet dan `&ipv6=<dat adres>` achter de URL.

2. **PostGIS** voor de Postgres die er is (kijk de versie na met `psql --version`):

   ```bash
   sudo apt install postgresql-<versie>-postgis-3
   ```

3. **De repository** in een map die de gebruiker `postgres` kan lezen:

   ```bash
   sudo git clone https://github.com/Rick007110/BeanDesk /opt/beandesk
   ```

   Zolang de backend nog niet in `main` staat, gebruik je de branch van de pull request: voeg
   `-b claude/intelligent-lovelace-ecumh8` toe.

4. **De database.** Bedenk een sterk wachtwoord voor de API-gebruiker en draai dan:

   ```bash
   sudo -u postgres env API_PASSWORD='<wachtwoord>' bash /opt/beandesk/deploy/migrate.sh
   ```

   Dat doet het volgende:
   - het maakt de database `beandesk` en de rollen `anon`, `authenticated` en `service_role`;
   - het zet alle migraties neer, in volgorde, en houdt bij welke er al zijn;
   - het maakt de gebruiker `beandesk_api`, die alleen naar die drie rollen mag wisselen.

   Opnieuw draaien kan altijd; dan doet hij alleen wat nieuw is. Draait Postgres niet op 5432,
   zet dan `PGPORT=<poort>` erbij.

   De API logt in via `127.0.0.1` met een wachtwoord. Op Debian en Ubuntu mag dat standaard
   (`scram-sha-256` in `pg_hba.conf`).

5. **De instellingen:**

   ```bash
   cd /opt/beandesk/deploy && sudo cp .env.example .env && sudo chmod 600 .env
   ```

   Vul in `.env` in:
   - `DOMAIN`: de DuckDNS-naam.
   - `DATABASE_URL`: met het wachtwoord van stap 4 (en de poort, als die anders is).
   - `JWT_SECRET`: de uitvoer van `openssl rand -hex 32`. Verander dit later niet, want dan raken
     alle telefoons hun account kwijt.

6. **Starten:**

   ```bash
   cd /opt/beandesk/deploy && sudo docker compose up -d --build
   ```

   In `sudo docker compose logs -f api` zie je bij de eerste start:
   - `first import {"inserted": 7154, ...}`: ruim 7.000 plekken;
   - Caddy die het certificaat ophaalt.

7. **Firewall:** alleen 22, 80 en 443 open. Postgres (5432) en de API (8080) blijven dicht van
   buiten. De API luistert alleen op `127.0.0.1`; laat Postgres ook alleen op localhost luisteren,
   of zet hem in de firewall dicht.

8. **Back-ups**, elke nacht. Bijvoorbeeld in de crontab van `postgres`:

   ```
   15 3 * * * pg_dump -Fc beandesk > /var/backups/beandesk/beandesk-$(date +\%F).dump && find /var/backups/beandesk -name '*.dump' -mtime +14 -delete
   ```

   Zet de back-ups liefst ook ergens buiten de VPS.

## Bijwerken

```bash
cd /opt/beandesk && sudo git pull
```

```bash
sudo -u postgres bash /opt/beandesk/deploy/migrate.sh
```

```bash
cd /opt/beandesk/deploy && sudo docker compose up -d --build
```

## Controleren

Met `URL=https://<naam>.duckdns.org`:

```bash
curl -s "$URL/health"
```

```bash
curl -s "$URL/v1/cities/utrecht" | head -c 300
```

```bash
curl -s -X POST "$URL/v1/search" -H "Content-Type: application/json" -d '{"q": "amersfoort"}' | head -c 300
```

```bash
TOKEN=$(curl -s -X POST "$URL/v1/accounts" | sed 's/.*"token":"\([^"]*\)".*/\1/')
```

```bash
curl -s -X POST "$URL/v1/reports" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"place_id": <een id uit de stad-export>, "wifi": "fast"}'
```

```bash
curl -s "$URL/v1/me/contributions" -H "Authorization: Bearer $TOKEN"
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "$URL/v1/me" -H "Authorization: Bearer $TOKEN"
```

Wat er uit moet komen:

- `/health` geeft `{"ok":true}`.
- De stad-export begint met `{"version": 1, "city": "utrecht", ...}` en heeft zo'n 190 plekken.
- Zoeken geeft plekken rond Amersfoort, met `"search": {"kind": "woonplaats", ...}`.
- Het account geeft een token.
- De melding geeft `{"ok":true}` en staat daarna in `contributions`.
- Verwijderen geeft `204`.

## Wat de app aanroept

| Wat | Aanroep |
| --- | --- |
| Stad downloaden | `GET /v1/cities/utrecht` |
| Alleen wijzigingen | `GET /v1/cities/utrecht/changes?since=<generated_at van de vorige download>` |
| Zoeken | `POST /v1/search` met `{"q": "amersfoort"}` |
| Anoniem account, bij de eerste melding | `POST /v1/accounts`, geeft `{"token": ...}` |
| Melden en sterren | `POST /v1/reports` met `{"place_id": 1, "wifi": "fast", "stars": 4, ...}` |
| Eigen meldingen | `GET /v1/me/contributions` |
| Account verwijderen | `DELETE /v1/me` |

De laatste drie hebben `Authorization: Bearer <token>` nodig.

Er zijn limieten per IP-adres:
- 300 aanvragen per minuut;
- 30 nieuwe accounts per uur;
- 60 zoekopdrachten per minuut.

Daarnaast heeft elk account een daglimiet voor meldingen, in de database.

## Terug naar Rick

Alleen het adres, `https://<naam>.duckdns.org`; dat komt in de app.

Nooit delen of in de repository zetten:
- `JWT_SECRET`;
- het wachtwoord van de database;
- het DuckDNS-token.

## Seeding

De bezoeken uit `docs/seeding` voer je op de VPS in met de importer (Python 3), als de gebruiker
`postgres`:

```bash
cd /opt/beandesk/importer && sudo python3 -m venv /opt/beandesk-venv && sudo /opt/beandesk-venv/bin/pip install -e .
```

```bash
sudo -u postgres env DATABASE_URL=postgresql:///beandesk /opt/beandesk-venv/bin/python -m beandesk_import seed-import /opt/beandesk/docs/seeding/amsterdam-binnenstad.csv --dry-run
```

## Later, vóór de Play Store

- **Een eigen domein:** de DuckDNS-naam zit vast in de app. Een domein van een paar euro per jaar
  is in eigen hand; wissel vóór de eerste openbare release.
- **Twee webpagina's** die de Play Store vraagt: de privacyverklaring, en hoe je je account
  verwijdert. Caddy kan die op hetzelfde adres tonen.

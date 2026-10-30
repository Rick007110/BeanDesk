# Eigen Overpass-server

De zoekfunctie haalt nieuwe gebieden uit OpenStreetMap via Overpass. De openbare servers zitten
vaak vol; deze map zet een eigen server neer met alleen Nederland. De zoekfunctie vraagt die
eerst en valt terug op de openbare servers als hij niet antwoordt.

Wat er draait, in Docker:

- **Overpass** ([wiktorn/overpass-api](https://hub.docker.com/r/wiktorn/overpass-api)) met de
  extract van Nederland van Geofabrik, elk uur bijgewerkt met hun dagelijkse wijzigingen.
- **Caddy** ervoor. Die laat alleen verzoeken met onze sleutel (`X-BeanDesk-Key`) door, en regelt
  HTTPS. Zonder eigen domein gebruikt hij `<ip>.sslip.io`, een naam die naar je IP-adres wijst.
  Zijn poort 80 of 443 al bezet, dan wordt het gewoon HTTP op poort 8090.

Nodig: een Linux-VPS (Debian of Ubuntu) met minstens 2 GB geheugen (4 GB is beter) en 30 GB vrije
schijf. Wat er al op draait, zoals Postgres, blijft ongemoeid: alles staat in
`/opt/beandesk-overpass` en in twee containers.

## Installeren

Op de VPS:

```bash
curl -fsSL https://raw.githubusercontent.com/Rick007110/BeanDesk/main/infra/overpass/install.sh | sudo bash
```

Het script installeert Docker als dat er nog niet is, maakt een sleutel en start alles. Daarna
haalt Overpass Nederland op en bouwt zijn database; dat duurt een à twee uur. Volgen kan met:

```bash
docker compose -f /opt/beandesk-overpass/docker-compose.yml logs -f overpass
```

Als het klaar is, controleer je hem met:

```bash
bash /opt/beandesk-overpass/check.sh
```

## Koppelen aan Supabase

Het script toont aan het eind `OVERPASS_URL` en `OVERPASS_KEY`; ze staan ook in
`/opt/beandesk-overpass/.env`. Zet ze als secrets bij de edge functions (Supabase-dashboard →
Edge Functions → Secrets), of:

```bash
supabase secrets set OVERPASS_URL=... OVERPASS_KEY=...
```

De zoekfunctie gebruikt ze zodra hij opnieuw start, binnen enkele minuten; opnieuw deployen hoeft niet.

## Beheer

- Opnieuw draaien van `install.sh` werkt de configuratie bij en houdt sleutel en database.
- Stoppen: `docker compose -f /opt/beandesk-overpass/docker-compose.yml down`. Met `-v` erachter
  gaat ook de database weg.

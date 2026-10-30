# Seeding

Vóór de launch vul je een buurt met echte gegevens, zodat de app daar niet leeg is. Je gaat langs
30 tot 50 plekken en schrijft op wat je ziet; die bezoeken gaan als meldingen de database in, van
een apart seed-account, met de tijd van je bezoek.

## De checklist

[`amsterdam-binnenstad.csv`](amsterdam-binnenstad.csv) heeft 50 plekken binnen de Singelgracht:
eerst bibliotheken en coworkings, dan cafés die volgens OpenStreetMap wifi hebben, dan cafés met
openingstijden. De kolom `kaart` linkt naar de plek op OpenStreetMap. Niet elke plek is een
werkplek (een sportcafé, een vakbibliotheek): sla die gewoon over.

Opnieuw maken, of voor een andere buurt (zie `AREAS` in `importer/beandesk_import/seeding.py`):

```bash
python -m beandesk_import seed-checklist amsterdam-binnenstad --out docs/seeding/amsterdam-binnenstad.csv
```

## Invullen

Per bezochte plek vul je `bezocht` in, plus wat je zag. Lege velden tellen niet mee; een rij
zonder `bezocht` wordt overgeslagen.

| Kolom | Wat je invult |
| --- | --- |
| `bezocht` | datum en tijd van je bezoek, Nederlandse tijd: `2026-11-02 10:30` |
| `wifi` | `geen`, `ja`, `traag` of `snel` |
| `stopcontacten` | `geen`, `weinig` of `veel` |
| `rustig` | `ja` of `nee` (ook goed: `rustig`, `druk`) |
| `laptop` | `welkom`, `niet in het weekend` of `niet toegestaan` |
| `gesloten` | `ja` als de zaak er niet meer is |
| `opmerking` | voor jezelf; gaat niet mee |

De tijd van je bezoek telt mee voor "drukte per dagdeel" en "laatst bevestigd", dus vul de echte
tijd in.

## Invoeren

Eerst controleren; dit verandert niets:

```bash
python -m beandesk_import seed-import docs/seeding/amsterdam-binnenstad.csv --dry-run
```

Klopt het, dan zonder `--dry-run`. Daarvoor is `DATABASE_URL` nodig (de connection string van de
database). Bij een fout in het bestand gaat er niets in en zegt het commando welke regel het is.

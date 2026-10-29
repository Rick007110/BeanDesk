<img src="icon-rounded.svg" width="120" alt="BeanDesk-logo">

# Logo

De koffieboon is de stip van een wifi-symbool: koffie en een verbinding, precies wat de app helpt vinden.

## Varianten

Gebruikers kiezen in de app zelf hun app-icoon (palet-knop rechtsboven). Espresso is de standaard en ook het icoon in de Play Store.

| | Variant | Achtergrond (verloop) | Boon | Golven |
| --- | --- | --- | --- | --- |
| <img src="variants/icon-espresso.svg" width="48"> | Espresso | `#4A2C1A` → `#1E120B` | `#F2A65A` | `#FFE6C4` |
| <img src="variants/icon-terracotta.svg" width="48"> | Terracotta | `#F07A45` → `#C9472A` | `#2B1A12` | `#FFF1DD` |
| <img src="variants/icon-crema.svg" width="48"> | Crema | `#FFF1DC` → `#F4CF9E` | `#6F4E37` | `#3A2416` binnen, `#C8743A` buiten |
| <img src="variants/icon-teal.svg" width="48"> | Teal | `#16615D` → `#0B3534` | `#F2A65A` | `#FFE6C4` |
| <img src="variants/icon-night.svg" width="48"> | Nacht | `#2B2F4A` → `#14162A` | `#F2A65A` | `#FFE6C4` binnen, `#F2A65A` buiten |

## Bestanden

| Bestand | Waarvoor |
| --- | --- |
| `icon.svg` | Vierkant icoon (espresso) zonder afgeronde hoeken, zoals stores het willen |
| `icon-rounded.svg` | Afgeronde tegel (espresso), voor de README, sites en presentaties |
| `variants/icon-*.svg` | Alle varianten als afgeronde tegel |
| `mark.svg` | Alleen het beeldmerk, voor een lichte achtergrond |
| `play-store-icon-512.png` | Het icoon voor de Play Store (512 × 512, Google rondt zelf af) |

In de app staan per variant een adaptief launcher-icoon en een tegel voor in de app (`logo_badge_*`). Daarnaast is er één versie in één kleur voor thema-iconen op Android 13+.

## Hoe het wisselen werkt

Elke variant is een `<activity-alias>` in `AndroidManifest.xml` met een eigen icoon; er staat er steeds precies één aan. `AppIconManager` zet de gekozen alias aan en de rest uit, zodra de app naar de achtergrond gaat. Doet hij dat terwijl de app open is, dan kan Android de app sluiten. Launchers nemen het nieuwe icoon na een paar seconden over. Op sommige telefoons verdwijnt een snelkoppeling op het startscherm, die je dan opnieuw neerzet.

Een nieuwe variant toevoegen gaat zo:
1. Voeg hem toe aan `VARIANTS` in `generate.py`.
2. Voeg een alias toe aan het manifest.
3. Voeg een waarde toe aan `AppIcon`.

`AppIconManifestTest` controleert of manifest en enum gelijk blijven.

## Aanpassen

Alle bestanden komen uit `generate.py`, dat de vormen één keer beschrijft. Pas daar kleuren of maten aan en draai:

```bash
python3 docs/brand/generate.py
```

Exporteer daarna `icon.svg` opnieuw naar `play-store-icon-512.png` op 512 × 512, met een SVG-tool naar keuze.

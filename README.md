# Poslední spoj

Android aplikace pro pěší výletníky: na mapě ukazuje u každé zastávky čas posledního spoje,
se kterým se ještě stihneš vrátit do cíle (např. do Prahy) v zadaný čas.

- `app/` – Android aplikace (Kotlin, Jetpack Compose, MapLibre, OpenStreetMap)
- `pipeline/prep.py` – převod jízdních řádů PID (GTFS) na kompaktní data pro aplikaci
- `.github/workflows/data.yml` – každou noc data připraví a uloží do větve `data`

Data jízdních řádů: © ROPID, [otevřená data PID](https://pid.cz/o-systemu/opendata/) (CC BY).
Mapa: © přispěvatelé OpenStreetMap, turistické trasy © waymarkedtrails.org.

# ⚔ RoundDuel – kolový 2v2 až 5v5 deathmatch (jako Valorant)

Dva týmy (max 5 a 5) hrají na kola. Kdo zemře, nerespawnuje se, jen se dívá jako divák
do konce kola. Kolo vyhrává tým, kterému zůstane poslední živý hráč. Zápas vyhrává tým,
který první vyhraje nastavený počet kol (výchozí 5).

Všichni mají **stejnou výzbroj** (jen barva přilby/bot podle týmu): železný meč, luk se šípy,
chainmail zbroj. Žádné enchanty, lektvary ani schopnosti.

Cílová verze: **Paper 26.2**, Java 25.

## Jak kolo funguje

1. **Odpočet kola** (výchozí 5 s) – hráči jsou na spawnu svého týmu, vybaveni, ale nemůžou
   si navzájem ubližovat (přátelská i nepřátelská palba je do konce odpočtu vypnutá).
2. **Boj** – PvP mezi týmy zapnuté, žádné přirozené doléčení (hunger regen vypnutý), žádný
   pád neubírá život. Kdo zemře, jde rovnou do diváka (bez respawnu).
3. Kolo končí, jakmile jeden tým nemá žádného živého hráče. Volitelně i časovým limitem
   (výchozí 90 s) – pak je kolo remíza a hraje se znovu beze změny skóre.
4. Krátká pauza, pak další kolo. Hráči se vždy znovu plně vybaví a vyléčí.

Scoreboard vpravo ukazuje skóre obou týmů a soupisku hráčů – mrtví v aktuálním kole jsou
přeškrtnutí a šedí, živí zelení.

## Sestavení

```
gradle build
```

Výsledek: `build/libs/RoundDuel-1.0.0.jar`. Bez JDK 25 lokálně stačí nahrát na GitHub,
workflow v `.github/workflows/build.yml` jar sestaví sám (záložka Actions → Artifacts).

## Nastavení arény (jednorázově, jako OP)

Pro **oba týmy**:

1. Postav se na místo, kde má tým respawnovat, a přidej spawn (jde přidat víc najednou
   pro rozmanitost): `/roundduel setspawn <red|blue>`
2. Volitelně nastav společnou čekárnu: `/roundduel setlobby`

Stav nastavení zjistíš příkazem `/roundduel arena`. Hráči se pak připojují přes
`/roundduel join`, zápas se sám spustí od 2 hráčů rozdělených do obou týmů. Pro test
sám/ve dvou stačí `/roundduel start`.

## Příkazy

| Příkaz | Kdo | Co dělá |
|---|---|---|
| `/roundduel join` | všichni | připojí se (vyváženě do menšího týmu) |
| `/roundduel leave` | všichni | odejde ze hry |
| `/roundduel status` | všichni | stav hry a tvůj tým |
| `/roundduel setlobby` | OP | nastaví čekárnu |
| `/roundduel setspawn [tým]` | OP | přidá spawn týmu |
| `/roundduel clearspawns [tým]` | OP | smaže spawny týmu |
| `/roundduel arena` | OP | stav nastavení arény |
| `/roundduel start` | OP | vynutí start i s menším počtem hráčů |
| `/roundduel stop` | OP | zruší hru |

Aliasy: `/rd`, `/duel`. Oprávnění: `roundduel.play` (default všichni), `roundduel.admin` (default OP).

Počet hráčů na tým, počet kol na výhru, délku odpočtů a časový limit kola nastavíš
v `plugins/RoundDuel/config.yml` (po změně je potřeba restart serveru).

## Co vědět předem

Nebyl jsem schopný si tento plugin v tomhle prostředí zkompilovat ani odzkoušet na skutečném
serveru (chybí tu stažené Paper API) – ověřil jsem jen, že je syntaxe v pořádku. Při prvním
buildu nebo testu ve hře se tak může objevit drobná chyba – pošli mi ji a opravím to.

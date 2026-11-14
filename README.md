# Salamander Droid

Dvoupanelový správce souborů pro Android (Kotlin, Jetpack Compose) inspirovaný
[Open Salamanderem](https://github.com/OpenSalamander/salamander). Je to sesterský projekt
[Salamander RS](https://github.com/tondakrap-cmd/SalamanderRS) pro Linux: stejné ovládání
z klávesnice, stejné zkratky a stejné chování všude, kde to na Androidu dává smysl.

Cílí na tablety s hardwarovou klávesnicí (Android 11 a novější, vyvíjeno pro Samsung Galaxy Tab
s DeX), ale dá se ovládat i dotykem a na telefonu (zobrazí se jeden panel, přepíná se ⇄).

## Funkce

**Panely**
- sloupce Název, Přípona, Velikost, Změněno, Práva, Vlastník; řazení klikem na záhlaví nebo Ctrl+F3–F6
- informační řádek s položkou pod kurzorem, stavový řádek se součty výběru a volným místem
- automatická obnova při změnách na disku, rychlé hledání psaním (podporuje `*` a `?`)
- historie (Alt+←/→), Backspace vrátí kurzor na adresář, ze kterého jste přišli, volitelná položka `..`
- klávesové cesty Ctrl+Shift+0–9 (uložit) a Ctrl+0–9 (skok), fungují i s českým rozložením
- Alt+F1 / Alt+F2: úložiště (interní paměť, SD karta, USB), běžné složky, koš, klávesové cesty,
  servery a historie

**Soubory**
- kopírování a přesun s řešením konfliktů, průběhem a zrušením (zachovává časy); data se zapisují
  do dočasného souboru a cíl nahradí až po dokončení
- koš (skrytá složka `.SalamanderDroid-Trash` na každém svazku) i trvalé mazání, přejmenování,
  nová složka (i `a/b/c`), nový soubor
- F3 prohlížeč (text/hex, hledání), F4 vestavěný textový editor, Enter otevře soubor v přidružené
  aplikaci, Shift+Enter nabídne výběr aplikace
- výpočet obsazeného místa (Alt+Shift+Enter), hledání podle masky a obsahu (Alt+F7)
- porovnání adresářů (Shift+F2), výběr skupiny maskou (`*.jpg;*.png|tmp*`), invertování výběru
- vlastnosti (Alt+Enter) a atributy (Ctrl+F2): čas změny, na serverech i práva (chmod) rekurzivně

**Archivy**
- Enter na archivu ho otevře jako adresář; čtení i zápis zip, tar, tar.gz/xz/zst/bz2 a 7z,
  jen čtení cpio a ar/deb
- F5 z archivu rozbalí, F5 do archivu přidá, F8 v archivu odstraní, Alt+F5 zabalí, Alt+F9 rozbalí
- ochrana proti „zip slip“: položky s `..` se nerozbalí, symbolické odkazy se nerozbalují

**Vzdálené servery (SSH/SFTP)**
- databáze serverů (Ctrl+K) ve stejném formátu jako Salamander RS; vzdálený adresář se otevře
  v panelu jako `sftp://uživatel@server/cesta`
- F5/F6 stahuje a nahrává (i mezi dvěma servery), F3, F4 (uložení se nahraje zpět), F2, F7,
  Shift+F4, F8, Ctrl+F2
- ověření heslem nebo soukromým klíčem (OpenSSH/PEM, i s heslem ke klíči)
- neznámý klíč serveru je nutné potvrdit (zobrazí se otisk SHA256), změněný klíč se odmítne
- hesla volitelně v šifrovaném trezoru (Argon2id + AES-256-GCM, hlavní heslo, zámek po 15 minutách)
- log vzdálených operací (Vzdálené → Log), nikdy neobsahuje hesla

Úplný přehled kláves je v aplikaci pod F1.

## Rozdíly proti Salamander RS

| Salamander RS (Linux) | Salamander Droid |
|---|---|
| chown, chmod, ACL, xattr, chattr na místních souborech | sdílené úložiště Androidu je nepodporuje; lze měnit čas, na serverech i práva |
| symbolické odkazy, pojmenované roury | nelze vytvářet |
| systémový koš FreeDesktop | vlastní koš ve skryté složce svazku |
| F4 otevře systémový editor | vestavěný textový editor |
| terminál (Ctrl+T) | není |
| SSH přes systémový OpenSSH (`~/.ssh/config`, agent, ProxyJump) | knihovna sshj; bez agenta a ProxyJump |
| archivy přes libarchive (i rar, iso, rpm, cab) | Apache Commons Compress (zip, tar.*, 7z, cpio, ar) |
| trezor XChaCha20-Poly1305 | trezor AES-256-GCM (soubor trezoru není přenositelný) |
| schránka sdílená s jinými správci | schránka souborů jen uvnitř aplikace |

Navíc proti desktopu: dotykové ovládání, lišta tlačítek F2–F8, přenosy mezi dvěma servery.

## Instalace

APK stáhnete z artefaktů sestavení v záložce Actions (nebo si ho sestavíte, viz níže), otevřete ho
v tabletu a povolíte instalaci z neznámých zdrojů. Při prvním spuštění aplikace požádá o oprávnění
**Přístup ke všem souborům** — bez něj správce souborů na Androidu pracovat nemůže.

## Sestavení

Potřebujete JDK 21 a Android SDK (platforma 35, build-tools 35).

```bash
./gradlew testDebugUnitTest      # testy jádra, SFTP proti vestavěnému serveru i UI (Robolectric)
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Sestavení `release` je podepsané ladicím klíčem stroje, na kterém vzniklo. APK z různých strojů
proto nejdou instalovat přes sebe; pro průběžné aktualizace je potřeba vlastní podpisový klíč.

## Stav

Verze 0.1.0 je první funkční verze. Jádro (operace, archivy, SFTP, trezor) i ovládání klávesnicí
pokrývají automatické testy, na skutečném tabletu ale aplikace zatím vyzkoušená nebyla.

## Licence

GPL-3.0-or-later

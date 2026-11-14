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
  cloudové účty, servery a historie
- rychlé zobrazení (Ctrl+P): druhý panel ukazuje náhled souboru pod kurzorem — obrázky, první
  stranu PDF, text nebo hex
- myš a trackpad: pravé tlačítko otevře místní nabídku, Ctrl+klik označí, Shift+klik označí rozsah
- přetažení souborů myší do jiného okna nebo druhého panelu; soubory puštěné z jiné aplikace se
  uloží do panelu
- sdílení: Ctrl+S pošle vybrané soubory jiné aplikaci, „Uložit do Salamandera“ je přijme od jiných
- více oken aplikace (Ctrl+N), například vedle sebe nebo na externím monitoru v DeX

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

**Cloud: OneDrive a Google Drive**
- účty se spravují v dialogu Ctrl+Shift+K; OneDrive osobní i firemní (Microsoft Graph) a Google Drive
- panel ukazuje `onedrive://Název/cesta` nebo `gdrive://Název/cesta`; F5/F6 stahuje a nahrává
  (velké soubory po částech), a to i přímo mezi cloudem, serverem a druhým cloudem
- F3, F4 (uložení se nahraje zpět), F2, F7, Shift+F4, F8; smazané položky jdou do koše služby
- dokumenty Google (Dokumenty, Tabulky, Prezentace) se stahují převedené do formátů Office
- přihlášení probíhá v prohlížeči (OAuth 2 s PKCE), aplikace heslo nevidí; tokeny jsou uložené
  zašifrované klíčem z Android Keystore
- každý účet potřebuje vlastní registraci aplikace, viz [Registrace pro cloud](#registrace-pro-cloud)

**Síťové disky (SMB)**
- sdílené složky Windows a NAS (SMB 2/3) ve správci serverů (Ctrl+K), panel `smb://uživatel@server/sdílení/cesta`
- stejné operace jako u SFTP; heslo volitelně v šifrovaném trezoru

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

Navíc proti desktopu: dotykové ovládání, lišta tlačítek F2–F8, přenosy mezi dvěma servery,
OneDrive, Google Drive, síťové disky SMB, rychlé zobrazení, sdílení a přetahování mezi aplikacemi.

## Registrace pro cloud

Microsoft i Google vyžadují, aby se aplikace přistupující k souborům uživatele ohlásila vlastním
identifikátorem. Registrace je zdarma a dělá se jednou; ID se pak zadá v aplikaci u účtu.

### OneDrive (osobní i firemní)

1. Na <https://entra.microsoft.com> otevřete **App registrations → New registration**.
2. Jako podporované typy účtů zvolte **Accounts in any organizational directory and personal
   Microsoft accounts** (jedna registrace pak slouží osobnímu i firemnímu OneDrive).
3. U **Redirect URI** vyberte platformu **Public client/native (mobile & desktop)** a zadejte
   `http://localhost`.
4. Po vytvoření zkopírujte **Application (client) ID**.
5. V **API permissions** přidejte delegovaná oprávnění Microsoft Graph `Files.ReadWrite.All`,
   `offline_access` a `User.Read`.
6. V aplikaci: Ctrl+Shift+K → Nový… → OneDrive, vložte ID aplikace. Pole Tenant nechte `common`;
   jen u registrace omezené na jednu organizaci do něj patří ID tenantu.

U firemního účtu může organizace vyžadovat schválení aplikace správcem.

### Google Drive

1. Na <https://console.cloud.google.com> založte projekt a v **APIs & Services → Library**
   povolte **Google Drive API**.
2. Nastavte obrazovku souhlasu (OAuth consent screen): typ **External**, a v části **Audience**
   aplikaci zveřejněte (**In production**). Ve stavu Testing platí přihlášení jen 7 dní.
3. V **Clients** vytvořte OAuth klienta typu **Desktop app** a zkopírujte **Client ID**
   a **Client secret**.
4. V aplikaci: Ctrl+Shift+K → Nový… → Google Drive, vložte obě hodnoty.

Při prvním přihlášení Google upozorní, že aplikace není ověřená; pokračuje se přes
**Advanced → Go to …**. Zobrazuje se „Můj disk“; sdílené disky a „Sdíleno se mnou“ zatím ne.

## Instalace

APK stáhnete z [Releases](https://github.com/tondakrap-cmd/SalamanderDroid/releases/latest), otevřete ho
v tabletu a povolíte instalaci z neznámých zdrojů. Při prvním spuštění aplikace požádá o oprávnění
**Přístup ke všem souborům** — bez něj správce souborů na Androidu pracovat nemůže.

## Sestavení

Potřebujete JDK 21 a Android SDK (platforma 35, build-tools 35).

```bash
./gradlew testDebugUnitTest      # testy jádra, SFTP, cloudu a UI (Robolectric); test SMB potřebuje smbd a roota
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Bez podpisového klíče se `release` podepíše ladicím klíčem stroje a APK z různých strojů nejdou
instalovat přes sebe. Stálý klíč se předává proměnnými `SIGNING_KEYSTORE` (cesta k souboru `.jks`,
alias `salamander`) a `SIGNING_PASSWORD`; CI je bere ze secrets `SIGNING_KEYSTORE_BASE64`
a `SIGNING_PASSWORD`.

## Stav

Verze 0.2.0 přidává cloud, síťové disky a ovládání myší. Automatické testy pokrývají jádro
(operace, archivy, trezor), SFTP proti vestavěnému serveru, SMB proti serveru Samba a ovládání
klávesnicí i myší. OneDrive a Google Drive jsou otestované jen proti napodobeninám obou služeb,
se skutečnými účty zatím ne; přetahování mezi okny a více oken nejdou mimo zařízení ověřit vůbec.

## Licence

GPL-3.0-or-later

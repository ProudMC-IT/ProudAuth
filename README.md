# ProudAuth

ProudAuth e una soluzione di autenticazione premium/cracked per network Minecraft in `offline-mode`, con backend Paper, storage MySQL, sessioni persistenti, protezioni pre-login, anti brute-force e supporto TOTP 2FA.

Questa repository ora e organizzata in moduli separati per avere una base ordinata e pronta sia per l'uso standalone sia per il deploy dietro proxy.

Il principio operativo e questo:

- il backend Paper deve poter funzionare bene anche da solo
- il proxy companion deve esporre solo configurazione realmente usata
- nessun file di config deve contenere chiavi decorative o inutilizzate
- il debug va attivato solo quando devi diagnosticare una rete reale

## Architettura del progetto

```text
ProudAuth/
|- common/      -> logica condivisa: auth, storage, sessioni, premium check, sicurezza
|- bootstrap/   -> composizione runtime comune e bootstrap dei provider
|- bukkit/      -> plugin Paper/backend
|- velocity/    -> companion plugin per proxy Velocity
`- universal/   -> packaging finale del jar unico
```

### Cosa distribuisci davvero

Il jar finale da distribuire e uno solo:

- `universal/build/libs/ProudAuth-1.0.0.jar`

I moduli `common`, `bootstrap`, `bukkit` e `velocity` sono sorgenti organizzati per piattaforma.

Il jar che devi consegnare o installare e sempre lo stesso.

## A cosa serve

ProudAuth e pensato per:

- server Paper standalone in `online-mode=false`
- network premium + cracked con backend Paper
- network Velocity che vogliono un companion lato proxy
- owner che vogliono vendere un auth plugin serio, con flusso pulito e documentazione chiara

## Funzionalita principali

- riconoscimento automatico account premium via Mojang API
- supporto account premium e cracked nello stesso ambiente offline-mode
- login automatico premium opzionale
- registrazione e login manuale per cracked
- sessioni persistenti con TTL configurabile
- binding sessione su IP opzionale
- blocco IP dopo troppi tentativi falliti
- supporto TOTP 2FA
- protezione del player fino all'autenticazione
- invisibilita fino al login
- spawn auth dedicato
- comandi admin per forcelogin, reset password, ban IP, stats e reload
- stesso jar compatibile con Paper backend e Velocity proxy
- companion Velocity incluso nello stesso artefatto

## Compatibilita

### Backend

- Paper `1.21.1`
- Java `21`
- MySQL

### Proxy

- Velocity `3.5.x`

### Nota importante

Il backend principale e Paper-only.

Questa build non e destinata a Bukkit o Spigot puro.

Se la devi vendere, dichiaralo in modo netto:

`Backend supportato: Paper 1.21.1`

## Come funziona

### Server standalone Paper

Quando un player entra:

1. ProudAuth controlla se l'IP e bloccato.
2. Se `premium.enabled: true`, verifica lo username con Mojang.
3. Se il player e premium:
   - usa UUID premium
   - puo essere autenticato automaticamente se `premium.auto-login: true`
4. Se il player non e premium:
   - usa UUID offline
   - deve registrarsi o fare login
5. Se esiste una sessione valida:
   - la sessione viene ripristinata
6. Se il player non e autenticato:
   - viene protetto
   - non puo muoversi, parlare o interagire
   - puo essere teletrasportato allo spawn auth

Questo flusso funziona anche senza alcun proxy installato.
In quel caso ProudAuth usa solo il backend e `proxy.mode: NONE`.

### Network Velocity

Con Velocity il flusso consigliato e:

1. Il proxy carica `ProudAuth-1.0.0.jar`.
2. Il backend Paper carica `ProudAuth-1.0.0.jar`.
3. Il companion Velocity prova a risolvere il profilo premium gia sul proxy.
4. Se il proxy sta gia inoltrando un UUID premium, ProudAuth lo usa.
5. Se arriva ancora un offline UUID, ProudAuth esegue fallback alla verifica Mojang.
6. Se il `bridge` ProudAuth e attivo, il proxy salva una assertion breve e firmata su MySQL.
7. Il backend valida quella assertion e puo fidarsi del risultato del proxy.
8. Il backend continua a gestire protezioni, sessioni, `/login`, `/register`, `/2fa` e tutto il flusso auth.

In pratica:

- il proxy companion migliora coerenza e compatibilita del profilo premium
- il bridge opzionale consente un controllo trusted proxy -> backend
- il backend resta il punto in cui avviene l'autenticazione del giocatore

### Network BungeeCord / Waterfall

Questa build non include un companion dedicato per Bungee/Waterfall.

Per quei proxy il supporto resta backend-side:

- installi `ProudAuth-1.0.0.jar` sui backend Paper
- configuri correttamente il forwarding del proxy
- imposti `proxy.mode: BUNGEE` in `config.yml`

Il comportamento auth continua a vivere sul backend.

## Installazione backend Paper

### File da usare

- Jar universale: `ProudAuth-1.0.0.jar`

### Percorsi generati

Al primo avvio il plugin backend crea:

- `plugins/ProudAuth/config.yml`
- `plugins/ProudAuth/lang/it.yml`

### Passaggi

1. Imposta il backend Paper in `online-mode=false` dentro `server.properties`.
2. Copia `ProudAuth-1.0.0.jar` nella cartella `plugins/`.
3. Avvia il server una prima volta.
4. Configura `plugins/ProudAuth/config.yml`.
5. Riavvia il server oppure usa `/proudauth reload`.

### Librerie backend

Sul backend Paper il jar resta leggero perche usa `plugin.yml -> libraries`.

Al primo avvio Paper scarica automaticamente:

- HikariCP
- jBCrypt
- TOTP library
- MySQL Connector/J

Quindi il backend deve avere accesso a internet al primo boot.

## Installazione proxy Velocity

### File da usare

- Jar universale: `ProudAuth-1.0.0.jar`

### Percorsi generati

Il companion Velocity crea:

- `plugins/proudauth/velocity-config.yml`
- `plugins/proudauth/lang/it.yml`
- `plugins/proudauth/runtime-libs/`

### Cosa usa davvero il proxy

Il proxy oggi usa solo:

- `database`
- `premium`
- `bridge`

Nel dettaglio:

- `database` serve per leggere i ban IP attivi condivisi col backend
- `premium` serve per la verifica Mojang e la risoluzione del profilo premium
- `bridge` serve per firmare e pubblicare assertion trusted verso il backend

Il proxy non usa piu:

- `sessions`
- `security`
- `protection`
- `password`
- `auth-spawn`

Questa e una scelta intenzionale:

- il backend mantiene tutta la logica auth completa
- il proxy resta leggero e ha solo le impostazioni che consuma davvero

### Cosa fa il companion Velocity

- controlla subito lock IP al pre-login
- prova a risolvere il profilo premium sul proxy
- se `bridge.enabled: true`, firma e salva assertion trusted su MySQL
- mantiene un fallback pulito quando il backend riceverebbe ancora un offline UUID
- espone `/proudauth reload` lato proxy
- scarica e carica automaticamente le librerie runtime necessarie al primo avvio

### Cosa non fa il companion Velocity

Non sostituisce il backend auth.

I comandi player come:

- `/login`
- `/register`
- `/changepassword`
- `/logout`
- `/2fa`

restano lato backend Paper.

### Bridge proxy -> backend

Il bridge ProudAuth e opzionale e usa MySQL come trasporto.

Quando `bridge.enabled: true` sia sul proxy sia sul backend:

1. Velocity risolve il profilo finale del player.
2. Velocity genera una assertion breve contenente:
   - username canonico
   - nome profilo risolto
   - uuid finale
   - account type
   - IP
   - timestamp
   - scadenza
   - nonce
   - firma HMAC
3. L'assertion viene salvata in MySQL.
4. Il backend la recupera al pre-login.
5. Il backend verifica:
   - username
   - IP
   - UUID atteso
   - firma
   - TTL
6. Se la verifica passa, il backend usa la decisione trusted del proxy.

Modalita disponibili:

- `FALLBACK`: se l'assertion manca o non e valida, il backend torna al controllo classico
- `STRICT`: se l'assertion manca o non e valida, il backend rifiuta il login

Il `bridge.shared-secret` di ProudAuth non e il secret nativo di Velocity forwarding.
Sono due cose diverse e vanno configurate separatamente.

### Setup consigliato Velocity modern forwarding

Secondo la documentazione ufficiale PaperMC:

1. In `velocity.toml` imposta `player-info-forwarding-mode = "modern"`.
2. In `velocity.toml` scegli `online-mode` in base al tuo modello di rete.
3. Imposta il secret reale in `forwarding.secret`.
4. Su ogni backend Paper imposta `server.properties -> online-mode=false`.
5. In `spigot.yml` assicurati che `settings.bungeecord=false`.
6. In `config/paper-global.yml` imposta:
   - `proxies.velocity.enabled=true`
   - `proxies.velocity.secret=<stesso secret>`
   - `proxies.velocity.online-mode=<uguale a velocity.toml>`
7. Installa `ProudAuth-1.0.0.jar` sui backend.
8. Installa `ProudAuth-1.0.0.jar` sul proxy.
9. In `plugins/ProudAuth/config.yml` del backend imposta:

```yaml
proxy:
  mode: VELOCITY
```

### Nota importante sulla modalita `online-mode` di Velocity

Se su Velocity usi `online-mode=true`:

- il proxy autentica gia i premium
- i cracked non passeranno il proxy

Se vuoi una rete mista premium + cracked:

- di solito Velocity deve consentire l'accesso anche a profili non autenticati premium
- ProudAuth poi distingue premium e cracked e gestisce il flusso auth sul backend

Il setup preciso della tua rete dipende dal modello che vuoi vendere o supportare.

### Riassunto pratico

Con una rete Velocity devi usare lo stesso jar in due posti:

- `plugins/` di ogni backend Paper
- `plugins/` del proxy Velocity

Al primo avvio:

- Paper usa il proprio library loader
- Velocity salva le dipendenze in `plugins/proudauth/runtime-libs`

## Database

ProudAuth usa MySQL e crea automaticamente le tabelle necessarie:

- `pa_accounts`
- `pa_sessions`
- `pa_ip_bans`
- `pa_proxy_assertions`

Non devi importare manualmente uno schema SQL separato.

## Comandi backend Paper

### Player

| Comando | Uso | Descrizione |
|---|---|---|
| `/login` | `/login <password>` | Effettua il login |
| `/register` | `/register <password> <conferma>` | Registra l'account |
| `/changepassword` | `/changepassword <vecchia> <nuova> <conferma>` | Cambia password |
| `/logout` | `/logout` | Chiude la sessione |
| `/2fa setup` | `/2fa setup` | Genera secret TOTP |
| `/2fa <codice>` | `/2fa <codice>` | Completa una challenge TOTP pendente |
| `/2fa disable <codice>` | `/2fa disable <codice>` | Disattiva il TOTP |

### Admin

| Comando | Descrizione |
|---|---|
| `/proudauth forcelogin <player>` | Autentica forzatamente un player |
| `/proudauth forcelogout <player>` | Chiude auth e sessione del player |
| `/proudauth resetpassword <player> <nuova>` | Resetta password |
| `/proudauth banip <ip> [secondi]` | Blocca un IP |
| `/proudauth unbanip <ip>` | Sblocca un IP |
| `/proudauth stats` | Mostra statistiche |
| `/proudauth reload` | Ricarica config, lingua e servizi |

## Comandi proxy Velocity

| Comando | Descrizione |
|---|---|
| `/proudauth reload` | Ricarica `velocity-config.yml`, lingua e runtime proxy |
| `/proudauthproxy reload` | Alias esplicito per ricaricare il proxy |

## Comandi backend dietro Velocity

Quando proxy e backend usano entrambi lo stesso label `/proudauth`, il proxy intercetta il comando prima del backend.

Per questo il backend espone anche:

| Comando | Descrizione |
|---|---|
| `/proudauthbackend reload` | Ricarica ProudAuth sul backend Paper |

## Permessi backend

### Player

| Permesso | Default |
|---|---|
| `proudauth.login` | `true` |
| `proudauth.register` | `true` |
| `proudauth.changepassword` | `true` |
| `proudauth.logout` | `true` |
| `proudauth.2fa` | `true` |

### Admin

| Permesso | Default |
|---|---|
| `proudauth.admin` | `op` |
| `proudauth.admin.forcelogin` | `op` |
| `proudauth.admin.forcelogout` | `op` |
| `proudauth.admin.resetpassword` | `op` |
| `proudauth.admin.banip` | `op` |
| `proudauth.admin.unbanip` | `op` |
| `proudauth.admin.stats` | `op` |
| `proudauth.admin.reload` | `op` |
| `proudauth.bypass.auth` | `false` |

## Permessi proxy Velocity

| Permesso | Descrizione |
|---|---|
| `proudauth.admin.reload` | Consente il reload lato proxy |

## Reload

### Backend

`/proudauth reload` lato backend ricarica:

- `config.yml`
- `lang/it.yml`
- pool MySQL
- servizi auth
- premium verifier
- brute-force guard
- session manager
- listener e command binding aggiornati

### Proxy

`/proudauth reload` lato proxy ricarica:

- `velocity-config.yml`
- `lang/it.yml`
- storage MySQL lato proxy
- premium verifier lato proxy
- bridge service lato proxy

## Configurazione backend: `config.yml`

### `database`

- `host`
- `port`
- `name`
- `user`
- `password`
- `pool-size`

### `premium`

- `enabled`
- `auto-login`
- `api-timeout-ms`

### `sessions`

- `enabled`
- `ttl-minutes`
- `bind-to-ip`

### `security`

- `max-attempts`
- `lockout-seconds`
- `totp-enabled`

### `protection`

- `block-movement`
- `block-chat`
- `block-interactions`
- `invisible-until-auth`
- `no-drop-on-death`
- `auth-timeout-seconds`
- `auth-spawn`

### `password`

- `min-length`
- `max-length`
- `complexity-regex`

### `proxy`

- `mode: NONE | BUNGEE | VELOCITY`

La sezione `proxy` del backend serve davvero:

- `NONE` mantiene ProudAuth completamente standalone
- `BUNGEE` e `VELOCITY` attivano il comportamento backend-aware dietro proxy

### `bridge`

- `enabled`
- `mode: FALLBACK | STRICT`
- `transport: MYSQL`
- `shared-secret`
- `assertion-ttl-seconds`

La sezione `bridge` viene usata davvero solo quando vuoi un collegamento trusted tra proxy e backend.
Se lasci `enabled: false`, il backend continua a lavorare in autonomia come prima.

### `debug`

- `debug: true | false`

Quando `debug: true`, ProudAuth scrive log dettagliati su:

- pre-login
- decisione premium/cracked
- esito bridge
- join outcome
- apply/remove protection
- publish bridge sul proxy

## Configurazione proxy: `velocity-config.yml`

Il file proxy e stato ridotto alle sole sezioni effettivamente usate:

- `database`
- `premium`
- `bridge`

## Esempio backend standalone

```yaml
database:
  host: "127.0.0.1"
  port: 3306
  name: "proudauth"
  user: "root"
  password: "password"
  pool-size: 10

premium:
  enabled: true
  auto-login: true
  api-timeout-ms: 3000

sessions:
  enabled: true
  ttl-minutes: 1440
  bind-to-ip: true

bridge:
  enabled: false

proxy:
  mode: NONE
```

## Esempio backend dietro Velocity

```yaml
premium:
  enabled: true
  auto-login: true

sessions:
  enabled: true
  ttl-minutes: 720
  bind-to-ip: true

bridge:
  enabled: true
  mode: FALLBACK
  transport: MYSQL
  shared-secret: "change-me"
  assertion-ttl-seconds: 10

proxy:
  mode: VELOCITY
```

## Esempio proxy Velocity

```yaml
database:
  host: "127.0.0.1"
  port: 3306
  name: "proudauth"
  user: "root"
  password: "password"
  pool-size: 10

premium:
  enabled: true
  api-timeout-ms: 3000

bridge:
  enabled: true
  mode: FALLBACK
  transport: MYSQL
  shared-secret: "change-me"
  assertion-ttl-seconds: 10
```

## Best practice operative

- usa MySQL su un host stabile e vicino alla rete di gioco
- non disabilitare `bind-to-ip` senza una ragione precisa
- se usi Velocity, installa lo stesso jar sia sul proxy sia sui backend
- se attivi il bridge, usa lo stesso `bridge.shared-secret` su proxy e backend
- se attivi `STRICT`, testalo prima in staging
- se usi Bungee/Waterfall, configura correttamente il forwarding e usa `proxy.mode: BUNGEE`
- testa sempre sia un premium sia un cracked sul tuo staging
- se vuoi un'esperienza pulita, abilita un `auth-spawn` separato dal mondo principale
- documenta chiaramente ai clienti che il backend deve essere `online-mode=false`
- assicurati che backend e proxy abbiano rete al primo avvio per risolvere le dipendenze

## Limitazioni da dichiarare in vendita

Per ridurre ticket e contestazioni, conviene dichiarare in modo trasparente:

- backend supportato: Paper `1.21.1`
- Java richiesto: `21`
- MySQL richiesto
- il jar distribuito e unico e va riusato sia su backend sia su Velocity
- il companion proxy incluso e per Velocity
- il bridge trusted proxy -> backend attualmente usa MySQL
- Bungee/Waterfall sono supportati solo in modalita backend-aware, senza jar proxy dedicato in questa build
- il forwarding Velocity va configurato correttamente a livello proxy + Paper
- ProudAuth non sostituisce la configurazione nativa di forwarding del proxy
- il bridge shared secret ProudAuth e separato dal forwarding secret nativo di Velocity
- se `premium.enabled` e attivo, serve connettivita verso Mojang API
- il backend Paper scarica le librerie tramite `plugin.yml libraries`
- il proxy Velocity scarica le librerie runtime nella propria cartella dati al primo avvio

## Posizionamento commerciale consigliato

Puoi presentare ProudAuth come:

- auth plugin premium/cracked per backend Paper
- soluzione MySQL con sessioni, TOTP e anti brute-force
- plugin proxy-aware con companion Velocity incluso nello stesso jar
- bridge trusted opzionale tra proxy e backend con modalita `FALLBACK` e `STRICT`
- sistema modulare adatto sia a server singoli sia a network

## Riferimenti ufficiali utili

- [PaperMC Docs - plugin.yml libraries](https://docs.papermc.io/paper/dev/plugin-yml/)
- [PaperMC Docs - Velocity player information forwarding](https://docs.papermc.io/velocity/player-information-forwarding/)
- [PaperMC Docs - Velocity configuration](https://docs.papermc.io/velocity/configuration/)

## Licenza / vendita

Aggiungi qui:

- licenza commerciale
- politica aggiornamenti
- policy supporto
- eventuali limiti di ridistribuzione

Se prevedi di venderlo pubblicamente, questa sezione va completata prima della distribuzione.

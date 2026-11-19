# MobHarn `v1.1.1`

**MobHarn** è un'applicazione Android (Kotlin + Jetpack Compose) progettata come **agent harness AI completamente on-device**, alimentata da **LiteRT-LM** (Google AI Edge) e modelli locali `.litertlm` (come **Gemma 4**).

> **Versione corrente:** `v1.1.1` (`versionCode 3`) — ricerca web fattuale potenziata (Wikipedia lead extracts + DuckDuckGo Web), guardia anti-loop sui tool, sintesi automatica della risposta finale e riduzione significativa dell'uso di RAM sulla KV-cache.

L'app esegue inferenza locale con supporto **NPU (Google Tensor G5+ AOT), GPU e CPU**, **Chain of Thought (CoT) visibile in tempo reale**, **esecuzione reale di strumenti Android/Web/Coding**, apprendimento continuo (memoria + skill riutilizzabili), sandbox JavaScript/SQLite offline e protezioni avanzate per **RAM, temperatura, batteria e fluidità di sistema**.

---

## Funzionalità principali

### 1. Chat & Timeline Live dell'Agente
- **Pannello Attività Agente in tempo reale**: durante ogni risposta viene mostrata una scheda pulita con timer live (`<Modello> • In esecuzione da Xs`), stato corrente (*Ragionamento in corso…*, *Ricerca sul web…*, *Lettura file…*, *Apertura app…*) ed elenco espandibile passo-passo.
- **Chain of Thought (CoT) leggibile**: il ragionamento interno del modello scorre dal vivo dentro un blocco collassabile e resta consultabile in qualsiasi momento nello storico della chat. Dal menu `⋮` in Chat è possibile attivare/disattivare il CoT in 1 tap senza ricaricare i pesi del modello.
- **Visione multimodale**: allega immagini dalla Galleria (Android Photo Picker senza permessi storage), scatta foto con la Fotocamera o condividi testo/immagini da altre app Android direttamente verso MobHarn. Compressione e downscaling automatico in memoria (max `1024px`) per evitare picchi RAM.
- **Azioni dirette sui blocchi di codice**: copia con conferma istantanea, salva nel Coding Workspace o esegui direttamente nella sandbox JS/SQLite.
- **Storico & Ricerca Full-Text (FTS)**: drawer laterale con gestione sessioni e ricerca istantanea su tutte le conversazioni passate tramite SQLite FTS.

### 2. Motore LiteRT-LM, Esecuzione Tool & Apprendimento Continuo
- **Accelerazione hardware adattiva**: caricamento `.litertlm` con fallback automatico tra **NPU (Google Tensor)**, **GPU** e **CPU**, e `useLegacyPackaging = true` per il rilevamento delle librerie native Tensor (`libLiteRtDispatch_GoogleTensor.so`).
- **Routing intelligente dei gruppi di strumenti**: in base al prompt vengono esposti al modello solo i tool di dominio rilevanti, mantenendo **sempre attivo** il gruppo **Memoria & Skill** per l'apprendimento continuo su ogni turno.
- **Motore di Tool-Call Recovery anti-crash**:
  - Rimuove le penalità di ripetizione/presenza sui turni con tool per preservare i delimitatori nativi `<|"|>` di Gemma 4.
  - Intercetta automaticamente eventuali errori `Status Code: 3` (`Failed to parse FC tool calls`) di LiteRT-LM o blocchi `<|tool_call>` finiti nel testo, estrae nome e parametri reali del tool, lo esegue dal vivo aggiornando la timeline e sintetizza la risposta finale in italiano.
  - **Gate di approvazione sicurezza**: le azioni esterne sensibili (es. sveglie, bozze, azioni sullo schermo) possono richiedere conferma esplicita dell'utente prima dell'esecuzione.
- **Apprendimento continuo (Memoria + Skill che migliorano con l'uso)**:
  - L'agente salva proattivamente fatti personali, preferenze e note (`save_memory`, `delete_memory`, `search_memory`, `search_past_sessions`) sincronizzati su Room + `USER.md`/`MEMORY.md`, con una rete di sicurezza automatica se chiedi esplicitamente di segnare/ricordare qualcosa.
  - Il catalogo compatto delle **Skill** apprese è sempre visibile nel contesto: l'agente le richiama con `load_skill` (incrementando il contatore d'uso) e ne crea o aggiorna di nuove con `save_skill` quando impara un flusso riutilizzabile.

### 3. Gruppi di Strumenti (Toolkits)
| Gruppo | Strumenti disponibili |
| --- | --- |
| **Memoria & Skill** | `save_memory`, `search_memory`, `delete_memory`, `search_past_sessions`, `list_skills`, `load_skill`, `save_skill` |
| **Coding & Sandbox** | `list_workspace_files`, `read_file`, `write_file` (con snapshot di rollback), `patch_file`, `grep_workspace`, `run_javascript` (ES6+), `run_sql` (SQLite in-memory) |
| **Azioni Android** | `launch_app`, `open_url`, `open_maps`, `set_alarm`, `start_timer`, `create_calendar_event`, `draft_email`, `draft_sms`, `dial_number`, `share_text`, `open_settings` |
| **Web & Calcolo** | `web_search` (ricerca reale su web), `fetch_webpage` (estrazione testo pulito), `calculate_expression`, `validate_json_or_regex` |
| **Telemetria Dispositivo** | `get_device_telemetry` (batteria, °C, RAM, storage, rete), `read_clipboard`, `get_sensors`, `get_approx_location` |
| **Automazioni** | `schedule_automation` (WorkManager: `BRIEFING`, `TELEMETRY_CHECK`, `WEB_MONITOR`, `MEMORY_DIGEST`, `WORKSPACE_AUDIT`) |
| **Auto-Evoluzione** | `create_custom_tool`, `list_custom_tools`, `run_custom_tool`, `delete_custom_tool`, `update_soul_md`, `tune_harness_params` |
| **Phone-Use (Accessibilità)** | `read_screen`, `tap_element`, `tap_coordinates`, `type_text`, `scroll_screen`, `system_navigate` |

### 4. Interfaccia Pulita su 4 Schede (Material 3)
L'interfaccia è stata progettata per ridurre al minimo il rumore visivo tramite *progressive disclosure*:
1. **Chat**: barra superiore essenziale con pillola di stato del motore, welcome screen centrata con 3 spunti rapidi, messaggi assistente editoriali senza riquadri superflui, compositore unico a pillola con menu `+`.
2. **Modello**: singola scheda di stato con 3 indicatori vitali (`RAM`, `°C`, `Batteria`), importazione `.litertlm` da file locale, profili modello con opzioni avanzate nascoste dietro **Tune ▾** (backend, Visione, Thinking/CoT, contesto, temperatura, eliminazione) e scheda compatta **Batteria & Aspetto**.
3. **Studio**: selettore progetto/file compatto, editor di codice con toolbar integrata (Run/Salva/Rollback/Snapshot), sezioni secondarie collassabili ad accordion (*Gruppi di Strumenti*, *Custom Tool JS*, *Testa Strumenti*, *Log Esecuzioni*) e scheda **Automazioni** con un solo pulsante principale.
4. **Memoria**: editor compatto del Vault (`SOUL.md`, `USER.md`, `MEMORY.md`), schede minimaliste per i Fatti appresi e Skill procedurali riutilizzabili (`/slug`) espandibili al tocco.

---

## Contesto Lungo, Auto-Compattamento, Ricerca Ricordi & Background Zero-Lag

MobHarn implementa un'architettura mobile a **3 livelli di memoria** (`ContextAndMemoryEngine` + `LiteRtRuntimeManager`) progettata per lavori lunghi senza saturare RAM, VRAM o fluidità del telefono:

### Gestione Contesto Lungo & Auto-Compattamento
- **Finestra di contesto adattiva alla RAM (`4K` / `8K` / `16K` / `32K`)**: il limite massimo si adatta automaticamente alla RAM libera disponibile (`≥ 6.5 GB` fino a `32K`, `≥ 4.2 GB` fino a `16K`, `≥ 2.6 GB` fino a `8K`, altrimenti `4K`) evitando crash OOM.
- **Architettura a 3 livelli**:
  1. **Hot Window (verbatim)**: mantiene intatti gli scambi più recenti scalati sul budget attivo (fino a 14 turni a `32K`) con compressione testa+coda (`compactSingleMessage`) per non perdere né le premesse né le conclusioni dei messaggi lunghi.
  2. **Warm Checkpoint di sessione (auto-compattato)**: quando la chat supera la finestra calda o si avvicina al limite token, un compattatore deterministico (`<5 ms` su CPU, zero calore GPU aggiuntivo) estrae obiettivi, file toccati, tool eseguiti e decisioni chiave in un checkpoint persistente (`sessions/session_<id>.md`) iniettato nel prompt di sistema.
  3. **Cold Vault ricercabile**: ricerca ibrida locale **BM25 + normalizzazione accenti + stemming italiano/inglese + importanza + recenza** per `search_memory`, `search_past_sessions` (SQLite FTS4 OR + re-ranking) e recupero *Just-in-Time* automatico di memorie e skill rilevanti ad ogni turno.
- **Sticky Session Tool-Groups & KV-cache calda**: durante sessioni lunghe, il runtime mantiene una unione stabile dei gruppi di tool usati nella sessione (fino a 3 gruppi) e tiene la KV-cache calda per `180s` in foreground, evitando continui reset e re-prefill.
- **Indicatore contesto discreto**: una barra sottile da `2 dp` sotto l'intestazione della Chat e il contatore live nel menu `⋮ → Compatta contesto` mostrano l'occupazione reale senza affollare lo schermo.

### Esecuzione Background Senza Cali di Prestazioni sul Telefono
- **Cedimento immediato CPU/GPU in background**: appena passi a un'altra app (`onStop()`), il runtime abbassa istantaneamente il thread di inferenza a `Process.THREAD_PRIORITY_LOWEST` (schedulato sui core ad efficienza) e inserisce micro-pause cooperative tra i chunk (`≥ 32 ms`) per lasciare a SurfaceFlinger e alle app in primo piano tutta la fluidità a 120 Hz. Al ritorno nell'app la priorità foreground viene ripristinata sul chunk successivo.
- **Throttling UI adattivo**: lo streaming in foreground aggiorna la UI a `~10.5 Hz` (`95 ms`), mentre in background scende a `~2.8 Hz` (`350 ms`) eliminando ricomposizioni inutili fuori schermo.
- **Automazioni WorkManager gentili**: `AutomationWorker` gira a priorità `THREAD_PRIORITY_LOWEST`, rinvia automaticamente i task non manuali se il dispositivo è `HOT`/`CRITICAL` o con poca RAM, salta in Risparmio Energetico e chiude sempre le connessioni HTTP idle al termine.

### Gestione RAM, Temperatura e Batteria (`ThermalAndMemoryGuard`)
- **Protezione NPU & RAM**: guardie pre-flight per evitare crash nativi su modelli non-AOT o SoC non supportati, `android:largeHeap="true"`, trim della KV-cache a riposo (`18s` in background solo a generazione ferma) e auto-scaricamento trasparente dei pesi in background (`120s` o `45s` con batteria bassa) con ricaricamento automatico al turno successivo.
- **Monitoraggio termico continuo** (`COOL` / `WARM` / `HOT` / `CRITICAL`): regola dinamicamente `maxTokens`, pacing tra token, priorità CPU e budget CoT per mantenere il dispositivo fresco.
- **Risparmio batteria profondo**: `AccessibilityService` senza risvegli continui (`typeWindowStateChanged`, timeout `400ms`), compressione immagini on-decode (`max 1024px`), chiusura socket HTTP in `onStop()`, sensori a bassa frequenza e tema scuro OLED `#080B0A`.

---

## Stack Tecnologico & Architettura

- **Linguaggio**: Kotlin (`2.4.21`) con Coroutines & StateFlow
- **UI**: Jetpack Compose + Material 3, tipografia personalizzata locale (**Space Grotesk**, **Plus Jakarta Sans**, **JetBrains Mono**), Edge-to-Edge
- **AI On-Device**:
  - `com.google.ai.edge.litertlm:litertlm-android:0.19.1`
  - `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2`
- **Persistenza Locale**: Room (`2.7.0`, con SQLite FTS per lo storico) + DataStore Preferences (`1.1.7`) + Vault Markdown su storage interno privato
- **Background & Rete**: AndroidX WorkManager (`2.10.0`), OkHttp (`4.10.0`), Coil Compose (`2.7.0`)
- **Architettura**: MVVM + Container DI manuale (`MobHarnAppContainer`) con separazione netta tra:
  - `agent/` (runtime LiteRT-LM, prompt assembler, guardie termiche/memoria/batteria, toolkit, sandbox JS/SQLite, phone-use, automazioni)
  - `data/` (Room DAO/Entity, DataStore, Vault Markdown, Workspace file manager, Repository)
  - `ui/` (ViewModel, schermate Chat/Modello/Studio/Memoria, componenti riutilizzabili tra cui `AgentActivityPanel` e `MarkdownCodeRenderer`, tema Material 3)

---

## Configurazione e Avvio Rapido

### Requisiti
- **Android minSdk**: 24 (consigliato Android 13+ su dispositivi con almeno 8 GB di RAM, es. Google Pixel con Tensor per NPU/GPU)
- **Target / Compile SDK**: 36
- **JDK**: 11+
- Un file modello compatibile in formato **`.litertlm`** (es. Gemma 4 quantizzato per LiteRT-LM) salvato sul telefono.

### Build
```bash
# Compilazione debug APK
gradle :app:assembleDebug

# Esecuzione unit test locali (JVM / Robolectric)
gradle :app:testDebugUnitTest
```

### Primo utilizzo su dispositivo
1. Apri **MobHarn** e vai nella scheda **Modello**.
2. Tocca **Importa `.litertlm`** e seleziona il modello dal dispositivo.
3. Tocca **Carica** sul profilo importato (opzionalmente espandi **Tune ▾** per scegliere backend NPU/GPU/CPU, Visione, Chain of Thought, temperatura e finestra di contesto).
4. Torna nella scheda **Chat** e inizia a interagire con l'agente.

### Permessi Android (tutti opzionali e richiesti solo al bisogno)
- `INTERNET` / `ACCESS_NETWORK_STATE`: usati solo per i tool `web_search` e `fetch_webpage` (l'inferenza AI è 100% locale).
- `CAMERA`: richiesto solo quando scegli **Scatta Foto** dal menu `+` della chat.
- `ACCESS_COARSE_LOCATION`: usato solo se invochi il tool di posizione approssimativa.
- `POST_NOTIFICATIONS`: usato per le notifiche delle automazioni pianificate in background.
- `SET_ALARM`: usato dal tool `set_alarm` / `start_timer`.
- **Servizio di Accessibilità (opzionale)**: da abilitare manualmente nelle Impostazioni Android solo se vuoi usare i tool **Phone-Use** (`read_screen`, `tap_element`, ecc.).

---

## Cronologia Versioni (Changelog)

### `v1.1.1` (`versionCode 3`)
- **Ricerca web fattuale precisa (`web_search`)**: priorità agli estratti introduttivi completi di **Wikipedia IT/EN** (con estrazione automatica dell'entità core es. *"popolazione Cardito NA"* → *"Cardito"*), ricerca web reale su **DuckDuckGo HTML/Lite** sempre attiva e rimozione dei link redirect chilometrici di Google News (che ora interviene solo per richieste di notizie o fallback).
- **Stop loop tool e mai più output vuoti `"Strumenti eseguiti con successo."`**: aggiunta guardia anti-duplicati per turno, `noRepeatNgramSize = 5` (evita loop come `web_search_search_search`), budget output ampliato sui turni con tool e **sintesi finale automatica** se il modello esaurisce i token nel ragionamento CoT dopo aver eseguito un tool.
- **Riduzione drastica RAM**: KV-cache nativa limitata a `4K–6K` in Modalità Eco (delegando i lavori lunghi all'auto-compattamento a 3 livelli invece di pre-allocare 1.2–2.4 GB di VRAM), trim KV a riposo ridotto a `45s` in foreground / `8s` in background, auto-scaricamento a `60s` (`30s` con batteria bassa) e Sticky Tool-Groups limitati a 2 gruppi.

### `v1.1.0` (`versionCode 2`)
- **Contesto lungo & architettura a 3 livelli (`4K` / `8K` / `16K` / `32K`)**: finestra adattiva alla RAM libera, *Hot Window* con compressione testa+coda, *Sticky Tool-Groups* per mantenere calda la KV-cache tra i turni e barra discreta di occupazione contesto in Chat.
- **Auto-compattamento istantaneo (< 5 ms su CPU)**: sintetizza automaticamente i turni più vecchi in checkpoint persistenti per sessione senza generare calore GPU aggiuntivo.
- **Ricerca ricordi e storico potenziata (BM25 ibrido locale)**: normalizzazione accenti, stemming italiano/inglese, boost su importanza/recenza, FTS4 `OR` per lo storico e RAG locale *Just-in-Time* su memorie e skill ad ogni turno.
- **Background zero-lag**: cedimento immediato a `THREAD_PRIORITY_LOWEST` + micro-pause cooperative GPU/CPU al passaggio ad altre app, throttling UI fuori schermo (~2.8 Hz) e automazioni WorkManager gentili con controllo termico e RAM.
- **Stabilità NPU & Tool-Call Recovery**: guardie pre-flight per modelli AOT/SoC Tensor G5+ e RAM libera, `android:largeHeap="true"`, fix errori `Status Code: 3` sulle chiamate tool e loop di apprendimento continuo per memorie e skill.
- **Etichetta versione visibile**: indicazione `MobHarn v1.1.0 (build 2)` in fondo alla scheda **Modello** per verificare subito l'APK installato.

### `v1.0.0` (`versionCode 1`)
- Prima release completa di MobHarn con inferenza locale LiteRT-LM (Gemma 4), UI Material 3 pulita su 4 schede, pannello live attività agente e Chain of Thought (CoT), toolkit Android/Web/Coding/Sandbox/Phone-Use, Vault Markdown + Room FTS e protezioni termiche/batteria.

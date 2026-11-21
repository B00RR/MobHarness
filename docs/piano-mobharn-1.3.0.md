# MobHarn 1.3.0 proposta — incarichi verificabili e modifiche recuperabili

**Stato:** bozza da discutere, non autorizzazione a implementare, installare o pubblicare. **Obiettivo:** da una richiesta in italiano a un risultato osservato sul telefono, oppure a una spiegazione precisa del punto di arresto. Nessun PC o servizio cloud necessario nell'uso normale. Mantenere Kotlin, LiteRT-LM, Gemma E4B, Room, WorkManager e i consensi esistenti. Niente nuovo permesso previsto.

## Due incarichi definiscono il perimetro

1. «Apri la pagina indicata, entra nella seconda voce e dimmi se l'opzione è attiva. Non modificarla.» Due navigazioni, valore corretto, interruttore invariato. Il prompt non contiene la risposta. Nessun nome di app, lingua o voce di Impostazioni cablato nel prodotto.
2. «Nel progetto di prova sostituisci questa frase, mostrami cosa è cambiato e poi annulla.» Il file finale deve essere identico all'originale. Con dati o schermate ambigui è un risultato corretto fermarsi, non indovinare.

Non è una promessa di controllo universale. Criterio di scelta: API/intent ufficiale quando restituisce il dato o l'azione richiesti; Accessibilità quando occorre interagire con un'altra app. Se l'utente chiede esplicitamente di navigare, una scorciatoia non vale come prova dei due tocchi.

## Punto di partenza e dipendenze

Release 1.2.0 build 5 (`3036c65`), #9 aperta a `2713097` per il ritorno dalla notifica, #10 solo documentazione. In QA: un tocco verificato sul Pixel, compito a due passi non riuscito. I 64 test di #9 sono una base, non il collaudo della 1.3.0. Riferimenti: `plans/MobHarn-mappa-capacita.md` e [fonti](https://github.com/B00RR/MobHarness/pull/10). Una PR per cambiamento; nessun merge implicito. Nel piano futuro il candidato schermo dipende da #9; file e automazioni possono essere revisionati separatamente. Non portare suffisso QA o firma debug nella release.

## Tappa 1 — Il consenso deve riferirsi a un bersaglio reale (obbligatoria)

- Riprodurre sul PC il difetto: lettura assente, etichetta assente o doppia → oggi il tocco per testo può chiedere l'approvazione lo stesso. Usare il test già esistente sulle coordinate come confronto. Aggiungere anche un caso positivo: una corrispondenza, una richiesta.
- Prima della notifica leggere la schermata corrente e risolvere un nodo unico. Una rilettura limitata è consentita; mai un ripiego su coordinate non identificate. Distinguere servizio assente, bersaglio assente/ambiguo, rifiuto e finestra cambiata. Ricontrollo di app, finestra e nodo dopo il consenso, anche quando una regola era stata ricordata. Lo stato del bersaglio non si conserva come permesso permanente.
- Esaminare i chiamanti testo, coordinate, input e scroll per lo stesso errore, senza modificare i gesti globali per analogia. Codice: `app/src/main/java/com/example/agent/tools/SelfEvolveAndPhoneToolSets.kt`, `agent/phoneuse/MobHarnAccessibilityService.kt` (i percorsi abbreviati qui sotto sono sotto `app/src/main/java/com/example/`). Test: `PhoneTypingPrivacyRegressionTest`, `ApprovalGateTest`, `ApprovalNotificationTest`.
- Uscita: zero notifiche e zero gesti per bersaglio invalido; zero gesti dopo revoca, blocco o cambio finestra; nessun testo riservato nel log.

## Tappa 2 — Completare un incarico a due passi (obbligatoria)

- Separare nell'esecuzione e nella chat «richiesto», «inviato», «verificato», «non verificabile/interrotto». Riusare il log e il coordinatore correnti; un testo generato dal modello non è una verifica. Prima del prossimo gesto attendere e osservare la nuova pagina entro un budget esplicito. Se l'azione potrebbe essere riuscita, ripetere solo la lettura, mai il gesto. Stop, scadenza e riavvio non devono rieseguire effetti pendenti.
- Su una fixture a due pagine usare un controllo caratteristico della destinazione, non il nome già presente nella pagina iniziale. Leggere il valore effettivo dello switch; verificare sia acceso sia spento. Se non osservabile, riportare l'ultimo passo certo e non inventare il valore. La ripresa automatica generale dopo la chiusura del processo resta fuori: arresto sicuro prima di ripresa sofisticata.
- Codice: `agent/phoneuse/MobHarnAccessibilityService.kt`, `agent/tools/MobHarnToolSets.kt`, `ui/viewmodel/ChatViewModel.kt`, `ui/components/AgentActivityPanel.kt`. Modello interno: `agent/phoneuse/RecentsRoutine.kt` per invio singolo + osservazione limitata. Controllare anche il percorso delle chiamate recuperate affinché non aggiri le stesse guardie.
- Uscita: due messaggi formulati diversamente, due stati dello switch, risposta corretta in ciascuna combinazione sull'emulatore; nel caso negativo esito esplicito, nessun terzo tocco e nessuna nuova approvazione alla cieca.

## Tappa 3 — File reversibili e letture economiche (obbligatoria per la release proposta)

- Su file sintetici provare sovrascrittura, patch, creazione e annullamento. Salvare lo stato recuperabile prima di sostituire il file; distinguere «file inesistente» da «file vuoto». Fallimento dello snapshot → non scrivere. Ripristino fallito → conservare lo snapshot, non mostrare successo. Verificare i percorsi tool ed editor che scrivono nello stesso workspace. Non estendere l'accesso ai file personali. Se serve cambiare Room, migrazione non distruttiva provata su copia 1.2.0.
- `read_file_lines` oggi carica e divide tutto il file. Leggere progressivamente con un limite sia in byte sia nelle righe restituite; segnalare il troncamento. Testare una singola riga molto grande e un intervallo oltre il limite; una lettura parziale non deve diventare la base di una sovrascrittura silenziosa.
- Codice: `data/workspace/WorkspaceManager.kt`, `data/MobHarnRepository.kt`, `agent/tools/MobHarnToolSets.kt`, `ui/viewmodel/StudioViewModel.kt`. Riferimento esterno: Mobile Harness per checkpoint e letture limitate. Uscita: contenuti originali identici dopo undo; errori di scrittura/interruzione simulati su PC, copia recuperabile. Misurare byte letti nel test, non stressare il Pixel.

## Tappa 4 — Esiti coerenti e regressioni prima del candidato

- In `agent/automation/AutomationWorker.kt` separare l'esito dell'azione dalla decisione di WorkManager (fine, rinvio o nuovo tentativo). Un worker terminato non prova che l'incarico sia riuscito. Test: eccezione simulata → esito azione fallita, non notifica di successo; nessun replay automatico se l'effetto è incerto. Conservare il comportamento con risparmio energetico, calore e memoria bassa (`AutomationPowerSaveRegressionTest`).
- Collaudo delle funzioni già esistenti che rendono l'app simile a Hermes, senza aggiungere nuovi moduli: una lezione sintetica proposta → salvata → richiamata in nuova chat; disattivata → non richiamata. Verificare che «ricorda approvazione» non si estenda a un bersaglio diverso. Se la copertura manca, etichettare la capacità non dimostrata; non presentarla come novità della release.
- Test: `LocalLearningTest`, `LessonStorageTest`, `ApprovalGateTest`; suite/build CI con Gradle 9.3.1: `gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon`. L'emulatore con fixture è un passaggio distinto dalla CI unit. Esaminare i risultati sullo SHA preciso del candidato.

## Tappa 5 — Candidato, compatibilità e pubblicazione (decisioni distinte)

1. Sul PC/emulatore: fixture senza account, zero dati reali; testare stop, blocco, errore e ripristino. Stesso modello e stesso backend per confronti pertinenti. Registrare passaggi verificati, chiamate al modello, durata, memoria e dati indisponibili; niente screenshot personali nei log. Un modello in RAM alla volta. Nessuna promessa di velocità senza misura.
2. Solo previo consenso separato, breve prova finale sul Pixel con app QA separata, permessi minimi, nessun tocco dell'operatore al posto dell'app. Fermarsi su warning termico, memoria bassa, telefono bloccato o bersaglio incerto. Niente stress, root, flash o cambi al timeout. Alla fine chiudere la QA, scaricare Gemma dalla RAM, verificare assenza di processi e approvazioni pendenti. Se non autorizzato, scrivere «QA fisica non eseguita» e non dichiararla superata.
3. Candidato di aggiornamento: versione proposta `1.3.0`, `versionCode` superiore a quello effettivamente distribuito (oggi 5), note con risultati e limiti. Su ambiente usa e getta verificare aggiornamento 1.2.0 → candidata con due build a firma compatibile e dati sintetici: chat, skill, preferenze e riferimento al modello preservati. Una QA affiancata non è prova di upgrade. Se una candidata firmata come la release richiede la pubblicazione nella pipeline corrente, non aggirare la firma: preparare prima una procedura protetta senza pubblicazione, oppure esplicitare il limite e sospendere il rilascio. Non disinstallare la copia personale.
4. Prima del rilascio: regressioni verdi, entrambi gli incarichi verificati, nessun difetto aperto con rischio di perdita dati, effetti duplicati o consenso fuori bersaglio. Poi decisione esplicita di rilascio; solo allora merge/tag secondo il flusso del progetto, verifica firma, hash e APK effettivamente pubblicato. Se un criterio fallisce, resta la 1.2.0; nessun downgrade distruttivo come recupero.

## Cosa rimandare e decisione di perimetro

Rimandare ROM, root, shell generale, nuovi runtime, agenti paralleli, agenti remoti e OCR continuo. Nessuno risolve i due incarichi, diversi aumentano privilegi, RAM o consumo. Non aggiungere una dipendenza per replicare una funzione Kotlin già presente. Se serve davvero un'uscita più piccola, separare una patch `1.2.x` per il solo flusso schermo; non chiamarla 1.3.0 completa. La decisione da discutere ora è il valore dei due incarichi per l'utente, non il modo di distribuire subito un APK.

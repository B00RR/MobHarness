# Fonti per una harness sul telefono

Obiettivo: un incarico in linguaggio naturale, eseguito sul dispositivo e verificato senza un PC. Questi riferimenti servono al confronto: non sono dipendenze né un elenco di funzioni già implementate.

## Agente e strumenti

- [Hermes Agent](https://github.com/NousResearch/hermes-agent): ciclo di esecuzione, memoria e procedure riutilizzabili. Distinguere il risultato di un tool dalla risposta del modello.
- **AndroidHarness**: esempi Kotlin di [feedback quando una chiamata è errata](https://github.com/Sanuu7/AndroidHarness/blob/c2f12baf12add491f21f1b2f0d113fca0adf8b05/app/src/main/java/com/androidharness/app/agent/ToolFailurePolicy.kt) e [prove di verifica che scadono dopo una modifica](https://github.com/Sanuu7/AndroidHarness/blob/c2f12baf12add491f21f1b2f0d113fca0adf8b05/app/src/main/java/com/androidharness/app/agent/VerificationEvidence.kt). La seconda implementazione riguarda build e test, non la verifica di un tocco Android.
- **Mobile Harness (techjarves)**: workspace di sviluppo Android con Ubuntu in PRoot. [WorkspaceCheckpoints.kt](https://github.com/techjarves/Mobile-Harness/blob/367b207ab7b048b9510f594ca4b730419debb901/app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt) conserva lo stato iniziale dei file e permette di esaminare e annullare le modifiche singolarmente. [BoundedFileReads.kt](https://github.com/techjarves/Mobile-Harness/blob/367b207ab7b048b9510f594ca4b730419debb901/app/src/main/java/com/jarves/mh/runtime/BoundedFileReads.kt) legge solo la porzione necessaria dei log senza caricare file interi in RAM. Spunti per i tool file e terminale, non per i tocchi tra app. PRoot non è una VM di sicurezza e una shell locale non implica inferenza locale.
- [Termux](https://github.com/termux/termux-app) per il terminale dentro Android; [Playwright](https://playwright.dev/docs/locators) per selezionare elementi del web. Né una shell né il DOM di una pagina sostituiscono il controllo delle app native.

## Schermo, permessi e collaudo

- [Mobilerun Portal: AccessibilityTreeBuilder](https://github.com/droidrun/mobilerun-portal/blob/49d92110ed9faeaad4c8f65b681c4a3012b1de25/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt): confronto per la lettura dei controlli nativi; [Phone Harness: tap_text](https://github.com/phone-harness/phone-harness/blob/b16b37f612299b8e388a1704b3827b8e449d78b8/src/phone_harness/helpers.py#L199-L215): ricerca per etichetta e feedback se manca. Non prendere la prima di più corrispondenze.
- Android: [Accessibilità](https://developer.android.com/guide/topics/ui/accessibility/service) per lo schermo, [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) per documenti scelti dall'utente e [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent) per lavoro differibile. Preferire l'API diretta quando disponibile.
- [AndroidWorld](https://github.com/google-research/android_world) e [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator): progettare prove con un esito osservabile. Sul telefono reale il driver può osservare, non compiere il tocco al posto dell'agente.

## Modello locale

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) e [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM): inferenza e chiamate ai tool con il runtime già usato per Gemma.
- [QVAC](https://github.com/tetherto/qvac/tree/fbadcc2608773505ce3dc3820dd2cbf7c9c288a2): confronto per modelli locali, voce e OCR. L'[esempio di tool calling](https://github.com/tetherto/qvac/blob/fbadcc2608773505ce3dc3820dd2cbf7c9c288a2/packages/llm-llamacpp/examples/toolCalling.js) interpreta la richiesta del modello; non autorizza un gesto Android. Compatibilità con il file `.litertlm` di MobHarn non verificata.

## Esperienza d'uso (non codice da importare)

- **[Skales](https://github.com/skalesapp/skales/blob/5dd098d16897106fe0df0fb0b6a1f96b9a6adf21/README.md)** distingue il telefono autonomo dal telefono abbinato al desktop: nel secondo caso gli strumenti del PC non diventano strumenti Android. Da studiare la continuità della conversazione e il lavoro per obiettivi con ripresa e richieste di conferma. Per MobHarn: mostrare dove si svolge un incarico e, alla ripresa, distinguere l'ultimo passo verificato dal prossimo ancora da autorizzare. Il prodotto è [proprietario](https://github.com/skalesapp/skales/blob/5dd098d16897106fe0df0fb0b6a1f96b9a6adf21/LICENSE); il repository non contiene il sorgente corrente dell'app. La documentazione descrive il prodotto, non prova il controllo delle altre app sul Pixel.

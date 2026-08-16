# PLAN.md

Titolo di lavoro: **AnkiCard**. Da cambiare prima di qualsiasi distribuzione,
ma non per una questione di licenza: Anki è software libero (AGPLv3) e
leggerne il formato è pienamente legittimo. È il **nome** a essere di
Ankitects, e una licenza open source non concede il marchio: chiamare l'app
"AnkiCard" suggerisce una parentela che non c'è. Non ha impatto tecnico, ma
va deciso prima che finisca nell'`applicationId`.

---

## 1. Cosa costruiamo

Un sistema di ripetizione spaziata che, oltre alle flashcard classiche, sa
somministrare **quiz** (scelta multipla, abbinamento, vero/falso, risposta
scritta) generati a partire dalle card esistenti.

Il sistema è diviso in due metà con responsabilità nette:

```
   ┌──────────────────────────── DESKTOP (Windows) ────────────────────────────┐
   │                                                                            │
   │   sorgenti          baker (pipeline)                     artefatto         │
   │   ────────          ───────────────────                  ─────────         │
   │   .apkg      ─┐     ingest → normalize → classify                          │
   │   .csv/.tsv  ─┼──▶  → neighbor index → generate  ──────▶  mazzo.qzd        │
   │   .md        ─┘        (regole + LLM) → validate                           │
   │                                                                            │
   │   LLM locale (Ollama / llama.cpp)   +   embedder piccolo                   │
   └────────────────────────────────────────────────────────────────────────────┘
                                     │  copia manuale (USB / SAF)
                                     ▼
   ┌──────────────────────────── ANDROID ──────────────────────────────────────┐
   │                                                                            │
   │   mazzo.qzd  ──import──▶  content.db (sola lettura)                        │
   │                                    │                                       │
   │                                    ├──▶  review flashcard (richiamo libero)│
   │                                    ├──▶  quiz (pool + campionamento)       │
   │                                    │                                       │
   │                           state.db (progressi FSRS, log, distrattori       │
   │                                     bruciati)  ──export──▶ feedback.json   │
   │                                                                            │
   │   NESSUN LLM. NESSUNA RETE.                                                │
   └────────────────────────────────────────────────────────────────────────────┘
```

Il principio che governa tutto: **il modello non gira mai mentre l'utente
studia.** Non è un'ottimizzazione, è la separazione che rende l'app mobile
possibile.

---

## 2. Vincoli di progetto

Vincoli, non preferenze. Se una modifica futura ne rompe uno è una decisione
consapevole, non un dettaglio implementativo.

| | Vincolo | Come si verifica |
|---|---|---|
| **C1** | Nessun modello generativo nell'APK. Nessuna rete nel percorso di studio: la variante `offline` non dichiara `INTERNET`, la variante `lan` lo dichiara ma il modulo di sync è isolato e l'app resta pienamente funzionante senza | `aapt2 dump badging app-{flavor}-release.apk \| grep uses-permission`; test: modalità aereo → tutto funziona |
| **C2** | Contenuto e stato in due database separati: reimportare un mazzo aggiornato **non** cancella i progressi | test: import → studio → re-import → i progressi sopravvivono |
| **C3** | Zero generazione a runtime sull'app: ogni opzione mostrata o esiste già in `content.db`, o è la risposta corretta di un'altra card dello stesso mazzo | grep: nessuna costruzione di stringhe-risposta fuori da `QuizAssembler` |
| **C4** | Il quiz funziona anche con i pool vuoti, degradando al solo campionamento | test su un `.qzd` prodotto con `--no-llm` |
| **C5** | Ogni distrattore porta con sé la provenienza (`rule` / `llm` / `sibling`) e la versione del generatore | colonna `origin` NOT NULL |
| **C6** | Lo schema del `.qzd` è versionato e l'app rifiuta esplicitamente le versioni che non conosce | `manifest.json → schema_version`, test con versione futura |
| **C7** | Il baker è deterministico a parità di input e seed (LLM con `temperature` fissa e seed) | doppio run → stesso hash dell'artefatto |
| **C8** | Le identità delle card (`uid`) sono stabili attraverso i re-bake **e identiche se calcolate dal baker o dall'app**: stessa ricetta congelata, due implementazioni | test incrociato Python/Kotlin sullo stesso `.apkg` → stessi uid |
| **C9** | L'unità di baking e di studio è la **raccolta**: vicini ed esclusioni si calcolano su tutte le sue card, attraversando le sezioni | un quiz su una raccolta di due sezioni pesca distrattori da entrambe |
| **C10** | Nessuna opzione mostrata all'utente può essere una risposta corretta alla domanda posta | tabella `exclusion` popolata in fase di validazione e rispettata dal `QuizAssembler` |
| **C11** | L'unica identità che trasporta valore è quella della **card**. Raccolte e sezioni sono contenitori: se due dispositivi non concordano su di essi, non si perde nulla | test: rinomina una raccolta, reimporta, i progressi restano |

Corollario di C1 e C2: l'app Android è, di fatto, un lettore. Tutta la
complessità intelligente vive sul desktop, dove può essere debuggata con
`print()` invece che con `adb logcat`.

---

## 3. Il formato `.qzd`

Uno zip, sulla falsariga di `.apkg` (che è la stessa idea: zip + SQLite).

```
mazzo.qzd
├── manifest.json      metadati e versione schema
├── content.sqlite     card, pool di distrattori, indice dei vicini
└── media/             immagini/audio referenziati (opzionale, fase 2)
```

`manifest.json`:

```json
{
  "schema_version": 1,
  "raccolta": { "uid": "5f3c…", "name": "Reti" },
  "sezioni": [
    { "uid": "a1…", "name": "Day 03 - TCP/IP", "cards": 25, "src": "Day 03 Flashcards - TCP-IP.apkg" },
    { "uid": "b7…", "name": "Routing basics",  "cards": 61, "src": "routing.apkg" }
  ],
  "lang": "en",
  "card_count": 86,
  "baked_at": "2026-08-12T19:40:00Z",
  "baker_version": "0.3.1",
  "llm": { "model": "qwen3:4b-q4_K_M", "temperature": 0.8, "seed": 42 },
  "coverage": { "rule": 96, "llm": 210, "sibling_only": 106 }
}
```

`coverage` non è decorativo: ti dice a colpo d'occhio se la pipeline sta
classificando male (troppe card che finiscono in `sibling_only` = il
classificatore non riconosce i tipi).

---

## 4. Schema dati

### 4.1 `content.sqlite` (desktop → telefono, sola lettura sul telefono)

```sql
CREATE TABLE card (
  uid         TEXT PRIMARY KEY,      -- UUID stabile, vedi C8
  front       TEXT NOT NULL,
  back        TEXT NOT NULL,         -- il retro originale, mostrato per intero dopo la risposta
  answer_core TEXT NOT NULL,         -- la risposta vera e propria, senza glosse
  answer_note TEXT,                  -- glossa: espansione acronimo, nota, precisazione
  core_norm   TEXT NOT NULL,         -- answer_core normalizzato: lowercase, no punteggiatura, no articoli
  answer_type TEXT NOT NULL,         -- numeric|date|term|list|definition|formula|other
  blanks      INTEGER DEFAULT 0,     -- quanti segnaposto [...] nel fronte
  source_ord  INTEGER                -- posizione originale nel mazzo importato
);

-- RACCOLTA: unità di studio e di baking (C9). Un content.sqlite = una raccolta.
-- SEZIONE:  un .apkg importato. Unità di provenienza e di rimozione, mai di studio.
CREATE TABLE sezione (
  uid        TEXT PRIMARY KEY,
  name       TEXT NOT NULL,          -- rinominabile dall'utente all'import e dopo
  src_name   TEXT,                   -- nome del file originale, solo informativo
  imported_at INTEGER,
  card_count INTEGER
);

-- Molti-a-molti: se due .apkg contengono la stessa card (accade spesso con i
-- mazzi condivisi), la card è UNA sola e appartiene a entrambe le sezioni.
-- Rimuovere una sezione cancella le appartenenze, non le card: una card sparisce
-- solo quando resta senza sezioni.
CREATE TABLE card_sezione (
  card_uid    TEXT NOT NULL REFERENCES card(uid),
  sezione_uid TEXT NOT NULL REFERENCES sezione(uid) ON DELETE CASCADE,
  PRIMARY KEY (card_uid, sezione_uid)
);

CREATE TABLE distractor (
  card_uid    TEXT NOT NULL REFERENCES card(uid),
  text        TEXT NOT NULL,
  origin      TEXT NOT NULL,         -- rule | llm | sibling      (C5)
  quality     REAL,                  -- 0..1, stima del baker
  gen_version TEXT NOT NULL,
  PRIMARY KEY (card_uid, text)
);

CREATE TABLE neighbor (               -- vicini semantici precalcolati
  card_uid    TEXT NOT NULL REFERENCES card(uid),
  other_uid   TEXT NOT NULL REFERENCES card(uid),
  rank        INTEGER NOT NULL,      -- 0 = il più vicino
  sim         REAL NOT NULL,
  PRIMARY KEY (card_uid, rank)
);

-- "La risposta di other_uid NON può essere mostrata come distrattore per card_uid,
--  perché è anch'essa corretta." Precalcolata sul desktop: il telefono non ha
--  modo di accorgersene da solo. Vedi C10 e §5.8.
CREATE TABLE exclusion (
  card_uid  TEXT NOT NULL REFERENCES card(uid),
  other_uid TEXT NOT NULL REFERENCES card(uid),
  reason    TEXT,                    -- equivalent | same_question | user_burned
  PRIMARY KEY (card_uid, other_uid)
);
```

`neighbor` è il pezzo che fa la differenza fra un quiz Quizlet-grade e un
quiz scadente: il campionamento a runtime pesca dai vicini in classifica, non
a caso nel mazzo. Sono ~10 righe per card, costo trascurabile.

### 4.2 `state.sqlite` (solo telefono, mai sovrascritto dall'import)

```sql
CREATE TABLE review_state (
  card_uid    TEXT PRIMARY KEY,
  stability   REAL, difficulty REAL,        -- parametri FSRS
  due         INTEGER,                      -- epoch millis
  reps        INTEGER, lapses INTEGER,
  last_review INTEGER
);

CREATE TABLE review_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  card_uid TEXT NOT NULL,
  ts INTEGER NOT NULL,
  mode TEXT NOT NULL,        -- recall | mcq | match | truefalse | typed
  grade INTEGER NOT NULL,    -- 1..4
  elapsed_ms INTEGER
);

CREATE TABLE burned_distractor (   -- segnalati dall'utente come sbagliati/ambigui
  card_uid TEXT NOT NULL,
  text TEXT NOT NULL,
  reason TEXT,
  ts INTEGER NOT NULL,
  PRIMARY KEY (card_uid, text)
);
```

Il join fra i due DB avviene in Kotlin su `card_uid`, oppure con `ATTACH` se
serve una query unica. Due `RoomDatabase` distinti, non uno.

Si noti che `review_state` è indicizzato **solo** sul `card_uid`: non sa nulla
di raccolte né di sezioni. È la conseguenza pratica di C11 (§4.3).

### 4.3 Cosa è identità e cosa no

Una sola identità nel sistema trasporta valore: **il `card_uid`**. È l'unica
cosa a cui sono agganciati i progressi, e l'unica che deve essere calcolata in
modo identico dal baker in Python e dall'app in Kotlin (§5.1).

Raccolte e sezioni sono **contenitori organizzativi**. La loro identità è
locale al dispositivo che li ha creati e non deve concordare con niente:

- Se crei la raccolta "Reti" sul telefono e il PC ne ha una omonima ma con uid
  diverso, all'import il `.qzd` propone: *"Questo pacchetto appartiene alla
  raccolta «Reti». Unire alla tua «Reti» o crearne una nuova?"* — e qualunque
  cosa risponda l'utente, **i progressi non si muovono**, perché sono attaccati
  alle card.
- Rinominare una raccolta o una sezione non ha alcun effetto sui dati.
- Spostare una sezione da una raccolta all'altra cambia solo cosa entra nel
  bacino dei distrattori.

Il caso peggiore di un disallineamento è un contenitore duplicato che l'utente
rinomina o unisce in dieci secondi. È una proprietà che va difesa: ogni volta
che si è tentati di agganciare qualcosa al `raccolta_uid` — statistiche,
impostazioni di studio, stato FSRS — si sta creando una seconda identità
load-bearing, e con essa una nuova classe di bug in cui l'utente perde
progressi riorganizzando i propri mazzi.

---

## 5. La pipeline di baking (desktop)

Sette stadi, ognuno idempotente e ispezionabile da CLI. Ogni stadio scrive il
suo output nel DB di lavoro, così un errore al passo 5 non costringe a
rifare l'embedding.

```bash
baker ingest mazzo.apkg --out work.db
baker classify work.db
baker index work.db --model multilingual-e5-small
baker generate work.db --llm qwen3:4b --seed 42
baker validate work.db
baker export work.db --out Neuroanatomia.qzd
# oppure, tutto insieme:
baker bake mazzo.apkg --out Neuroanatomia.qzd
```

### 5.1 Ingest

Sorgenti supportate, in ordine di priorità:

1. **CSV/TSV** — il minimo indispensabile, due colonne.
2. **Markdown** — `Q: … / A: …` oppure tabelle. Comodo per scrivere a mano.
3. **`.apkg` di Anki** — zip contenente `collection.anki2` (SQLite). I campi
   della nota sono in `notes.flds` separati da `\x1f`; il modello di nota
   (`notetype`) dice quale campo è fronte e quale retro. Da qui arrivano i
   mazzi condivisi già fatti: è la fonte che dà valore immediato all'app.

#### La ricetta dell'`uid` (C8)

L'`uid` non è un UUID casuale: è **derivato dal contenuto**, perché lo stesso
`.apkg` deve produrre gli stessi identificatori sia quando lo ingerisce il
baker in Python, sia quando lo apre l'app in Kotlin (§6.1). È il perno che
permette a un mazzo importato al volo sul telefono di essere poi *arricchito*
dal `.qzd` cotto sul PC senza perdere i progressi.

```
uid = base32( sha256( "v1|" + hnorm(front) ) )[:26]

hnorm(s):  strip dei tag HTML  →  unescape entità  →  NFC
           →  lowercase  →  collasso di tutti gli spazi in uno  →  trim
```

`hnorm` è volutamente **più povera** della normalizzazione di §5.2: niente
scorporo glosse, niente apici tipografici, niente euristiche. Ogni euristica in
più è un punto in cui le due implementazioni possono divergere, e una
divergenza qui significa progressi persi. La ricetta è congelata: se un giorno
dovesse cambiare, cambia il prefisso (`v2|`) e il baker emette una mappa di
migrazione `uid_v1 → uid_v2` dentro il `.qzd`.

Nota: l'`uid` dipende dal solo fronte. Correggere un refuso nella risposta
mantiene i progressi (giusto); riscrivere la domanda crea una card nuova
(discutibile ma accettabile: una domanda riformulata è, ai fini della memoria,
un'altra domanda).

### 5.2 Normalize

Strip HTML dai campi Anki (`<div>`, `<br>` → newline), unescape delle entità
(`&nbsp;` → U+00A0 → spazio ordinario: nel mazzo di prova ce ne sono in quasi
tutte le card), normalizzazione Unicode NFC, apici tipografici → ASCII,
collasso degli spazi, rilevamento cloze `{{c1::…}}`.

Qui avviene anche lo **scorporo della glossa**, che nel mazzo di prova
riguarda 16 card su 25:

| `back` originale | `answer_core` | `answer_note` |
|---|---|---|
| `IEEE (Institute of Electrical and Electronics Engineers)` | `IEEE` | `Institute of…` |
| `Application layer\n*also called Layer 7 due to the OSI model` | `Application layer` | `also called Layer 7…` |
| `segment or datagram\n(segment when using TCP, datagram…)` | `segment or datagram` | `segment when using TCP…` |

Regole di taglio: parentesi finale che segue il nucleo, riga che inizia con
`*`, riga tra parentesi dopo un a capo. Il core è ciò che il quiz confronta e
imita; la nota si mostra solo **dopo** che l'utente ha risposto. Senza questo
scorporo l'LLM dovrebbe inventare acronimi con espansione plausibile — lavoro
sprecato su una parte di testo che non è la risposta.

Il taglio è euristico e va ispezionato: `baker normalize --review` stampa le
coppie core/nota per revisione manuale, ed esiste un override per card.

### 5.3 Classify

Puro euristico, niente LLM. Determina `answer_type`:

| Tipo | Riconoscimento |
|---|---|
| `numeric` | regex su numero + unità opzionale, eventualmente con separatori |
| `date` | pattern data, o anno isolato 3-4 cifre in range plausibile |
| `term` | ≤ 3 parole, nessun verbo coniugato |
| `list` | separatori ripetuti (`,` `;` `-` a inizio riga) con ≥ 3 elementi |
| `formula` | presenza di `=`, operatori, LaTeX |
| `definition` | ≥ 8 parole, struttura di frase |
| `other` | fallback |

Il segnaposto `[...]` nel fronte (10 card su 25 nel mazzo di prova) è un
segnale forte: la card è un riempimento di spazio bianco, quindi `term` o
`list`. Il numero di segnaposto finisce in `card.blanks`; con 2 o più,
la risposta è multipla (`segment or datagram`) e il quiz a scelta multipla
deve trattarla come un blocco unico, mai spezzarla.

Ogni card viene classificata e il conteggio finisce nel `manifest`. Se
`other` supera il 15% del mazzo, il classificatore va rivisto per quel dominio.

### 5.4 Neighbor index

Embedding di `back` (o di `front + back` — da valutare empiricamente) per
tutte le card, poi top-10 per similarità coseno. Con un modello piccolo
(`multilingual-e5-small` o `paraphrase-multilingual-MiniLM`, ordine dei
100-500 MB a seconda del formato — da verificare in fase M2) un mazzo da 500
card si indicizza in pochi secondi su CPU.

Fallback senza modello: TF-IDF su n-grammi di caratteri (3-5). Peggiore ma
gratuito, e sufficiente per mazzi molto omogenei.

### 5.5 Generate

Prima le regole, l'LLM solo su ciò che resta scoperto.

**Motore a regole** (nessun LLM, istantaneo):

- `numeric`: perturbazioni deterministiche — ×0.5, ×2, ±15%, cifre scambiate
  (`37,2` → `32,7`), ordine di grandezza (`×10`), unità coerente ma sbagliata.
  Si scartano i risultati che ricadono su un valore presente in un'altra card.
- `date`: ±1/±2/±5 anni, mese scambiato, date di card vicine nello stesso mazzo.
- `list`: si sostituisce un elemento con uno preso dalla lista di una card
  vicina. È il distrattore più cattivo che esista e costa zero.
- `formula`: segno invertito, esponente cambiato, fattore spostato.
- `term`: solo campionamento dai vicini (nessuna regola sensata).

**LLM** — chiamato per `definition`, `other`, e per le card di qualsiasi tipo
che dopo le regole hanno meno di 3 distrattori validi.

Prompt (una card per chiamata; il batching di più card degrada la qualità sui
modelli piccoli molto più di quanto faccia risparmiare):

```
Sei un autore di quiz. Genera 3 risposte SBAGLIATE ma credibili.

Domanda: {front}
Risposta corretta: {back}
Argomento del mazzo: {deck_name}
Concetti vicini nel mazzo: {neighbor_backs[:3]}

Regole tassative:
- Ogni risposta deve essere FALSA per la domanda data.
- Nessun sinonimo o parafrasi della risposta corretta.
- Stessa lunghezza (± 20%), stesso registro, stessa lingua della corretta.
- Errori plausibili: confusione con un concetto vicino, dettaglio alterato,
  causa/effetto invertiti. Mai assurdità o altre materie.
- Nessuna spiegazione, nessuna numerazione, nessun preambolo.

Rispondi con un array JSON di 3 stringhe.
```

Output vincolato — con llama.cpp una grammatica GBNF, con Ollama il parametro
`format` con lo schema JSON. Non opzionale: i modelli da 1-4B senza vincolo
producono preamboli e numerazioni nel 30-40% dei casi.

```gbnf
root   ::= "[" ws string ws "," ws string ws "," ws string ws "]"
string ::= "\"" ([^"\\] | "\\" .)* "\""
ws     ::= [ \t\n]*
```

### 5.6 Validate

Ogni distrattore candidato passa da qui. Scartato se:

1. **Troppo simile alla risposta corretta** — similarità coseno degli embedding
   sopra soglia (~0.85, da tarare) oppure Levenshtein normalizzata su
   `core_norm` sopra 0.85. Questo è il filtro che impedisce il caso peggiore:
   il distrattore che è in realtà corretto.
2. **È la risposta corretta di un'altra card che risponde alla stessa cosa
   detta in altro modo.** Non basta confrontare le domande: vedi §5.8. Qui si
   popola la tabella `exclusion`, ed è l'unico stadio in cui il problema è
   risolvibile, perché il telefono non ha né embedding né modello (C10).
3. **Duplicato** di un altro distrattore dello stesso pool.
4. **Anomalo per lunghezza** — fuori dal ±40% rispetto alla corretta. Il
   giveaway di lunghezza è il modo più comune in cui i quiz MCQ si rompono:
   l'utente impara a scegliere l'opzione più lunga e precisa.
5. **Pattern vietati** — "nessuna delle precedenti", "tutte le precedenti",
   "non lo so", stringhe che ripetono la domanda.
6. **Lingua sbagliata** — controllo rapido, i modelli piccoli scivolano in
   inglese.

#### Una lezione che vale per tutte le soglie

Le regole 1 e 3 confrontano stringhe, e in M1 questo ha prodotto due difetti
gravi, entrambi dalla stessa radice.

La prima versione delle esclusioni scartava le coppie con distanza di edit
oltre 0.85: sul mazzo di riferimento questo vietava «Layer 2» come distrattore
di «Layer 1» -- un carattere su sette -- cioè esattamente le opzioni migliori,
svuotando i quiz di metà mazzo. La prima versione della validazione, con la
stessa soglia, scartava `25/05/2019` come distrattore di `25/05/2018`.

**La vicinanza lessicale non è vicinanza semantica, e sulle risposte
strutturate le due cose sono spesso in opposizione.** Più due numeri, due date
o due formule si somigliano nella forma, più è probabile che siano gli elementi
contrapposti di una stessa serie -- cioè esattamente i distrattori che si
vogliono. Da qui due regole:

- le soglie su distanze di stringhe si applicano **solo alla prosa**
  (`term`, `definition`, `list`, `other`);
- per `numeric`, `date` e `formula` il confronto è di **uguaglianza esatta**
  su una normalizzazione che conserva i simboli. `cnorm` toglie la
  punteggiatura, e in `H = 2^n - 2` la punteggiatura *è* la risposta: ridotta
  a "h 2 n 2" diventa indistinguibile da `H = 2^n + 2`.

Il `quality` residuo è una funzione della similarità (né troppo alta né troppo
bassa: il distrattore ideale sta in una fascia intermedia) e serve all'app per
scegliere quali opzioni usare per un quiz "difficile".

Se dopo la validazione restano < 2 distrattori, la card viene marcata come
`sibling_only` e a runtime userà solo il campionamento (C4).

### 5.7 Export

`work.db` → `content.sqlite` (solo le colonne necessarie, `VACUUM`), più
`manifest.json`, zippati. Obiettivo dimensione: un mazzo da 500 card con 6
distrattori l'uno sta abbondantemente sotto il megabyte.

### 5.8 Il mazzo di riferimento

`Day 03 Flashcards - TCP-IP.apkg` — 25 card, notetype `Front/Back` semplice,
nessun media, nessun tag, formato Anki v3 (`collection.anki21b`, cioè lo
SQLite compresso con zstd; `collection.anki2` nello stesso zip è solo uno stub
per i client vecchi e contiene una card fittizia — ingerire quello significa
importare un mazzo vuoto).

È il mazzo su cui si tarano gli stadi, per tre ragioni.

**Conferma la tesi.** Le card 4-8 sono `"Layer N?" → Physical / Local Network /
Internet / Transport / Application`. Per la card 4 i tre distrattori ideali
sono le risposte delle card 5, 6, 7: il campionamento dai vicini li trova al
primo colpo e nessun modello generativo farebbe meglio. Su 25 card, la stima è
che l'LLM serva su 5-6.

**Contiene la trappola di C10.** Le card 4-8 e 9-13 sono due serie parallele
sullo stesso contenuto:

```
card 04:  "In the 5-layer model, what is Layer 3?"                  → "Internet layer (a.k.a. Network layer)"
card 11:  "Which layer provides end-to-end communication … using IP
           addresses and routers?"                                  → "Layer 3 (Internet layer, a.k.a. Network layer)"
```

Le domande non si somigliano affatto, quindi un confronto sui fronti non
scatta. Ma se il quiz sulla card 4 pesca la risposta della card 11 come
distrattore, sta offrendo come sbagliata una risposta **giusta** — l'errore
peggiore che un quiz possa fare, perché punisce chi ha capito. La cattura
avviene solo confrontando le *risposte* fra loro, con gli embedding, in fase
di validazione, e va spedita al telefono già risolta.

**Rompe le assunzioni comode.** 16 risposte su 25 hanno una glossa da
scorporare (§5.2); 10 fronti contengono `[...]`; le card 17 e 22 hanno risposta
doppia (`segment or datagram`); tutte hanno `&nbsp;` nel testo. Un ingest che
non gestisce questi quattro casi produce distrattori ridicoli su un mazzo
perfettamente ordinario.

**Criterio di uscita da M1:** su questo mazzo, senza LLM, ogni card ha ≥ 3
distrattori validi, `exclusion` contiene almeno le coppie 4↔11 e affini, e una
lettura manuale dei 25 quiz non trova nemmeno un'opzione corretta spacciata
per sbagliata.

---

## 6. L'app Android

### 6.1 Import: due porte, un solo `uid`

L'app apre **due formati**, e la differenza fra i due è tutta la storia del
prodotto.

| | `.apkg` (Anki) | `.qzd` (cotto dal baker) |
|---|---|---|
| Da dove arriva | scaricato dal telefono, condiviso, esportato da Anki | dal PC, via file o LAN (§6.7) |
| Cosa contiene | fronte e retro | + tipi, gruppi, pool di distrattori, vicini, esclusioni |
| Cosa abilita | flashcard con swipe, FSRS | anche i quiz |

**L'app è utile dal primo minuto senza PC.** Importi un `.apkg` qualsiasi e
studi. Il PC non è un requisito: è l'aggiornamento che sblocca i quiz.

L'`.apkg` sul telefono viene letto in modo deliberatamente **stupido**:
fronte, retro, nome del mazzo, `uid` con la ricetta di §5.1. Nessuna
classificazione, nessuno scorporo di glosse, nessuna euristica — tutto ciò che
il quiz userà viene calcolato **solo** sul desktop (C3). Non è pigrizia: è che
due implementazioni della stessa euristica in due linguaggi divergono, e la
divergenza qui costerebbe i progressi. Sul telefono vive solo `hnorm`, che è
banale e verificabile con un test incrociato.

Quando poi arriva il `.qzd` della stessa collezione, le card **si arricchiscono
sul posto**: stesso `uid`, quindi `state.db` non se ne accorge nemmeno. Ciò che
prima era una flashcard nuda ora ha tipo, gruppi e pool, e la voce "Quiz"
compare nel menu. I progressi di quelle settimane di swipe restano interi (C2).

Meccanica comune a entrambi: SAF (come VoxLocale con i modelli), scompattamento
nella storage privata, verifica di `schema_version` (C6), fusione con lo stato
esistente. Le card sparite dal mazzo restano nello stato ma non vengono più
schedulate — soft delete: se tornano, i progressi sono lì.

#### Il dialogo di import

Importare un `.apkg` non è un'operazione muta: crea una **sezione**, e l'utente
decide dove finisce.

```
┌─ Importa  «Day 03 Flashcards - TCP-IP.apkg» ─────────┐
│  25 card riconosciute                                │
│                                                      │
│  Nome della sezione                                  │
│  ┌────────────────────────────────────────────────┐  │
│  │ Day 03 - TCP/IP                                │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  Raccolta                                            │
│   ● Reti                        86 card, quiz pronti │
│   ○ Sistemi operativi          120 card, quiz pronti │
│   ○ + Nuova raccolta…                                │
│                                                      │
│  ⓘ 3 card sono già presenti in «Routing basics».     │
│    Non verranno duplicate.                           │
│                                                      │
│                             [ Annulla ]  [ Importa ] │
└──────────────────────────────────────────────────────┘
```

Il nome proposto è quello del mazzo Anki (o del file), modificabile. La nota
sui duplicati non è un avviso di errore: è la conseguenza normale di
`card_sezione` molti-a-molti, e va detta perché l'utente capisca perché il
conteggio della raccolta non è la somma delle sezioni.

**Scegliere la raccolta è la decisione che conta**, e vale la pena che l'utente
lo sappia: la raccolta è il bacino da cui il quiz pesca i distrattori (C9).
Mettere TCP/IP e Routing nella stessa raccolta produce quiz migliori perché i
concetti si confondono a vicenda; buttarci dentro anche lo spagnolo non fa
danni — i vicini restano in tema — ma non aggiunge niente, e allunga il baking.
Una riga di aiuto sotto il selettore basta: *"Le raccolte sono il bacino dei
quiz: tienici dentro argomenti che si somigliano."*

#### Aggiungere una sezione invalida i quiz della raccolta

Vicini ed esclusioni sono calcolati sull'intera raccolta: aggiungere 25 card ne
cambia la topologia. Le card nuove non hanno pool, e quelle vecchie hanno una
classifica di vicini che le ignora.

Non è un errore ed è tollerato per progetto: le card nuove ricadono sul
campionamento (C4), e la raccolta si segna come *da ricuocere*. L'app mostra
lo stato senza allarmismi — `Reti · 111 card · quiz aggiornati a 86` — e quel
numero è ciò che si porta dietro al PC per il prossimo `POST /bake` (§6.7).

Un dettaglio tecnico emerso in M2: `collection.anki21b` è zstd, e sul telefono
serve un decompressore. L'opzione **pura JVM** provata (`aircompressor`) è
stata scartata dopo test reali su Android: il bytecode gira, ma la libreria si
appoggia a metodi di `sun.misc.Unsafe` che l'ART espone solo in parte (prima
mancava `ARRAY_BYTE_BASE_OFFSET`, patchato il campo e ne è saltato fuori un
secondo su `copyMemory`: non è un bug isolato, è l'intera libreria a non
starci). La scelta operativa è `com.github.luben:zstd-jni`, con un dettaglio
che vale la pena scrivere perché non è ovvio: va dichiarato con la coordinata
`...:zstd-jni:VERSIONE@aar`, non come jar nudo — il jar nudo contiene solo le
build desktop/server (linux/darwin/win, layout non riconosciuto da AGP),
mentre le `.so` per arm64-v8a/armeabi-v7a/x86/x86_64 stanno solo nell'AAR
separato. Verificato che si impacchetta davvero (`lib/arm64-v8a/libzstd-jni*.so`
nell'APK) e che decomprime un `.apkg` reale su device fisico.

### 6.2 Modalità di studio

| Modalità | Cosa mostra | Da dove vengono le opzioni |
|---|---|---|
| **Richiamo libero** | Fronte → l'utente pensa → tap → retro → **swipe** | — |
| **Scelta multipla** | Fronte + 4 opzioni | pool + vicini |
| **Abbinamento** | 5 fronti ↔ 5 retri mescolati | 5 card vicine fra loro |
| **Vero/falso** | Fronte + una risposta, corretta o di un'altra card | vicini |
| **Scritto** | Fronte + campo di testo | confronto su `back_norm` con tolleranza |

Le ultime quattro sono tutte alimentate dallo stesso `QuizAssembler`.

#### Lo swipe e i quattro voti di FSRS

Il gesto è binario — **sinistra = non la so, destra = la so** — mentre FSRS
lavora con quattro voti. La mappatura:

| Gesto | Voto FSRS | |
|---|---|---|
| swipe ← | 1 `Again` | la card rientra in coda nella stessa sessione |
| swipe → | 3 `Good` | il caso normale |
| swipe ↓ (opzionale) | 2 `Hard` | la sapevo, ma a fatica |
| swipe ↑ (opzionale) | 4 `Easy` | immediata, allunga molto l'intervallo |

Le due diagonali verticali sono un'aggiunta, non un requisito: FSRS funziona
benissimo con i soli `Again`/`Good`, e perde molto meno di quanto si creda
rispetto ai quattro voti — mentre un'interfaccia a due gesti si usa con una
mano sola, in piedi sull'autobus, che è dove le flashcard si usano davvero.
Le due opzionali arrivano dopo, come scorciatoia per chi le vuole, e devono
restare facoltative: se sbagliare gesto costa un intervallo, l'utente rallenta
e la modalità perde il suo unico vantaggio.

Un accorgimento sul gesto: il retro si rivela con un tap, e lo swipe è
accettato **solo dopo** la rivelazione. Altrimenti si finisce per giudicare
card che non si è ancora provato a ricordare, e il richiamo — l'unica cosa che
consolida — non avviene.

### 6.3 Assemblaggio del quiz (a runtime, sull'app)

**Si studia una raccolta intera.** Non c'è selezione di sezioni all'avvio dello
studio: si apre una raccolta e si studia quella. È la scelta che rende
coerenti scheduler e quiz — lo scheduler vuole vedere tutte le card scadute,
il quiz vuole il bacino più largo possibile, e sono lo stesso insieme.

La sezione resta un'unità di *gestione* (rinomina, rimozione, conteggi), non di
studio. Il modello dati (`card_sezione`) è comunque in grado di filtrare, se un
giorno servisse un "ripassa solo questa sezione"; ma non è nel primo giro, e
finché non c'è una richiesta concreta non si aggiunge il selettore.

#### La soglia: quando un quiz ha senso

Un quiz a scelta multipla ha bisogno di un bacino. Con 8 card nella raccolta,
le risposte disponibili come distrattori sono 7, e dopo tre domande l'utente le
ha viste tutte: sta imparando l'insieme delle opzioni, non il contenuto.

| Card nella raccolta | Comportamento |
|---|---|
| < 8 | Quiz non offerto. Messaggio esplicito: "aggiungi altre sezioni a questa raccolta" |
| 8-19 | Quiz disponibile **solo se** i pool sono cotti (i distrattori generati non si esauriscono come i vicini) |
| ≥ 20 | Quiz sempre disponibile, anche col solo campionamento |

È la formalizzazione della "quantità discreta di flashcard". Nota che il
messaggio sotto soglia indica la via d'uscita giusta — **unire più materiale
nella stessa raccolta** — che è anche ciò che migliora i quiz in generale.

```
R = la raccolta aperta
scegli card C fra quelle di R, secondo lo scheduler
opzioni = []
prendi da distractor(C) i migliori per quality, escludendo i burned  → fino a 2
prendi da neighbor(C) le risposte (answer_core) delle card vicine,
   escludendo exclusion(C) e i vicini sotto la soglia di similarità
   (sorteggio **pesato sulla similarità** nei primi 5: varia i ripassi
    senza sprecare il vicino migliore)                               → fino a 2
se opzioni < 3: riempi da R, prima con card dello **stesso answer_type**
   (per una domanda numerica un altro numero si legge, una data no),
   sempre escludendo exclusion(C)
scarta i duplicati, le opzioni uguali alla risposta di C, e quelle di
   lunghezza anomala -- il filtro di forma vale anche sul campionamento,
   non solo sul pool; se resta sotto le 4 opzioni, si ripete senza
shuffle(opzioni + [answer_core di C])
```

Il mix voluto è ~50% pool / ~50% vicini: mescolare le due provenienze rende
il quiz meno riconoscibile e maschera gli eventuali difetti dell'LLM. Costo:
due `SELECT` indicizzate. Latenza percepita zero (C3).

Le sezioni non compaiono da nessuna parte in questo algoritmo, ed è voluto: una
card di "Day 03 - TCP/IP" può benissimo ricevere come distrattore la risposta
di una card di "Routing basics", se è quella semanticamente più vicina. È
esattamente il motivo per cui mettere argomenti affini nella stessa raccolta
migliora i quiz.

### 6.4 Gestione delle raccolte e delle sezioni

Dentro una raccolta le sezioni restano distinte e ispezionabili:

```
Reti                                        111 card · quiz aggiornati a 86
├── Day 03 - TCP/IP        25 card   importata il 12/08   [rinomina] [rimuovi]
├── Routing basics         61 card   importata il 03/08   [rinomina] [rimuovi]
└── Subnetting             28 card   importata il 12/08   [rinomina] [rimuovi]
                                     ⚠ non ancora nei quiz
```

**Rimuovere una sezione** cancella le appartenenze in `card_sezione`, non le
card: una card sparisce solo quando resta senza sezioni (§4.1). Cascata sulle
righe di `neighbor` ed `exclusion` che puntano a card sparite — il quiz continua
a funzionare con classifiche di vicini più corte, e un ricottura le ricompone.

**Ed è reversibile.** Le righe di `review_state` restano (C2, soft delete):
reimportare lo stesso `.apkg` ridà gli stessi `card_uid` (C8) e con essi i
progressi. Vale la pena dirlo nel dialogo di conferma, perché cambia
completamente la percezione del pulsante:

> Rimuovi «Subnetting» dalla raccolta Reti?
> Le 28 card escono dallo studio. I progressi vengono conservati: se
> reimporti la sezione, li ritrovi.

Se invece si vuole davvero azzerare, serve un'azione separata ed esplicita
("dimentica i progressi"), mai come effetto collaterale di una rimozione.

### 6.5 Scheduling

FSRS, l'algoritmo che Anki usa oggi. È un modello a due variabili di stato
(`stability`, `difficulty`) con parametri liberi già pubblicati: il codice è
qualche centinaio di righe. In fase M4 si verifica se esiste un port
JVM/Kotlin riusabile prima di scriverlo; l'implementazione diretta dalla
specifica di riferimento resta il piano B ed è alla portata.

**Il quiz non guida lo scheduler.** La scelta multipla misura riconoscimento,
il richiamo libero misura recupero: sono cose diverse e il secondo è quello
che consolida. Regola operativa:

- il grade del **richiamo libero** e della **risposta scritta** aggiorna FSRS
  normalmente;
- il grade di **MCQ / abbinamento / vero-falso** può solo *confermare*
  (piccolo bonus di stabilità se corretto) e non promuove una card a
  intervalli lunghi da sola; una risposta sbagliata invece conta pienamente,
  perché sbagliare pur avendo le opzioni davanti è informativo.

### 6.6 Il ritorno di informazione verso il desktop

Il pulsante "opzione sbagliata o ambigua" scrive in `burned_distractor`.
L'app esporta un `feedback.json` (via SAF) che il baker reingerisce:

```json
{ "deck_uid": "5f3c…",
  "burned": [ { "card_uid": "…", "text": "…", "reason": "corretta anche questa" } ] }
```

Il baker li mette in blacklist (`exclusion.reason = 'user_burned'`) e li
rigenera al bake successivo. È il correttivo più economico alla qualità dei
distrattori, e chiude il ciclo senza introdurre rete (C1): passa da un file,
come tutto il resto.

### 6.7 Trasferimento via LAN (variante `lan`, opzionale)

Il trasporto dei `.qzd` via cavo è scomodo. La variante `lan` aggiunge un
canale locale, **senza toccare nulla di quanto sopra**: continua a viaggiare
un `.qzd` già cotto, solo attraverso un socket invece che attraverso una
chiavetta.

```
PC:        baker serve --bind 192.168.1.x:8765 --pin 4821
             ├── annuncio mDNS  _ankicard._tcp.local
             ├── GET  /groups           elenco dei gruppi noti al PC + stato di cottura
             ├── POST /bake             "cuoci questi gruppi"  → 202 + job id
             ├── GET  /job/{id}         pronto? in coda? a che punto?
             ├── GET  /qzd/{id}         scarica l'artefatto
             └── POST /feedback         riceve il feedback.json (§6.6)
Telefono:  scopre il PC → PIN una volta → vede i gruppi → ne seleziona alcuni
           → "prepara il quiz" → il job parte sul PC → notifica a cottura finita
           → scarica e importa.
```

È questo il flusso che l'utente ha in mente quando dice "creo il quiz
collegandomi al PC": la selezione dei gruppi serve a dire al baker **cosa
cuocere**, non solo cosa studiare. Le due selezioni convivono e sono diverse:

| | Selezione di *baking* (PC) | Selezione di *sessione* (telefono) |
|---|---|---|
| Quando | una volta, quando aggiungi materiale | ogni volta che studi |
| A cosa serve | decidere su quali card far girare il modello | decidere cosa ripassare oggi |
| Costo | minuti di GPU/CPU | due `SELECT` |
| Serve il PC | sì | no (§6.3) |

Vincoli espliciti su questo canale:

- **Il baking resta asincrono.** `POST /bake` risponde `202` e mette in coda:
  il telefono non aspetta mai il modello, riceve una notifica quando è pronto
  (o semplicemente ricontrolla). Se il PC bakasse in modo sincrono, l'attesa
  del modello rientrerebbe dalla finestra dopo essere stata cacciata dalla
  porta (§1).
- **Il server è sul PC, mai sul telefono.** Nessun listener su Android,
  nessun servizio in background, nessun `FOREGROUND_SERVICE`.
- **Solo LAN, con PIN.** Accoppiamento una tantum con PIN a 4 cifre mostrato
  dal baker, poi un token in `state.db`. HTTP in chiaro su rete locale è
  accettabile per contenuto di studio; se un giorno non lo fosse, TLS con
  certificato autofirmato pinnato al primo accoppiamento.
- **Due varianti di APK**, come in VoxLocale: `offline` (zero permessi) e
  `lan` (solo `INTERNET`). Stesso codice, il modulo di rete è dietro un flag
  di build. Chi non vuole il permesso non lo installa (C1).

Questa è la parte che si può tagliare in qualsiasi momento senza che il resto
ne risenta: è comodità di trasporto, non funzionalità.

---

## 7. Stack

**Desktop (baker)** — Python 3.12. CLI con `argparse`/`typer`, `sqlite3` da
stdlib, `fastembed` o `sentence-transformers` per gli embedding.

Per l'LLM, due backend dietro la stessa interfaccia:

- **Ollama** via HTTP locale: la strada corta, `format` per l'output JSON.
- **llama-cpp-python**: nessun servizio da tenere acceso, grammatiche GBNF
  native, seed controllabile per C7.

Modelli candidati (2-5 GB in q4, tutti adeguati al compito): Qwen3 4B,
Gemma 3 4B, Phi-4-mini. Vale la pena confrontarli sullo stesso mazzo di prova
in M2 — su un compito così vincolato le differenze sono misurabili.

**Android** — Kotlin, Jetpack Compose, Room (due database), minSdk 29 come nel
progetto precedente. Nessuna dipendenza nativa, nessun permesso.

---

## 8. Milestone

| | Contenuto | Verificabile quando |
|---|---|---|
| **M0** ✅ | `baker ingest` (CSV + apkg v1/v2/v3) + `normalize` con scorporo glosse + `classify` | fatto: 25 card, 16 glosse scorporate, `other` al 4%, 25 test verdi |
| **M1** ✅ | Generatore a **regole** + `validate` + `export` + `preview` | fatto: 10 esclusioni corrette, 25 quiz da 4 opzioni su 8 semi, nessuna risposta giusta fra le sbagliate |
| **M2** 🔶 | App Android: import `.apkg` **e** `.qzd`, flashcard con swipe, FSRS, MCQ multi-gruppo con soglia | fatto e verificato su device vero: import `.apkg` (25/25 card, zstd via `zstd-jni`), tap-per-girare, swipe gated dietro il flip, scheduler placeholder (`SimpleScheduler`, non FSRS). Mancano: import `.qzd`, FSRS vero, MCQ, raccolte/sezioni |
| **M3** | `neighbor index` con embedding + tabella `exclusion` | le coppie 4↔11 del mazzo di prova non si presentano più a vicenda |
| **M4** ✅ | Generazione LLM (Ollama, `qwen3:4b`, `think:false`, schema JSON) + validazione | fatto: 1/25 card LLM sul mazzo di riferimento (le altre 24 `term` restano a campionamento, per progetto), determinismo verificato bit a bit, 57 test verdi. Trovato e corretto in corsa: l'LLM può proporre una formula matematicamente equivalente scambiando operandi commutativi ("RTT x banda" non è un distrattore di "banda x RTT") — `validate.py` ora lo riconosce |
| **M5** | Abbinamento, vero/falso, risposta scritta, burn + `feedback.json` | ciclo chiuso desktop ↔ telefono via file |
| **M6** | Variante `lan`: `baker serve`, mDNS, accoppiamento con PIN | il mazzo arriva sul telefono senza cavo, e la variante `offline` continua a esistere |

M1+M2 sono già un prodotto. Tutto ciò che viene dopo migliora la qualità dei
distrattori, non l'esistenza del quiz — ed è esattamente il motivo per cui
l'LLM arriva in M4 e non in M0.

---

## 9. Decisioni rinviate

- **Media (immagini/audio) nelle card.** I mazzi Anki ne sono pieni. Rinviato
  a dopo M5, ma `media/` è già previsto nel formato per non doverlo
  rivoluzionare.
- **Cloze.** Riconosciuto in normalize, ma il rendering e il quiz sui cloze
  sono un capitolo a sé (il distrattore per un cloze è un problema diverso:
  serve una parola che stia nella frase).
- **Sync bidirezionale vero.** Anche con la LAN (M6) il contenuto va solo
  desktop → telefono, e dal telefono torna indietro solo il feedback sui
  distrattori. Sincronizzare i *progressi* fra due dispositivi è un problema
  diverso (risoluzione dei conflitti sul `review_log`) e non si affronta finché
  non serve davvero. La separazione content/state (C2) è già la fondazione
  giusta per farlo.
- **GUI per il baker.** Prima la CLI funzionante, poi eventualmente un
  frontend. La CLI resta comunque l'interfaccia autorevole.
- **Nome definitivo e `applicationId`.** Prima di M2.

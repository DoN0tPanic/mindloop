# baker

La meta' desktop del sistema descritto in [../PLAN.md](../PLAN.md): prende
mazzi di flashcard, li normalizza, li classifica e — dalla milestone M1 in
poi — genera i distrattori dei quiz, esportando un `.qzd` che l'app Android
legge senza aver bisogno di alcun modello.

**Stato: M1 completa.** Ingest, normalize, classify, index, generate (a
regole), validate, export, preview. Nessun LLM: arriva in M4.

## Requisiti

Python 3.14, nessuna dipendenza esterna. La decompressione zstd degli export
Anki v3 usa `compression.zstd` della libreria standard; su Python piu' vecchio
serve il pacchetto `zstandard`.

## Uso

```bash
python -m baker bake ../testdata/misto.csv --out work/prova.db --raccolta "Reti"
```

`bake` e' la scorciatoia per tutti gli stadi in fila (con `--qzd` esporta
anche l'artefatto). Presi singolarmente:

```bash
python -m baker ingest    mazzo.apkg --out work/reti.db --raccolta "Reti" --sezione "TCP/IP"
python -m baker normalize work/reti.db --review     # nucleo e glossa
python -m baker classify  work/reti.db --review     # tipo della risposta
python -m baker index     work/reti.db --review     # classifica dei vicini
python -m baker generate  work/reti.db --review     # distrattori a regole
python -m baker validate  work/reti.db              # esclusioni e filtro
python -m baker preview   work/reti.db              # i quiz, come li vedra' il telefono
python -m baker export    work/reti.db --out Reti.qzd
```

`preview` e' il comando che conta quando si giudica un mazzo: assembla i quiz
con lo stesso algoritmo che girera' in Kotlin (pool + vicini + esclusioni) e
li stampa da leggere uno per uno. Le statistiche dicono se la pipeline ha
girato; solo la lettura dice se i quiz sono buoni.

Ogni stadio e' idempotente e rieseguibile: `normalize` e `classify`
ricalcolano tutto da capo a partire dai campi grezzi, quindi il modo normale
di lavorare su un'euristica e' cambiarla e rilanciare lo stadio sullo stesso
`work.db`.

`--review` stampa card per card cio' che lo stadio ha prodotto. E' il modo
previsto per giudicare un'euristica: si guarda l'output su un mazzo vero.

## Formati in ingresso

| | |
|---|---|
| `.apkg` | export Anki v1, v2 e v3. Notetype con Fronte/Retro nei primi due campi |
| `.csv` `.tsv` | due colonne, intestazione facoltativa, campi multi-riga fra virgolette |

## Struttura

```
baker/
├── textnorm.py     hnorm (CONGELATA), cnorm, snorm, display_norm
├── uid.py          la ricetta dell'uid: l'unica identita' che porta valore
├── gloss.py        scorporo di nucleo e glossa dalla risposta
├── classify.py     tipo della risposta, euristico
├── similarity.py   TF-IDF su n-grammi, Levenshtein, insiemi di token
├── relations.py    vicini ed esclusioni fra card
├── rules/          il motore a regole: numeric, dates, lists, formulas
├── validate.py     quali distrattori sopravvivono
├── assemble.py     riferimento eseguibile del QuizAssembler (PLAN.md 6.3)
├── export.py       scrittura del .qzd
├── db.py           schema del work.db
├── pipeline.py     gli stadi
├── cli.py          interfaccia
└── sources/        lettori: apkg, csv/tsv
```

## Sulle soglie

Due difetti gravi di M1 avevano la stessa radice: una soglia su una distanza
di stringhe applicata a risposte strutturate. «Layer 1» e «Layer 2» distano un
carattere su sette e sono i distrattori perfetti l'uno dell'altro; `25/05/2018`
e `25/05/2019` idem.

Regola: **le distanze di stringhe si applicano solo alla prosa.** Per
`numeric`, `date` e `formula` il confronto è di uguaglianza esatta su `snorm`,
che conserva i simboli -- perché in `H = 2^n - 2` la punteggiatura è la
risposta.

## Cosa non si tocca senza pensarci

`textnorm.hnorm` e `uid.card_uid` sono **congelate**. Entrano nel calcolo del
`card_uid`, che e' l'unica cosa a cui sono agganciati i progressi di studio e
che l'app Android deve saper ricalcolare identico in Kotlin. Modificarle
significa che, al primo import successivo, ogni card cambia identita' e ogni
utente riparte da zero.

Se una modifica e' davvero necessaria: si cambia `UID_RECIPE_VERSION`, si
aggiorna il gemello Kotlin e si emette una mappa di migrazione nel `.qzd`.
Il test `TestUidFrozen` esiste per rendere impossibile farlo per sbaglio.

## Test

```bash
python -m unittest discover -s tests
```

I test sul mazzo di riferimento (`TestReferenceDeck`) si saltano da soli se il
file non e' presente; il percorso e' in testa a `tests/test_m0.py`.

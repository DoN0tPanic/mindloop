# Mindloop

Flashcard a ripetizione spaziata su Android, con quiz a scelta multipla
generati sul PC — anche da un modello linguistico locale, senza mandare
niente su Internet.

L'app da sola serve a studiare. Il PC serve solo quando vuoi trasformare un
mazzo in un quiz: l'app manda le card in rete locale, il PC le elabora e
restituisce il pacchetto. Il telefono non genera niente e non chiama nessun
servizio esterno.

## Le due metà

| | Cosa fa | Dove gira |
|---|---|---|
| **app** (`android/`) | importa mazzi `.apkg`, studio a flashcard, quiz | telefono Android (da Android 10) |
| **baker** (`baker/`) | costruisce i quiz e li serve in rete locale | PC, Python 3.11+ |

Fra le due passa un file `.qzd`: uno zip con dentro il manifesto e un
database SQLite già pronto da leggere.

## Perché è fatto così

Tre scelte spiegano quasi tutto il resto del codice.

**L'identità di una card è il testo della domanda, non un numero di riga.**
Si calcola con una ricetta congelata (`hnorm` + SHA-256) implementata due
volte, in Python e in Kotlin, che deve produrre lo stesso risultato byte per
byte. È ciò che permette di reimportare un mazzo senza perdere i progressi e
di agganciare un quiz alla raccolta giusta anche se i nomi non coincidono.
Se le due implementazioni divergessero, i progressi di studio si
scollegherebbero dalle card: per questo ci sono valori d'oro nei test da
entrambe le parti, e il server segnala ogni divergenza.

**Il telefono non genera mai niente durante lo studio.** Nessun modello,
nessuna rete nel percorso di studio: le domande arrivano già pronte. Serve a
rendere lo studio immediato e utilizzabile offline.

**Una risposta corretta non deve mai comparire fra le opzioni sbagliate.**
È il difetto peggiore possibile in un quiz, perché punisce chi ha capito. Il
generatore può produrre troppo: è il validatore a decidere, e in caso di
dubbio scarta. Meglio una domanda con due opzioni che una domanda che mente.

Il ragionamento completo, con i vincoli numerati, sta in [PLAN.md](PLAN.md).

## Provarlo

### App

```bash
cd android && ./gradlew :app:assembleDebug
```

L'APK esce in `app/build/outputs/apk/debug/`. Importa un `.apkg` e puoi già
studiare: per le flashcard il PC non serve.

### Baker come eseguibile (Windows)

Chi non vuole installare Python può usare `MindloopBaker.exe`: un solo file,
circa 10 MB. Doppio clic e parte il server, che stampa il codice di
accoppiamento e gli indirizzi da usare sul telefono. Con argomenti si
comporta come la riga di comando (`MindloopBaker.exe bake mazzo.apkg ...`).

L'eseguibile **non** include Ollama: se vuoi i quiz generati da un modello,
Ollama va installato a parte. Senza, il baker funziona lo stesso e produce i
distrattori con le sole regole.

Per ricostruirlo:

```bash
cd baker && python -m PyInstaller --onefile --console --name MindloopBaker mindloop_baker.py
```

PyInstaller serve solo a chi impacchetta, non a chi usa: il programma resta
senza dipendenze. Un avvertimento pratico: gli eseguibili in file singolo
fanno spesso insospettire gli antivirus, ed è normale che la prima apertura
richieda qualche secondo perché il contenuto viene estratto in una cartella
temporanea.

### Test

```bash
cd baker && python -m unittest discover tests
```

Alcuni test usano un mazzo Anki reale, che non sta nel repository perche' e'
materiale di terzi. Senza di esso si dichiarano saltati; per eseguirli basta
indicare dove si trova:

```bash
MINDLOOP_TEST_DECK=/percorso/del/mazzo.apkg python -m unittest discover tests
```

### Baker da sorgente

Nessuna dipendenza da installare su Python 3.14 o superiore. Su versioni
precedenti serve il pacchetto `zstandard`, e solo per leggere i mazzi Anki
in formato v3: dalla 3.14 quella decompressione è nella libreria standard.

```bash
cd baker
python -m baker.cli bake mazzo.apkg --out work.db --raccolta "Reti"
python -m baker.cli export work.db --out Reti.qzd
```

Con un modello locale (serve [Ollama](https://ollama.com) in esecuzione):

```bash
python -m baker.cli bake mazzo.apkg --out work.db --llm qwen3:4b
```

Sul mazzo di prova da 25 card la generazione con `qwen3:4b` richiede circa
dieci minuti: è il modello a costare, non la pipeline.

#### Cambiare modello

Va bene qualunque modello servito da Ollama: si passa con `--llm nome`. Un
modello più capace tende a dare distrattori migliori, ma vale la pena essere
precisi su **cosa** migliora e cosa no.

Migliora quello che dipende dal sapere e dall'obbedienza al prompt: la
plausibilità dei distrattori, il restare nella stessa categoria della
risposta (organizzazioni con organizzazioni, livelli con livelli) e nella
stessa forma (una sigla contro altre sigle, non contro i nomi per esteso).
Sono esattamente i difetti visti con `qwen3:4b`.

Non migliora ciò che non passa dal modello: la garanzia che una risposta
corretta non compaia mai fra le sbagliate resta del validatore, non del
modello, e le card la cui risposta non ha alternative sensate restano senza
distrattori generati per scelta.

Costa tempo: il grosso dei dieci minuti misurati è attesa del modello, e
cresce con la sua dimensione. Su un mazzo da qualche centinaio di card la
differenza fra un modello piccolo e uno grande si misura in ore.

Due requisiti tecnici: il modello deve accettare l'output guidato da schema
JSON, e se è un modello "ragionante" la risposta utile finisce nel campo
sbagliato a meno di disattivare quel comportamento (il baker lo fa già).

**Come capire se il modello nuovo sta davvero aiutando**, senza fidarsi a
occhio: la cottura stampa già i numeri che servono.

```
distrattori tenuti: 120
scartati:
  lunghezza-anomala              65
  uguale-alla-risposta            4
  alias-nella-nota                3
```

Gli scarti non sono sprechi: sono il motivo per cui il quiz resta onesto. Al
modello si chiedono più risposte sbagliate di quante ne servano proprio
perché i filtri ne tolgano una parte senza lasciare la domanda a corto di
opzioni.

Con lo stesso mazzo e lo stesso `--seed`, il confronto è onesto. Se salgono
i distrattori tenuti e scendono gli scarti per forma, il modello è
migliore per questo compito. Se sale solo il tempo, no.

Il confronto fra modelli diversi non è stato misurato: i numeri qui sopra
vengono tutti da `qwen3:4b`.

### Generare un quiz dal telefono

```bash
cd baker && python -m baker.cli serve
```

Il server stampa indirizzi e un codice di accoppiamento a 6 cifre. Sul
telefono: raccolta → **Genera quiz** → **Cerca il PC** → tocca il PC trovato
→ digita il codice. La generazione prosegue anche a schermo spento e l'app
avvisa quando il quiz è pronto.

Se la ricerca automatica non trova nulla, l'indirizzo si può scrivere a mano;
in quel caso controlla che il firewall del PC lasci passare la porta 8765.

## Sicurezza

Il server accetta dati e restituisce file su una rete che può essere
condivisa. Le difese in essere:

- **codice di accoppiamento a 6 cifre** su ogni richiesta, confrontato a
  tempo costante, con freno progressivo e blocco temporaneo dell'indirizzo
  dopo troppi tentativi falliti (senza il freno il codice si indovinava in
  una ventina di minuti: con il freno servono mesi);
- **tetto alla dimensione delle richieste** e timeout sulle connessioni, per
  non far esaurire memoria e thread;
- **traffico in chiaro consentito solo verso indirizzi di rete locale**,
  verificato anche a runtime prima di ogni chiamata;
- i pacchetti `.qzd` sono **file non fidati**: si leggono solo le due voci
  attese dallo zip, la versione dello schema viene verificata e i percorsi di
  destinazione sono validati per non uscire dalla cartella dell'app.

Il codice di accoppiamento non viaggia mai nella scoperta automatica: quella
dice soltanto che su quella rete c'è un PC disponibile.

## Pubblicare una versione

La chiave di firma non sta nel repository. Chi pubblica la genera una volta:

```bash
keytool -genkeypair -v -keystore mindloop.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mindloop
```

Poi copia `android/keystore.properties.example` in
`android/keystore.properties` e compila i quattro valori. Senza quel file la
build di rilascio funziona ma resta firmata con la chiave di debug e **non è
distribuibile**; il messaggio te lo ricorda durante la compilazione.

Quella chiave va conservata: se la perdi, non potrai più pubblicare
aggiornamenti installabili sopra le versioni già distribuite.

```bash
cd android && ./gradlew :app:assembleRelease
```

## Nome

Il progetto legge il formato `.apkg` di Anki ma non è Anki, non ne deriva e
non è affiliato: "Anki" è un marchio di Ankitects Pty Ltd. Da qui la scelta
di un nome proprio.

## Licenza

MIT — vedi [LICENSE](LICENSE).

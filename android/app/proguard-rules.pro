# Regole per la build di rilascio (R8).
#
# Ogni regola qui dentro deve avere un motivo scritto: una lista di -keep
# copiata a caso disattiva l'ottimizzazione senza che nessuno sappia piu'
# perche', e con il tempo nessuno osa piu' toglierne una.

# --- zstd-jni: obbligatoria, senza questa l'app muore all'import ---
#
# I mazzi Anki v3 sono compressi con zstd. La libreria e' meta' Java e meta'
# codice nativo, e il codice nativo cerca i campi Java PER NOME, con
# GetFieldID. R8 quei nomi li cambia, la ricerca dal lato nativo fallisce e il
# processo viene ucciso -- non e' un'eccezione Kotlin che si possa catturare,
# e' un crash del runtime:
#
#   Java_com_github_luben_zstd_ZstdInputStreamNoFinalizer_initDStream
#     -> art::JNI::GetFieldID -> FindFieldJNI -> ThrowNewExceptionF
#
# Va tenuto tutto, nomi compresi: campi e metodi, non solo le classi.
-keep class com.github.luben.zstd.** { *; }

# --- Room ---
#
# Le entita' e i DAO sono raggiunti dal codice generato, non per riflessione,
# quindi in teoria R8 li segue da solo. Le entita' pero' sono il punto in cui
# un errore non si vede subito: si manifesta come colonna mancante a runtime,
# su un database dell'utente. Il costo di tenerle e' trascurabile.
-keep class com.local.spacedcards.data.**.*Entity { *; }

# --- Rumore nei log di build ---
#
# Riferimenti opzionali di librerie che non usiamo: senza questa riga R8
# stampa avvisi su classi assenti che non hanno alcun effetto.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

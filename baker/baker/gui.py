"""Finestra di controllo del baker.

Chi usa il PC per generare i quiz non ha motivo di imparare una riga di
comando: gli serve scegliere il modello, far partire il server e vedere a che
punto e' la cottura. Questa finestra fa esattamente quelle tre cose.

Usa `tkinter`, che sta nella libreria standard: la regola del progetto --
nessuna dipendenza esterna -- vale anche per l'interfaccia, e PyInstaller la
impacchetta senza configurazioni particolari.

Il server continua a essere quello di sempre: la finestra lo avvia nello
stesso processo e ne legge lo stato, non ne duplica la logica.
"""

from __future__ import annotations

import json
import queue
import threading
import tkinter as tk
import urllib.error
import urllib.request
from pathlib import Path
from tkinter import messagebox, ttk
from typing import Any

from . import server as server_mod

OLLAMA_TAGS_URL = "http://localhost:11434/api/tags"
SENZA_MODELLO = "Solo regole (nessun modello)"
INTERVALLO_AGGIORNAMENTO_MS = 700


def modelli_ollama(timeout: float = 4.0) -> list[str]:
    """Modelli installati su Ollama, o lista vuota se non risponde.

    Non e' un errore che Ollama sia spento: il baker lavora comunque con le
    sole regole, e la finestra deve dirlo invece di bloccarsi.
    """
    try:
        with urllib.request.urlopen(OLLAMA_TAGS_URL, timeout=timeout) as risposta:
            dati = json.loads(risposta.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        return []
    nomi = [m.get("name", "") for m in dati.get("models", [])]
    return sorted(n for n in nomi if n)


class FinestraBaker:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.server: server_mod.BakeHttpServer | None = None
        self._thread_server: threading.Thread | None = None
        self._eventi: queue.Queue[tuple[str, Any]] = queue.Queue()

        root.title("Mindloop Baker")
        root.minsize(720, 520)

        self._costruisci_riquadro_avvio()
        self._costruisci_riquadro_connessione()
        self._costruisci_riquadro_lavori()

        self._carica_modelli()
        self.root.protocol("WM_DELETE_WINDOW", self._chiudi)
        self.root.after(INTERVALLO_AGGIORNAMENTO_MS, self._aggiorna)

    # ------------------------------------------------------------------ UI

    def _costruisci_riquadro_avvio(self) -> None:
        cornice = ttk.LabelFrame(self.root, text="Generazione", padding=12)
        cornice.pack(fill="x", padx=12, pady=(12, 6))

        ttk.Label(cornice, text="Modello:").grid(row=0, column=0, sticky="w")
        self.var_modello = tk.StringVar(value=SENZA_MODELLO)
        self.menu_modello = ttk.Combobox(
            cornice, textvariable=self.var_modello, state="readonly", width=34
        )
        self.menu_modello.grid(row=0, column=1, sticky="w", padx=(8, 8))

        ttk.Button(cornice, text="Aggiorna elenco", command=self._carica_modelli).grid(
            row=0, column=2, sticky="w"
        )

        self.etichetta_modelli = ttk.Label(cornice, text="", foreground="#555555")
        self.etichetta_modelli.grid(row=1, column=0, columnspan=3, sticky="w", pady=(6, 0))

        self.bottone_avvia = ttk.Button(cornice, text="Avvia il server", command=self._avvia)
        self.bottone_avvia.grid(row=2, column=0, sticky="w", pady=(12, 0))
        self.bottone_ferma = ttk.Button(
            cornice, text="Ferma", command=self._ferma, state="disabled"
        )
        self.bottone_ferma.grid(row=2, column=1, sticky="w", pady=(12, 0), padx=(8, 0))

    def _costruisci_riquadro_connessione(self) -> None:
        self.cornice_connessione = ttk.LabelFrame(
            self.root, text="Collegamento dal telefono", padding=12
        )
        self.cornice_connessione.pack(fill="x", padx=12, pady=6)

        self.etichetta_codice = ttk.Label(
            self.cornice_connessione, text="Server fermo", font=("TkDefaultFont", 12, "bold")
        )
        self.etichetta_codice.pack(anchor="w")

        self.etichetta_indirizzi = ttk.Label(self.cornice_connessione, text="", justify="left")
        self.etichetta_indirizzi.pack(anchor="w", pady=(4, 0))

    def _costruisci_riquadro_lavori(self) -> None:
        cornice = ttk.LabelFrame(self.root, text="Cotture", padding=12)
        cornice.pack(fill="both", expand=True, padx=12, pady=(6, 12))

        colonne = ("raccolta", "stato", "avanzamento")
        self.tabella = ttk.Treeview(cornice, columns=colonne, show="headings", height=8)
        for colonna, titolo, larghezza in (
            ("raccolta", "Raccolta", 240),
            ("stato", "Stato", 220),
            ("avanzamento", "Avanzamento", 120),
        ):
            self.tabella.heading(colonna, text=titolo)
            self.tabella.column(colonna, width=larghezza, anchor="w")
        self.tabella.pack(fill="both", expand=True)

        barra = ttk.Frame(cornice)
        barra.pack(fill="x", pady=(8, 0))
        self.bottone_invia = ttk.Button(
            barra, text="Invia al cellulare", command=self._invia, state="disabled"
        )
        self.bottone_invia.pack(side="left")
        self.bottone_cartella = ttk.Button(
            barra, text="Apri cartella del pacchetto", command=self._apri_cartella, state="disabled"
        )
        self.bottone_cartella.pack(side="left", padx=(8, 0))

        self.etichetta_aiuto = ttk.Label(barra, text="", foreground="#555555")
        self.etichetta_aiuto.pack(side="left", padx=(12, 0))

        self.tabella.bind("<<TreeviewSelect>>", lambda _evento: self._aggiorna_bottoni())

    # ------------------------------------------------------------- azioni

    def _carica_modelli(self) -> None:
        modelli = modelli_ollama()
        valori = [SENZA_MODELLO] + modelli
        self.menu_modello["values"] = valori
        if self.var_modello.get() not in valori:
            self.var_modello.set(SENZA_MODELLO)
        if modelli:
            quanti = (
                "1 modello disponibile" if len(modelli) == 1
                else f"{len(modelli)} modelli disponibili"
            )
            self.etichetta_modelli.config(
                text=f"{quanti} su Ollama. Un modello piu' capace da' distrattori "
                "migliori, ma la cottura dura di piu'."
            )
        else:
            self.etichetta_modelli.config(
                text="Ollama non risponde: si puo' generare lo stesso, con le sole regole."
            )

    def _avvia(self) -> None:
        if self.server is not None:
            return
        modello = self.var_modello.get()
        try:
            self.server = server_mod.create_server(
                llm_model=None if modello == SENZA_MODELLO else modello,
            )
        except OSError as errore:
            messagebox.showerror(
                "Avvio non riuscito",
                f"Impossibile aprire la porta {server_mod.DEFAULT_PORT}.\n\n{errore}\n\n"
                "Di solito significa che un altro Mindloop Baker e' gia' in esecuzione.",
            )
            self.server = None
            return

        self._thread_server = threading.Thread(
            target=self.server.serve_forever, name="baker-http", daemon=True
        )
        self._thread_server.start()

        servizio = self.server.service
        porta = self.server.server_address[1]
        indirizzi = server_mod.announce_addresses(
            self.server.server_address[0], porta, servizio.hostname
        )
        self.etichetta_codice.config(text=f"Codice di accoppiamento:  {servizio.pairing_code}")
        self.etichetta_indirizzi.config(
            text="Sul telefono: Genera quiz -> Cerca il PC.\n"
            "Se la ricerca non trova nulla, uno di questi indirizzi:\n"
            + "\n".join(f"    http://{a}:{porta}" for a in indirizzi)
        )
        self.menu_modello.config(state="disabled")
        self.bottone_avvia.config(state="disabled")
        self.bottone_ferma.config(state="normal")

    def _ferma(self) -> None:
        if self.server is None:
            return
        # shutdown() blocca finche' il loop non si ferma: fuori dal thread
        # della finestra, altrimenti l'interfaccia si congela.
        server, self.server = self.server, None
        threading.Thread(target=self._spegni, args=(server,), daemon=True).start()
        self.etichetta_codice.config(text="Server fermo")
        self.etichetta_indirizzi.config(text="")
        self.menu_modello.config(state="readonly")
        self.bottone_avvia.config(state="normal")
        self.bottone_ferma.config(state="disabled")

    @staticmethod
    def _spegni(server: server_mod.BakeHttpServer) -> None:
        server.shutdown()
        server.server_close()

    def _job_selezionato(self) -> server_mod.BakeJob | None:
        if self.server is None:
            return None
        selezione = self.tabella.selection()
        if not selezione:
            return None
        return self.server.service.get_job(selezione[0])

    def _invia(self) -> None:
        job = self._job_selezionato()
        if job is None or job.state != "done":
            return
        # Il telefono non puo' restare in ascolto: Android chiude i socket
        # delle app in background. Quindi non si "spinge" niente -- si tiene
        # il pacchetto pronto e lo ritira il telefono appena si ricollega.
        messagebox.showinfo(
            "Pronto per il telefono",
            f"Il quiz di «{job.raccolta}» resta disponibile su questo PC.\n\n"
            "Apri Mindloop sul telefono e vai sulla raccolta: se il collegamento "
            "e' attivo, il pacchetto viene ripreso da solo.\n\n"
            "Il PC deve restare acceso con il server avviato.",
        )

    def _apri_cartella(self) -> None:
        job = self._job_selezionato()
        if job is None:
            return
        cartella = Path(job.job_dir)
        if not cartella.is_dir():
            return
        try:
            import os

            os.startfile(str(cartella))  # type: ignore[attr-defined]
        except (AttributeError, OSError):
            messagebox.showinfo("Cartella del pacchetto", str(cartella))

    def _aggiorna_bottoni(self) -> None:
        job = self._job_selezionato()
        pronto = job is not None and job.state == "done"
        self.bottone_invia.config(state="normal" if pronto else "disabled")
        self.bottone_cartella.config(state="normal" if job is not None else "disabled")
        if job is None:
            self.etichetta_aiuto.config(text="")
        elif job.state == "done":
            self.etichetta_aiuto.config(text="Il telefono lo riprende quando si ricollega.")
        elif job.state == "error":
            self.etichetta_aiuto.config(text=job.error or "cottura fallita")
        else:
            self.etichetta_aiuto.config(text="")

    # ---------------------------------------------------------- ciclo vita

    def _aggiorna(self) -> None:
        if self.server is not None:
            self._ridisegna_lavori(self.server.service.list_jobs())
        self.root.after(INTERVALLO_AGGIORNAMENTO_MS, self._aggiorna)

    def _ridisegna_lavori(self, jobs: list[server_mod.BakeJob]) -> None:
        presenti = set(self.tabella.get_children())
        for job in jobs:
            stato = {
                "running": f"in corso - {job.message}",
                "done": "pronto",
                "error": f"errore: {job.error or 'sconosciuto'}",
            }.get(job.state, job.state)
            valori = (job.raccolta, stato, f"{int(job.progress * 100)}%")
            if job.job_id in presenti:
                self.tabella.item(job.job_id, values=valori)
            else:
                self.tabella.insert("", "end", iid=job.job_id, values=valori)
        self._aggiorna_bottoni()

    def _chiudi(self) -> None:
        if self.server is not None:
            self._ferma()
        self.root.destroy()


def avvia() -> int:
    root = tk.Tk()
    FinestraBaker(root)
    root.mainloop()
    return 0

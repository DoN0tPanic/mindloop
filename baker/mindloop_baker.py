"""Punto di ingresso dell'eseguibile per Windows.

Chi scarica l'exe non lo lancia da riga di comando: fa doppio clic. Quindi
senza argomenti si avvia il server, che e' l'unica cosa che serve per usare
il telefono; con argomenti si comporta come la CLI di sempre, cosi' l'exe
resta utile anche a chi vuole cuocere un mazzo a mano.
"""

from __future__ import annotations

import sys

from baker.cli import main


def _run() -> int:
    # Senza questo, l'output resta nel buffer finche' il programma non
    # termina: chi fa doppio clic vedrebbe una finestra vuota proprio mentre
    # aspetta di leggere il codice di accoppiamento, che e' l'unica cosa che
    # deve copiare sul telefono. Il server poi non termina mai da solo.
    for flusso in (sys.stdout, sys.stderr):
        try:
            flusso.reconfigure(line_buffering=True)
        except (AttributeError, ValueError):
            pass

    argv = sys.argv[1:]
    if not argv:
        # Doppio clic: si apre la finestra. Prima partiva il server nella sola
        # console, e per scegliere il modello o vedere l'avanzamento bisognava
        # conoscere le opzioni da riga di comando -- cosa che chi fa doppio
        # clic non ha nessun motivo di sapere.
        try:
            return main(["gui"])
        except Exception:  # noqa: BLE001 - senza grafica si ripiega sulla console
            print("Interfaccia grafica non disponibile: avvio il server in console.\n")
            try:
                return main(["serve"])
            finally:
                _pausa_se_console_effimera()
    return main(argv)


def _pausa_se_console_effimera() -> None:
    """Tiene aperta la finestra dopo un arresto o un errore.

    Serve solo quando la console e' stata aperta dal doppio clic: se l'utente
    lo ha lanciato da un terminale suo, aspettare un tasto sarebbe fastidioso.
    """
    if not sys.stdin or not sys.stdin.isatty():
        return
    try:
        input("\nPremi Invio per chiudere...")
    except (EOFError, KeyboardInterrupt):
        pass


if __name__ == "__main__":
    raise SystemExit(_run())

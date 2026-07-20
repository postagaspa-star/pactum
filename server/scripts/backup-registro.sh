#!/bin/sh
# Pactum — backup del registro (SQLite), da schedulare in DSM Task Scheduler.
#
# Il registro E' il prodotto: questo script ne fa una copia CONSISTENTE (via l'API
# di backup di SQLite, sicura anche mentre il postino gira) in una cartella host, e
# tiene le ultime 30 copie. Consigliato: una copia al giorno + una copia off-site
# (es. Hyper Backup verso un altro NAS/cloud) della cartella backup.
#
# Uso:  sh backup-registro.sh [CARTELLA_BACKUP]
#   CARTELLA_BACKUP default: la sottocartella server/backup montata nel container.
#   In DSM Task Scheduler: Attivita' pianificata -> Script utente ->
#     sh /volume1/docker/pactum/server/scripts/backup-registro.sh
set -e

CONTAINER="pactum"
# La cartella backup e' quella accanto allo script (server/backup), ricavata dalla
# posizione dello script: cosi' funziona a prescindere da dove hai messo Pactum.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKDIR="${1:-$SCRIPT_DIR/../backup}"
TIENI=30                                   # quante copie conservare
STAMP="$(date +%Y%m%d-%H%M)"

mkdir -p "$BACKDIR"

# Snapshot consistente scritto dentro il container su /backup (= $BACKDIR sull'host).
docker exec -e STAMP="$STAMP" "$CONTAINER" python -c "import sqlite3, os; s = sqlite3.connect('/data/pactum.db'); d = sqlite3.connect('/backup/pactum-' + os.environ['STAMP'] + '.db'); s.backup(d); d.close(); s.close()"

# Verifica che il file esista e non sia vuoto.
FILE="$BACKDIR/pactum-$STAMP.db"
if [ ! -s "$FILE" ]; then
  echo "ERRORE: backup non creato ($FILE)" >&2
  exit 1
fi

# Ruota: tieni solo le ultime $TIENI copie.
ls -1t "$BACKDIR"/pactum-*.db 2>/dev/null | tail -n +$((TIENI + 1)) | xargs -r rm -f

echo "backup registro ok: pactum-$STAMP.db ($(wc -c < "$FILE") byte)"

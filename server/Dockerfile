# Per il NAS Synology: montare un volume su /data (database) e uno su /apk (APK).
FROM python:3.14-slim

WORKDIR /srv/pactum

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copia il pacchetto: include app/versioni.json (metadati auto-update) servito da
# /api/versione, e i moduli. tzdata arriva da requirements.txt (fuso Europe/Rome).
COPY app ./app

# Le cartelle dei volumi devono esistere prima del mount: /data (db) e /apk (APK
# firmati). Un volume vergine al primo avvio parte cosi' senza errori.
RUN mkdir -p /data /apk

ENV PACTUM_DB=/data/pactum.db \
    PACTUM_APK_DIR=/apk

EXPOSE 8000

# Sonda di salute: il container e' "healthy" solo se /api/salute risponde. Usa
# python (gia' presente) invece di curl, che l'immagine slim non ha.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/salute', timeout=4).status == 200 else 1)"]

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]

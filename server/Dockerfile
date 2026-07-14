# Per il NAS Synology (tappa successiva): montare un volume su /data per il database.
FROM python:3.14-slim

WORKDIR /srv/pactum

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV PACTUM_DB=/data/pactum.db

EXPOSE 8000

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY server /app/server
RUN pip install --no-cache-dir /app/server
COPY docs/target-server-plan/models /app/docs/target-server-plan/models

RUN useradd --system --uid 10001 --no-create-home woona
USER 10001

CMD ["sh", "-c", "alembic -c /app/server/alembic.ini upgrade head && uvicorn server.app:app --host 0.0.0.0 --port 8080"]

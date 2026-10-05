FROM python:3.12-slim
WORKDIR /srv

# Install dependencies first so this layer is cached until pyproject.toml changes.
COPY pyproject.toml .
RUN mkdir app && touch app/__init__.py && pip install --no-cache-dir . && rm -rf app

COPY app ./app
# Versioned prompts (agent_system/vN.txt, baseline/vN.txt); app/prompts.py looks for them next to app/.
COPY prompts ./prompts
# The nutrition reference lives in the backend's resources (single source of truth). docker-compose.yml hands that
# folder in as the named build context "knowledge"; a plain `docker build` without it fails here on purpose.
COPY --from=knowledge nutrition_reference_v1.md /srv/knowledge/nutrition_reference_v1.md
ENV NUTRITION_REFERENCE_FILE=/srv/knowledge/nutrition_reference_v1.md
# Paused agent runs (LangGraph checkpoints). compose mounts a volume here so they survive a container restart.
RUN mkdir -p /srv/data
ENV AGENT_CHECKPOINT_DB=/srv/data/checkpoints.sqlite

EXPOSE 8000
# 0.0.0.0 is the container's own interface; compose publishes the port on 127.0.0.1 of the host only.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

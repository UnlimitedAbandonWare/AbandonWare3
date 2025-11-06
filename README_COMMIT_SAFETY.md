# Commit Safety Guide

This repository was sanitized to prevent accidental secret leaks.

## What changed
- Sanitized `src/main/resources/application.properties`: secrets moved to `${ENV_VAR}` placeholders (no hardcoded values).
- Removed `src/main/resources/keystore.p12` (sensitive binary).
- `src/main/resources/application-example.yml` and `.env.example` document the placeholders.
- Strengthened `.gitignore` to hard-block secret patterns.
- Added `src/main/resources/keystore.README.txt` placeholder.

## How to run locally
1) Export environment variables (examples):
   ```
   export OPENAI_API_KEY=... 
   export GROQ_API_KEY=...
   export GEMINI_API_KEY=...
   export TAVILY_API_KEY=...
   export PINECONE_API_KEY=...
   export NAVER_API_KEY=...
   ```
   Spring will resolve these via `${ENV_VAR}` references.

2) Provide keystore at runtime (if required):
   - Place `keystore.p12` under `src/main/resources/` (untracked), or
   - Point to an external path via env: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`.

## Safe push checklist
```
git init
git check-ignore -v src/main/resources/application.properties src/main/resources/keystore.p12
git add .
git status     # sensitive files must NOT show up
git commit -m "init (without secrets)"
```
Never use `git add -f` or CI options that bypass `.gitignore`.

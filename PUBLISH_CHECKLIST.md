# Public Release Checklist

This repository has been sanitized for public release. **Do not re-introduce real secrets.**

## Actions performed
- REMOVE main/resources/application-ultra.properties
- ADD main/resources/application-ultra.properties.example
- REMOVE main/resources/application.properties
- ADD main/resources/application.properties.example
- REMOVE main/resources/application.yml.bak
- REMOVE main/resources/application.yml.bak2
- REMOVE main/resources/application.yml.bak_before_dw_patch
- REMOVE main/resources/application.yml.bak_before_normalize
- REMOVE main/resources/application.yml.bak_before_split
- REMOVE main/resources/matrix_policy.yaml.bak
- REMOVE app/src/main/resources/application.yml.bak
- REMOVE app/src/main/resources/application.yml.bak2
- REMOVE app/src/main/resources/application.yml.bak_before_chatgpt_20251031_202243
- REMOVE app/src/main/resources/application.yml.bak_before_dw_patch
- REMOVE app/src/main/resources/application.yml.bak_before_split
- REMOVE analysis
- REMOVE build-logs
- REMOVE __reports__
- APPEND .gitignore
- ADD SECURITY.md
- ADD CONTRIBUTING.md

## Secret scan (after sanitization)
Detected patterns (post-sanitization) — these are expected to be non-secret assignments in code/tests:
- API_KEY_ASSIGN: 41
- PASSWORD_ASSIGN: 14
- LLM_API_KEY: 2
- KEYSTORE_PASSWORD: 1
- KEY_PASSWORD: 1

Paths with remaining **assignments** (review if necessary):
- README_COMMIT_SAFETY.md — 6 matches: API_KEY_ASSIGN:6
- main/java/com/example/lms/plugin/image/OpenAiImageService.java — 6 matches: API_KEY_ASSIGN:6
- main/resources/application.properties.example — 5 matches: PASSWORD_ASSIGN:3, KEYSTORE_PASSWORD:1, KEY_PASSWORD:1
- main/README.md — 3 matches: API_KEY_ASSIGN:2, LLM_API_KEY:1
- PATCH_NOTES_src_95.md — 2 matches: API_KEY_ASSIGN:2
- docs/PAST_TRAJECTORY_INDEX.md — 2 matches: API_KEY_ASSIGN:2
- main/java/com/example/lms/config/ModelConfig.java — 2 matches: API_KEY_ASSIGN:2
- main/java/com/example/lms/domain/Administrator.java — 2 matches: PASSWORD_ASSIGN:2
- main/java/com/example/lms/domain/Professor.java — 2 matches: PASSWORD_ASSIGN:2
- main/java/com/example/lms/domain/User.java — 2 matches: PASSWORD_ASSIGN:2

## Next steps (mandatory)
- Rotate any keys/passwords that were ever committed to this codebase.
- Set environment variables in your CI/CD or `.env` (do **not** commit `.env`).
- If this repo was previously public, rewrite history with `git filter-repo` or BFG to remove leaked secrets.

## How to run locally
Provide the required environment variables listed in `main/resources/*.example` files.

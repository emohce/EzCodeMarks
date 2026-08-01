# Knowledge Rules

Tool: codex

## Routing

- Error memories: `vibe/knowledge/error-memory/`
- Verified Computer Use routes: `vibe/knowledge/computer-use/`
- ADRs: `vibe/knowledge/adr/`
- Glossary/domain notes: `vibe/knowledge/glossary.md` or domain-specific files
- Active process docs: `vibe/specs/`
- Eval records: `vibe/evals/`

## Migration Notes

- Legacy material is preserved in `vibe/knowledge/legacy/`.
- No DB-specific workspace was created during migration.

## Write Policy

- Search existing knowledge before adding new records.
- Store only reusable, verified, safe knowledge.
- Mark evidence as code, test, DBTools, user-confirmed, official-doc, or inference.
- Link old and new docs when business behavior changes.

## Document Governance Map

- Knowledge index: [../knowledge/README.md](../knowledge/README.md)
- Specs index: [../specs/README.md](../specs/README.md)
- Legacy map: [../knowledge/legacy/README.md](../knowledge/legacy/README.md)
- DB workspace: not configured for this project
- Use `--all-markdown` only for deep historical document hygiene; default audit covers active AI rule surfaces.

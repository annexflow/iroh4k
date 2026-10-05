---
name: pr
description: Use when preparing a pull request title or description, or opening an authorized pull request, in the iroh4k repository.
---

# Preparing Pull Requests

## Describe the complete change

Resolve the intended base from the existing PR or repository branch context. Read the full branch diff and history, including fixup commits:

```bash
git diff <base>...HEAD
git log <base>..HEAD
```

Check working-tree status as well. Uncommitted changes are absent from this branch diff; make clear which changes the proposed PR will contain. Keep unrelated local work out of it.

## Title and body

Use the Conventional Commits format from [commit](../commit/SKILL.md) for the title. Describe the net result on the base branch, rather than the latest fixup or the sequence of implementation attempts. The format is a local convention; do not claim a semantic-title CI check or a particular merge strategy without evidence.

Keep the body short and useful to a reviewer who has not seen the conversation:

- State the concrete problem and resulting behavior. Add a before/after example when it clarifies the change.
- Identify affected public API, facades, platforms, and migration steps when relevant.
- Report checks actually run using [check](../check/SKILL.md), with failures or unavailable gates. Distinguish host Android tests from device verification.
- For AI-assisted work, include a brief attribution describing how AI was used. Include model identity only when known; do not guess an exact model or version.

Use an existing repository PR template if present. Otherwise one short summary paragraph and a validation list usually suffice. Re-read the final diff and update the title and body if scope changed.

## Opening the PR

A request for a title or body authorizes drafting that text. Open a PR when the task authorizes creation, and use the requested draft state or repository workflow. For multiline descriptions, prefer a structured tool argument or a temporary file passed to `gh pr create --body-file`; preserve literal text and newlines.

When working in the Codex desktop app, attach every created PR to the current chat with `attach_artifact` and its returned URL. Return a direct PR link and the material verification result.

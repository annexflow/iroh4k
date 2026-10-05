---
name: commit
description: Use when drafting a commit message or creating an authorized commit in the iroh4k repository.
---

# Authoring Commit Messages

Use Conventional Commits, matching the existing project history. Read the staged diff before describing what will be committed:

```bash
git diff --cached --stat
git diff --cached
```

When asked only for a message, draft it from the supplied or inspected change. Creating a commit requires a task that authorizes it; stage only the intended files and preserve unrelated work.

## Format

```text
<type>(<scope>): <subject>

<body>

<footer>
```

Only the header is required. Choose `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, or `revert` according to the actual change. `style` means formatting, not a behavior change. Use `docs` for guidance and skill text changes.

The optional scope names the affected domain or layer: `endpoint`, `connection`, `stream`, `watch`, `services`, `keys`, `addr`, `relay`, `ffi`, `jni`, `android`, or `build`. Omit it when the change spans areas and a scope adds little.

Write a short English subject in the imperative, with a lowercase first letter and no trailing period. For example, `fix(stream): release cancelled stream results` describes a caller-visible correction. Add a body when the reason or compatibility impact needs explanation; describe the final behavior instead of recounting fixups.

For a breaking public API change, put `!` after the type or scope and add a `BREAKING CHANGE:` footer explaining migration. Reference an issue with `Closes #123` only when the change actually resolves it.

## Release context

Commit types and PR titles describe intent. Releases are driven by `v*` tags and the version checks in [AGENTS.md](../../../AGENTS.md#releasing) and the [release workflow](../../../.github/workflows/release.yml). Do not infer automatic version bumps, changelog generation, or squash merging from the message format.

Use [check](../check/SKILL.md) before an authorized commit. Report actual verification results and any unavailable checks.

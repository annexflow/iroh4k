---
name: prose
description: Use when writing or substantially editing Markdown guides or public API documentation in the iroh4k repository.
---

# Writing Documentation

## Put guidance where callers find it

Use [README.md](../../../README.md) for installation, quickstart, platform requirements, and short usage guidance. Public API documentation belongs in Kotlin KDoc beside the API in `commonMain`, or `androidMain` for Android-only behavior. [STATUS.md](../../../STATUS.md) records demonstrated coverage and known limitations; update it only when evidence changes.

Use a focused file under `docs/` when a requested guide needs its own home, and link it from the relevant existing documentation. Keep design and implementation plans separate from consumer guides. Rust module headers explain wire layouts and internal ownership; the Rust crate is unpublished, so consumer documentation does not need crate-local symlinks or `include_str!` plumbing.

[AGENTS.md](../../../AGENTS.md) is for agent-only project rules. Link to the authoritative definition instead of copying a policy into several documents.

## Explain usage in order

Start with what the reader can do, then a minimal example, then lifecycle, failure, and platform details needed to use it correctly. Build from basic usage toward advanced options. When another document explains something fully, link to it and keep the local explanation brief.

Use plain English and describe current behavior. Explain implementation details when they affect a caller's decisions. Avoid change-history narration in API docs and speculative promises. Follow the surrounding Markdown wrapping and typography rather than imposing an ASCII-only format on existing documentation.

## Verify examples and claims

Prefer Kotlin examples for consumer guidance. Check signatures, extension-function imports, coroutine scope, and cleanup against current source; use the echo example as a reference for working endpoint and stream usage. KDoc links must resolve to visible Kotlin declarations; Markdown links must resolve from the file containing them.

Make resource ownership explicit: handle-owning types need `use` or `close`, and graceful `shutdown` is a separate operation. Watcher flows manage their own handles during collection. For Android mDNS, explain the app's `CHANGE_WIFI_MULTICAST_STATE` permission and hold `Iroh4kAndroid.multicastLock` before binding and throughout discovery.

Distinguish hermetic loopback examples from LAN discovery without internet. For a hermetic example, use the documented configuration from AGENTS.md. For an offline LAN example, retain the necessary LAN transports or mDNS, state its network prerequisites, and check that configured discovery and relay services match the claim. Explain when internet discovery or relays are required. For 0-RTT examples, keep early work idempotent and describe acceptance/rejection semantics.

Check upstream claims against the pinned dependency version and its corresponding source. Record test counts, supported platforms, or performance numbers only when verified and relevant; preserve limitations documented in STATUS.md. Use [check](../check/SKILL.md) to choose validation appropriate to the actual changes.

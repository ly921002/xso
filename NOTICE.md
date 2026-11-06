# NOTICE

This repository builds [XTLS/Xray-core](https://github.com/XTLS/Xray-core) and
distributes the resulting binaries.

## Upstream licence

Xray-core is licensed under the **Mozilla Public License 2.0 (MPL-2.0)**.
A copy of the licence text is included at [`LICENSE`](LICENSE).

The artifacts published on the Releases page are **derivative works** of Xray-core
(the core is statically linked into the shared library). Therefore:

- The MPL-2.0 obligations apply to those binaries.
- The exact upstream commit for every artifact is recorded in the `BUILDINFO`
  file attached to each release, and in `dist/*.meta` for local builds.
- Corresponding source is the XTLS/Xray-core repository at that commit, plus the
  glue in [`cshared/main.go`](cshared/main.go) in this repository.

## What this repository adds

Only the thin cgo entrypoint (`cshared/main.go`) and build tooling. No changes are
made to Xray-core itself; the entrypoint is copied into an unmodified upstream
checkout at build time.

## Trademarks

"Xray" and "Xray-core" are used descriptively to identify the upstream project.
This is an unofficial build and is not endorsed by the Xray project.

# Additional third-party notices

These files supplement `THIRD_PARTY_LICENSES.md` and the licenses already included under `deps/` and `src/android/app/src/main/cpp/`. Existing source copyright headers and authorship notices remain intact.

## Included material

- `katex/LICENSE`: KaTeX 0.16.21 MIT license, matching the bundled JavaScript version. `katex/mhchem-source.js` preserves the corresponding contribution's upstream copyright headers.
- `katex/fonts-upstream-LICENSE`: MIT notice from the KaTeX font upstream project. Its exact revision mapping to the shipped font files has not been independently established.
- `jetbrains-mono/OFL.txt`: JetBrains Mono 2.304 SIL Open Font License from the upstream tag.
- `rclone/COPYING`: rclone v1.75.0 MIT license. The wrapper's `go.mod` and `go.sum` identify module dependencies; this collection does not claim a complete notice inventory for every transitive module linked into the inherited `libgojni.so`.
- `tiktoken/LICENSE`: tiktoken 0.9.0 MIT reference notice. The inherited rank data and tokenizer port do not identify an exact upstream revision, so this is a reference notice rather than a claimed version match.
- `rootfs/musl/COPYRIGHT`, `rootfs/musl/getent.c`, `rootfs/apk-tools/LICENSE`, `rootfs/zlib/LICENSE`, `rootfs/openssl/LICENSE.txt`, `rootfs/pax-utils/COPYING`: upstream license/copyright files for the versions shown in the source index and installed package inventory.
- `rootfs/busybox/COPYING`: GPLv2 text, copied from the existing PRoot GPLv2 license. BusyBox and alpine-baselayout are declared GPL-2.0-only in the installed package inventory. Their package-specific notices and sources are referenced by the Alpine recipes.
- `rootfs/ca-certificates/MPL-2.0.txt`: Mozilla Public License 2.0 text. Alpine's recipe declares MPL-2.0 AND MIT; certificate bundle content and scripts retain their component-specific terms.
- `deps/talloc/COPYING.LESSER` and `deps/talloc/COPYING`: LGPLv3 and GPLv3 texts accompanying talloc's existing LGPL-3.0-or-later source headers.

## Root filesystem inventory

`rootfs-installed.txt` is the Alpine APK package database extracted from the bundled pristine minirootfs. It is package metadata, not an application database or user data. `rootfs-packages.json` and `.tsv` list installed package versions, license expressions, upstream URLs and Alpine recipe commits. Recipe snapshots are under `rootfs/aports/`.

`sources.json` records successful downloads, upstream locations and SHA-256 hashes. `unresolved-sources.json` records references not obtained or mappings not verified. This inventory documents what was actually collected; it is not an assertion that every dependency's licensing and corresponding-source requirements have been exhaustively verified. In particular, composite licenses, the inherited prebuilt native stack, font version mapping and all transitive Go modules require consulting the corresponding upstream sources and notices.

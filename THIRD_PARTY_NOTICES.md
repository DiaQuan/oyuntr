# Third-party components and notices

This file is an inventory for repository review. It does not replace the license texts of components fetched during a build.

| Component | Use in OyunTR | License/status to verify before publishing a distributable |
| --- | --- | --- |
| chiaki-ng, pinned at `43728af2e639fecb8a7af1cd68c29820a40de973` | PS4 Remote Play native protocol library, fetched by CMake | Upstream identifies AGPL-3.0; reviewed source headers include `LicenseRef-AGPL-3.0-only-OpenSSL`. Resolve exact terms, notices, corresponding-source duties, and Android-linked distribution implications. |
| Xiph Opus `v1.5.2` | PS4 audio decoding dependency, fetched by CMake | BSD-style license; preserve the pinned upstream `COPYING` notice. |
| Chatterbox Multilingual V3 | Optional self-hosted Turkish TTS model | MIT per the upstream model/repository; preserve notices. Model weights are not bundled. Voice recordings require speaker permission. |
| Google ML Kit Text Recognition / Translation | Android OCR and translation libraries | Review current Google SDK terms and dependency notices when producing a release. |

OyunTR has no project-level `LICENSE` file yet. Until the maintainers choose and add one, GitHub users should treat OyunTR-authored files as having no express open-source license. Do not describe this repository as open source or redistribute a combined app/native build as license-cleared. See [docs/licensing.md](docs/licensing.md) for the engineering checkpoint.

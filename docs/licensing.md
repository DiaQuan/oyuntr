# Third-party licensing plan

OyunTR is independently designed and written. Nino and CouchSubs are research references only and are not copied, forked, vendored, or linked.

chiaki-ng's project overview identifies it as AGPL-3.0, while source headers at the reviewed commit use `LicenseRef-AGPL-3.0-only-OpenSSL`. Before distributing a linked native build, resolve that exact license identifier and terms, retain notices, inventory transitive native dependencies, determine corresponding-source and network-use obligations, and decide how source access will be provided. The Android target may also bring separate decoder and crypto licensing considerations. This is still an engineering checkpoint, not a completed legal review.

This note records an engineering checkpoint, not legal advice or a determination that a particular product packaging is compliant. No chiaki-ng code or binary is included in this workspace.

The build recipe also fetches Xiph Opus `v1.5.2` for PS4 audio decoding. Its upstream `COPYING` file uses a BSD-style permissive license; preserve the exact upstream notice in any distributed build and include it in the third-party notices inventory. See the [pinned Opus license](https://github.com/xiph/opus/blob/v1.5.2/COPYING).

The optional dubbing service uses the external [Chatterbox Multilingual model](https://huggingface.co/ResembleAI/chatterbox). Its official repository/model page identifies the model as MIT licensed. Preserve the upstream copyright/license notices when redistributing. The project uses the multilingual V3 checkpoint for Turkish; model weights are fetched by the operator and are not included in this project ZIP. The repository also states that Chatterbox is retired research and is no longer maintained (as of August 2026), so deployment owners must manage version pinning, security review, and updates themselves. Voice references require the speaker's permission; model licensing does not grant rights to a person's voice.

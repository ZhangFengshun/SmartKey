# AOSP Pinyin decoder

The `include/` and `share/` directories and `assets/pinyin/dict_pinyin.dat` derive from Android's PinyinIME, licensed Apache-2.0. Original copyright headers and the NOTICE are retained.

Upstream: https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/

Pinned upstream revision: `49aebad1c1cfbbcaa9288ffed5161e79e57c3679` (includes the uint32 dictionary parsing fixes needed on 64-bit devices).

Retrieval: the original Google host could not be reached from the build machine, so the git objects were retrieved from https://github.com/oxangen/PinyinIME at `05cc8f8f2465553bc6d19b2ede24073d079eeed7`. That commit adds a README above the upstream revision. The original AOSP Java decoder service blob (`a4a3ac4fc4562d9d33631158b32026615ad026fc`) matches the official source browser. No Java front end or mirror-authored application code is included.

Dictionary SHA-256: `6bf0bbde4e3134cce38d08524a9f4dc1af40435243c8e36b38eb68d1e14462b2`.

The project adds its own JNI bridge and a public NDK logging compatibility header. Persistent user-dictionary learning is disabled; typed phrases are not saved. Sources are otherwise preserved. The source's dictionary-builder write path uses native sizes; this application uses only the included dictionary reader and does not build dictionaries on device.

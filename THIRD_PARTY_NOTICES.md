# Third-party notices

## Brave adblock-rust

Xuper integrates the Rust-based network and cosmetic filtering engine from Brave through an Android/JNI wrapper.

- Upstream engine: https://github.com/brave/adblock-rust
- Android wrapper: https://github.com/mlm-games/adblock-rs-kt-wrapper
- Wrapper Maven coordinate: `io.github.mlm-games:adblock:0.1.3`
- License: Mozilla Public License 2.0 (MPL-2.0)

The dependency is fetched from Maven Central during Gradle synchronization; its native library is not vendored into this source archive. Review the upstream repositories and license texts when redistributing builds.

## Filter lists

The app attempts to download the following third-party filter lists at runtime and stores a local cache:

- EasyList: https://easylist.to/easylist/easylist.txt
- EasyPrivacy: https://easylist.to/easylist/easyprivacy.txt
- AdGuard Annoyances: https://filters.adtidy.org/extension/chromium/filters/14.txt

These lists are maintained by their respective communities and can change independently of Xuper.

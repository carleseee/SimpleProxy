# Changelog

## 1.0.1

- Fixed Google Sign-In on Android TV devices by falling back to the classic Google Sign-In flow when Credential Manager is cancelled or unavailable.
- Improved the split tunneling dialog on Android TV: the window now expands to the full screen, giving the app list much more vertical space.
- Restored compatibility with Google Play Services builds that still provide the classic GoogleSignIn API.

## 1.0.0

- Initial public release.
- SOCKS5, SOCKS4/SOCKS4a, HTTP and HTTPS proxy support, including TCP and UDP.
- Zero-knowledge End-to-End Encrypted profile sync via Firebase and Google Drive.
- Split tunneling with All / User / System filters, system app badges, shared-UID grouping and Select all / Deselect all.
- "Include only selected" keeps all other apps, including OS background traffic, outside the VPN.
- Custom DNS with DoH / DNS-over-TCP fallback.
- Android TV support, Quick Settings tile, auto-start and auto-reconnect.
- In-app update checker with APK download to Downloads and installer integration.
- Available in Italian, English, French, German, Spanish, Russian, Chinese, Japanese and Polish.

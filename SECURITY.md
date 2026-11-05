# Security Policy

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
|---|---|
| 2.2 (latest) | ✅ |
| < 2.2 | ❌ |

## Reporting a vulnerability

**Please do not open a public issue.** Report privately through GitHub:

1. Go to the [Security tab](https://github.com/AlbatrossMicrosystems/lazarus-xposed/security).
2. Click **Report a vulnerability**.
3. Describe the problem, the affected version, your device and Android version, and the steps to
   reproduce.

You should get a reply within 7 days. Once a fix is released, the report is published as a
security advisory, crediting you unless you prefer otherwise.

## What counts as a vulnerability

Lazarus is anti-theft software, so these are in scope:

- Another app, or the `adb shell` user, controlling Lazarus without the signature permission
  (`io.github.albatrossmicrosystems.lazarus.permission.CONTROL`)
- Switching the phone off for real, or leaving fake power-off, without the owner's PIN, pattern
  or password (other than the hardware reset described below)
- Bypassing the PIN check for **Real power off** / **Restart**
- Lazarus leaking data or weakening Android's own security

## Known limitations (not vulnerabilities)

These are documented in the README and cannot be fixed in software:

- **Hardware reset:** holding power for about 6–10 s makes the phone's power chip force a real
  restart.
- **Unlocked bootloader:** someone with fastboot or recovery access can wipe or reflash the phone.
- **Settings are readable:** any app can read the `lazarus_cfg_*` values in `Settings.Global`.
  Only `system_server`, adb or root can write them.
- **Root:** anything running as root can control Lazarus.

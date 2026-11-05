# Lazarus

*by **Niik***

> **Play dead. Stay findable.**
>
> *Anti-theft for rooted Android: when a thief switches your phone off, it only looks off, and it keeps telling you where it is.*

When someone holds the power button, Lazarus only *pretends* to switch the phone off: the screen,
sound and vibration go dead, but Android keeps running, so location, mobile data and your tracking
app (e.g. [FMD](https://f-droid.org/packages/de.nulide.findmydevice/)) keep working. Holding power
again plays a **fake reboot**: a buzz, the real boot animation, then the lock screen. The phone
never actually restarts. That matters, because after a real restart Android keeps app storage
encrypted until the PIN is entered ("before first unlock"), and tracking apps cannot run in that
state.

Lazarus is an Xposed module that runs inside `system_server`.

## Features

| | |
|---|---|
| **Fake power-off** | Hold power (default 1.5 s). Keys, touch and every wake source (calls, notifications, charger, fingerprint, double-tap) are swallowed. Optional total-silence Do Not Disturb and no vibration. |
| **Fake reboot** | While fake-off, hold power (default 3 s): buzz → boot animation (~16 s) → lock screen. |
| **Owner exit** | While fake-off, hold Vol-Up + Vol-Down (default 3 s) to wake normally. |
| **Shutdown protection** | Power-off requests from menus or apps become fake power-off. Low-battery and overheating shutdowns are still allowed. |
| **Real power off for the owner** | App button or Quick Settings tile **"Real power off"**, protected by your **PIN/pattern/password**. Biometrics are not accepted, so nobody can force your finger onto the sensor. |
| **Dark wake** | Allow-listed apps (default: FMD) may use the display while fake-off, e.g. for FMD's remote camera, with the backlight forced off. |
| **Leak prevention** | Optionally disables Emergency SOS (5× power), the double-press camera, and the lock-screen / Quick Settings power menus. Original values are restored when you turn an option off, disable Lazarus, or uninstall the app. |

## Requirements

- Root with **Magisk** (Zygisk enabled)
- An Xposed framework: **[Vector](https://github.com/JingMatrix/Vector)** (LSPosed successor) or LSPosed
- Developed and tested on **Android 16** (crDroid). The hooked internals change between Android
  versions. Every hook fails safe to stock behaviour, and the app shows how many hooks loaded.

## Install

1. Install the APK.
2. In Vector/LSPosed, enable **Lazarus** with the scope **System Framework** (`android`), then **reboot**.
3. Open **Lazarus**. The status line should say **● Active (hooks 14/14)**.
4. *(Recommended)* Tap **Install boot-animation support (root)**. This installs a tiny Magisk module
   (`lazarus_sepolicy`, one SELinux rule: `allow system_server ctl_start_prop property_service set`)
   so the fake reboot can show the boot animation. Without it, the fake reboot stays dark for a few
   seconds and then lights up straight into the lock screen.
5. *(Optional)* Add the **Real power off** Quick Settings tile. The guide in the app has a one-tap
   button for this on Android 13+.
6. Install a tracker if you don't have one. [FMD](https://f-droid.org/packages/de.nulide.findmydevice/)
   works without Google and is allow-listed for dark wake by default.

On first launch the app opens **How to use**, a setup checklist that shows what is still missing:
module active, boot-animation support, screen lock, and tracking app.

## Tutorial

### Everyday use

| You do | What happens |
|---|---|
| Hold power ~1.5 s | **Fake power-off**: one buzz, then the phone looks dead. |
| While "off": press power, tap the screen, call the phone | Nothing. It stays dark and silent. |
| While "off": hold power ~3 s | **Fake restart**: buzz → boot animation → lock screen. |
| While "off": hold Vol-Up + Vol-Down ~3 s | **Owner exit**: the phone wakes up normally. |
| Power menu or an app tries to shut down | Becomes a fake power-off. |
| Lazarus → **Power off** / **Restart** (or the tile) | **Real** power off / restart, after your PIN. |

### Try it (2 minutes)

1. Hold power about 1.5 seconds. You feel one buzz and the screen goes black.
2. Press power a few times and tap the screen. Nothing should happen.
3. Hold **Vol-Up + Vol-Down** together for 3 seconds. The phone wakes up.
4. Hold power 1.5 s again, then hold power about **3 seconds**. The boot animation appears, so let go.
   After about 17 seconds the lock screen is back and the phone is in normal mode.
5. While the phone is "off", open your tracker's website (e.g. FMD Server) and locate it, ring it
   or take a photo.

### Settings

Every behaviour can be changed in the app: hold times, total silence, vibration, shutdown
protection, fake restart, owner exit, the dark-wake app list, and which system settings Lazarus may
change. The master switch turns Lazarus off completely and restores everything it changed.

## ⚠️ Read before use

- **You can no longer switch the phone off with the power button.** Use the in-app **Power off**
  (PIN-protected) or `adb shell settings put global lazarus_cmd shutdown`.
- **Hardware reset.** Every phone's power-management chip (PMIC) force-restarts the phone after the
  power key is held for a fixed time, often **6–10 s**, and power + volume-down may do so sooner.
  No software can block this. Keep the fake-reboot hold time **well below** your device's limit, so
  the boot animation appears and people let go first. After a real hardware restart, trackers only
  work again once the PIN is entered. Lazarus restores its Do Not Disturb change on the next boot.
- **Not theft-proof.** With an unlocked bootloader, someone who knows Android can still wipe or
  reflash the phone through fastboot or recovery. Lazarus defeats the common "hold power to turn it
  off" move.
- **Recovery** if something goes wrong:
  - In fake-off: hold Vol-Up + Vol-Down, or run `adb shell settings put global lazarus_cmd exit`.
  - Disable the module in Vector and reboot.
  - Boot into Android **safe mode** (hold Vol-Down during boot), which makes Magisk disable all modules.

## Control from a computer

```sh
adb shell settings put global lazarus_cmd enter      # fake power-off
adb shell settings put global lazarus_cmd exit       # back to normal
adb shell settings put global lazarus_cmd shutdown   # real power off
adb shell settings put global lazarus_cmd reboot     # real restart
adb shell settings get global lazarus_state          # 1 = fake-off
adb shell settings put global lazarus_cfg_<option> <value>   # see Keys.java for options
```

Logs go to the Vector/LSPosed module log, tag `Lazarus`.

## Security model

- The app controls the hook through broadcasts guarded by a **signature permission**
  (`io.github.albatrossmicrosystems.lazarus.permission.CONTROL`). Only an app signed with the Lazarus key, `adb shell`
  (via `settings`), or root can send commands.
- Configuration lives in `Settings.Global` (`lazarus_cfg_*`). Any app can read it; only
  `system_server`, adb or root can write it.
- Fake-off state lives in memory only, so every real boot starts in normal mode.

## Building

No Gradle needed. Requires the Android SDK (build-tools 36, platform 36) and JDK 17+.

```sh
bash build.sh        # -> lazarus-v<version>.apk
```

On first build, a release key is created in `~/.lazarus/` (outside the repo). **Back it up**:
updates must be signed with the same key. The `stubs/` directory holds compile-only stand-ins for
the Xposed API. They are not packaged.

## Contributing

Bug reports, ideas and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
Please report security problems privately, as described in [SECURITY.md](SECURITY.md).

## License

[GPL-3.0](LICENSE)

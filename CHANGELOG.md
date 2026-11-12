# Changelog

## 2.3
- Owner exit button combination is now selectable: hold Vol-Up + Vol-Down (default), hold Vol-Up,
  hold Vol-Down, hold Power + Vol-Up, or a secret Vol-Up / Vol-Down press sequence (3–8 presses).
- Power + Vol-Up cancels the fake reboot timer while it is held. An invalid secret sequence falls
  back to Vol-Up + Vol-Down, so the owner cannot be locked out.
- The in-app guide shows the configured owner exit gesture.

## 2.2 — first public release
- In-app "How to use" guide with a live setup checklist (opens on first launch), one-tap Quick
  Settings tile, clearer module description.

## 2.1
- In-app settings: master switch, hold times, silence/vibration, shutdown protection, fake reboot,
  owner exit, dark-wake app list, and each managed system setting can be toggled.
- Original values of changed system settings are recorded and restored when an option or Lazarus
  is turned off, or when the app is uninstalled.
- App ↔ hook control via a signature-protected broadcast; no ADB permission grants needed.
- Status line in the app (active / hook count / boot-animation support).
- One-tap root install of the boot-animation SELinux rule; dark fallback if it is missing.
- Fake reboot no longer lights the lock screen at full brightness without the animation.

## 2.0
- Renamed to Lazarus.

## 1.x (private)
- Fake power-off, dark wake for FMD camera, fake reboot with boot animation, PIN-protected real
  power off, hard-reset DND recovery, Android 16 power-key wake fix.

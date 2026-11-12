# Contributing to Lazarus

Thanks for helping. Lazarus hooks deep into `system_server`, so a small mistake can make a phone
unusable. Please read this page before opening an issue or pull request.

## Reporting bugs

Open a [bug report](https://github.com/AlbatrossMicrosystems/lazarus-xposed/issues/new/choose) and
include:

- Device model, Android version and ROM (e.g. *Redmi Note 9 Pro, Android 16, crDroid 12.x*)
- Root and framework versions (Magisk, Vector or LSPosed)
- Lazarus version and the status line from the app (e.g. **● Active (hooks 14/14)**)
- Steps to reproduce, what you expected, and what happened
- The module log: Vector/LSPosed → Logs, or `/data/adb/lspd/log/modules_*.log` (lines tagged `Lazarus`)

Fewer than all hooks loading is normal on Android versions other than 16. It is still useful to
report, together with the log, so support can be added.

**Security problems** (for example, a way for another app to control Lazarus or switch the phone
off) must **not** be reported in public issues. See [SECURITY.md](SECURITY.md).

## Suggesting features

Use the [feature request](https://github.com/AlbatrossMicrosystems/lazarus-xposed/issues/new/choose)
form. Explain the theft scenario or problem the feature solves.

## Pull requests

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Fork the repo and branch from `main`.
3. Keep each pull request to one change. Match the existing code style (plain Java 17, no
   external libraries, no Gradle).
4. Build and test on a real device (see below), and describe what you tested in the pull request.
5. Add a line to [CHANGELOG.md](CHANGELOG.md) under a new *Unreleased* heading.

### Automatic checks

Every pull request gets:

- **Build**: compiles the APK with `build.sh`, and checks that `magisk/` files use LF and that
  the version in `AndroidManifest.xml` matches `Keys.VERSION`.
- **CodeQL**: GitHub's security scan of the Java code and workflows.
- **CodeRabbit**: an AI review with comments on the diff. Its rules are in
  [`.coderabbit.yaml`](.coderabbit.yaml). Reply to a comment or mention `@coderabbitai` to discuss
  it. Its suggestions are advice; a maintainer decides what gets merged.

The build must pass before a pull request is merged.

### Rules for hook code

- **Fail safe.** Every hook must catch its own errors and fall back to stock Android behaviour.
  A failed hook must never crash `system_server` or block the power key.
- **Restore what you change.** Any system setting Lazarus changes must store its original value
  (`lazarus_orig_*`) and restore it when the option, Lazarus, or the app is turned off or removed.
- **Keep the security model.** App → hook commands go through the signature-protected broadcast.
  Do not add exported components or permissions that other apps could use.
- **Never block real emergencies.** Low-battery and overheating shutdowns must keep working.

## Building

No Gradle. You need the Android SDK (build-tools 36, platform 36) and JDK 17+.

```sh
bash build.sh        # -> lazarus-v<version>.apk
```

The first build creates your own signing key in `~/.lazarus/`. An APK signed with your key cannot
update the official release, so uninstall the official app before installing your build.

### Line endings

Files in `magisk/` (`module.prop`, `sepolicy.rule`, scripts) **must use LF line endings**. Magisk
fails on CR characters. `.gitattributes` enforces LF, and on Windows you should also run:

```sh
git config core.autocrlf false
```

## Testing on a device

1. Install your APK and enable Lazarus in Vector/LSPosed with scope **System Framework**, then reboot.
2. Check that the app shows all hooks loaded.
3. Run the [two-minute test](README.md#try-it-2-minutes): fake power-off, owner exit, fake reboot.
4. Exercise your change through ADB if possible:

   ```sh
   adb shell settings put global lazarus_cmd enter   # fake power-off
   adb shell settings put global lazarus_cmd exit    # back to normal
   adb shell settings get global lazarus_state       # 1 = fake-off
   ```

5. To test security, send commands as the `adb shell` user (uid 2000), **not** as root. Root
   bypasses the signature permission check.

Remember the hardware reset: holding power for about 6–10 s force-restarts most phones, whatever
Lazarus does. Have a way back in (see *Recovery* in the README) before testing.

## License

By contributing, you agree that your contributions are licensed under the
[GPL-3.0](LICENSE), like the rest of the project.

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Be respectful.

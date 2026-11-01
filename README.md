<p align="center">
  <img src="logo.png" alt="TerApp Logo" width="180" />
</p>

# TerApp 🦀

> An ultra-low memory, high-performance terminal emulator and POSIX Linux environment for Android, rewritten with a native **Rust** core.

[![CI](https://github.com/jeremy776/TerApp/actions/workflows/ci.yml/badge.svg)](https://github.com/jeremy776/TerApp/actions/workflows/ci.yml)
[![Release APK](https://github.com/jeremy776/TerApp/actions/workflows/release.yml/badge.svg)](https://github.com/jeremy776/TerApp/actions/workflows/release.yml)
[![Rust](https://img.shields.io/badge/Language-Rust_1.85+-orange.svg)](https://www.rust-lang.org/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.0+-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT%20or%20Apache--2.0-blue.svg)](LICENSE-MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%2010+%20%7C%20Linux%20%7C%20Windows-green.svg)]()

---

## 💡 Inspiration & Acknowledgements

**TerApp** is deeply inspired by [**Termux (`termux/termux-app`)**](https://github.com/termux/termux-app). 

Termux revolutionized running Linux software on mobile devices without requiring root access. However, as mobile workflows expand, traditional Android Java/Kotlin runtime (ART) overhead, object allocation pressure, and large package databases often result in noticeable memory consumption (often 80MB–200MB+ of RAM during active sessions).

**TerApp** takes the concepts pioneered by the Termux team and rebuilds them with a focus on native systems efficiency:
* **Ultra-Low Memory Footprint** (~9.38 KB active screen grid; sub-20 MB total app memory).
* **Native POSIX PTY Engine** (`/dev/ptmx` -> `/dev/pts/*`) with UTF-8 raw mode and dynamic `TIOCSWINSZ` window sizing.
* **Rust Native VT100 Engine** (`terapp-core` & `libterapp_android.so`) with Alternate Screen Buffer (`\e[?1049h`), 256-color & 24-bit TrueColor RGB.
* **Full-Screen Interactive App Support** (`vi`, `nano`, `top`, `htop`, `less`, `more`, interactive Python, and REPLs).
* **Dynamic Window Title & Bell** (OSC 0/2 title tracking and haptic vibration on bell `\x07`).
* **Fail-Safe Recovery Shell** for troubleshooting broken userland scripts or corrupted environment variables.
* **Built-in POSIX Userland** (BusyBox 1.36.1) without requiring root or external package downloads.
* **Lightweight Package Manager (`pkg`)** with self-healing userland, applet search, and system diagnostics (`pkg doctor`).
* **Extended 26-Key Toolbar & Long-Press Menu** (`ESC`, `TAB`, `CTRL`, `ALT`, `HOME`, `END`, `PGUP`, `PGDN`, `INS`, `DEL`, quotes, symbols, arrows).
* **Real-time Syntax Highlighting** for typed commands, flags, folders, executable `.sh` scripts, strings, and errors.
* **Momentum Gesture Scrolling** with thread-safe circular scrollback buffer and modern scrollbar indicator.
* **Split-Screen Dual Panes** for running two simultaneous terminal sessions.
* **8 Extended Color Themes** including Dracula, Nord, Monokai, Solarized Dark/Light, Gruvbox, One Dark, and Catppuccin.
* **Automated CI/CD Release Pipeline** on GitHub Actions for signed release APK generation and automated distribution.

---

## ⚡ Tech Stack & Architecture Comparison

| Layer | Traditional Termux | TerApp (Rust + Native Android) | Advantage |
| :--- | :--- | :--- | :--- |
| **Language** | Java & Kotlin | **Rust + Kotlin** | Zero-cost abstractions, minimal memory overhead. |
| **Terminal Core** | Java Terminal Emulator | **`terapp-core` (Zero-dep Rust)** | Raw byte stream parsing with 0 dynamic allocations during execution. |
| **PTY Subprocess** | Native C (`termux.c`) | **Native Rust (`terapp-pty`)** | Memory-safe POSIX pseudoterminal (`/dev/ptmx`) allocation & job control. |
| **Screen Buffer** | Java Objects & Text Layout | **Bit-packed `Cell` & Ring Buffer** | ~9.38 KB active screen grid; eliminates heap fragmentation. |
| **Alt Screen Buffer** | Supported | **Supported (`\e[?1049h`/`l`)** | Native isolation for full-screen apps (`vi`, `nano`, `top`). |
| **Scrollback** | Dynamic ArrayList / Deque | **Circular Fixed-Capacity Buffer** | Thread-safe, O(1) recycling of scrolled lines without re-allocations. |
| **Syntax Highlighting** | Monochrome / Bash Prompts | **Real-Time Client Tokenizer** | Instant color highlighting for folders, `.sh`, options, and errors. |
| **Userland** | GNU Coreutils via `apt` | **BusyBox 1.36.1 + `pkg` Helper** | Out-of-the-box POSIX tools (`ls`, `grep`, `awk`, `sed`, `tar`, `vi`, `curl`, `wget`) without root. |
| **Panes / Split View** | Third-party multiplexers (`tmux`) | **Native Dual-Pane Split Screen** | Built-in split terminal views with independent shell contexts. |
| **Fail-Safe Shell** | Supported | **Supported (`FailSafe Session`)** | Emergency clean rescue shell bypassing user dotfiles and broken PATH. |
| **Deployment** | Android Studio / Gradle | **Pure CLI (`gradlew`) + GitHub Actions CI/CD** | Automated signed APK building, SHA256 verification, and releases. |

---

## 🚀 Key Features

### 1. 🎨 Real-Time Syntax Highlighting (`TerminalColorizer.kt`)
* **Folders & Directories:** Sky Blue (`#42A5F5`) with bold styling.
* **Executable Scripts (`*.sh`, `./*`):** Emerald Green (`#2ECC71`) with underline.
* **Commands:** Vibrant Green (`#00E676`) for POSIX applets (`ls`, `cd`, `cat`, `grep`, `vi`, `sh`, `awk`, `pkg`, etc.).
* **Options & Flags:** Warm Orange (`#FFA726`) for `-la`, `-rf`, `--help`, `+x`.
* **Prompt Path & Symbol:** Vibrant Cyan (`#00E5FF`) for directory path and Amber Gold (`#FFD54F`) for `$` symbol.
* **Errors:** Coral Red (`#FF5252`) for command failures, `not found`, or `Permission denied`.
* **String Literals & Operators:** Pale Amber (`#FFF176`) for `"strings"` and Lavender (`#BA68C8`) for pipes `|`, `&&`, `;`.

### 2. 📜 Gesture Scrolling & Momentum Fling (`TerminalView.kt`)
* **Smooth Drag & Fling:** Single-finger swipe up/down navigates past terminal output with inertial physics using Android's `Scroller`.
* **Translucent Scrollbar:** Modern Cyan pill indicator appears dynamically on the right edge during scroll and hides at bottom.
* **Auto-Snap to Bottom:** Any keyboard keystroke, soft input, or ExtraKey immediately snaps back to the active command line.
* **Pinch-to-Zoom Isolation:** 2-finger zoom gestures smoothly scale terminal font without conflicting with 1-finger scrolling.

### 3. 📦 Package Manager & POSIX Userland (`BootstrapManager.kt` & `pkg`)
* Automatically extracts and symlinks **BusyBox 1.36.1** into `usr/bin`.
* Built-in `pkg` command line utility:
  ```bash
  pkg help              # Show package manager command menu
  pkg list              # List all installed POSIX applets in usr/bin
  pkg search <query>    # Search applets and tools matching pattern
  pkg install <applet>  # Symlink or register tool into usr/bin
  pkg doctor            # Run system environment & resource diagnosis
  pkg update            # Verify and heal userland symlinks
  ```
* Generates sample directories (`scripts/`) and executable scripts (`hello.sh`, `scripts/demo.sh`) on startup.
* Auto-heals dangling symlinks on app updates using physical library path fingerprinting (`.marker`).

### 4. 🪟 Native Split-Screen / Dual Pane Support
* Toggle between Single Pane and Split-Screen View from the overflow menu (`Toggle Split View`).
* Runs two independent terminal sessions with separate shell processes side-by-side.
* Tap either pane to seamlessly switch active input focus, IME keyboard, and ExtraKeys toolbar.

### 5. 🎨 8 Theme Presets (`ThemeManager.kt`)
* Switch instantly between 8 carefully tuned color palettes:
  1. **Dracula** (Default)
  2. **Catppuccin Mocha**
  3. **One Dark**
  4. **Nord**
  5. **Monokai**
  6. **Solarized Dark**
  7. **Solarized Light**
  8. **Gruvbox Dark**

### 6. 📋 System Clipboard Integration
* **Copy Screen:** Copies current terminal screen output to Android system clipboard with one tap from the action bar.
* **Paste:** Instantly injects clipboard contents directly into the active terminal shell.

### 7. 🔋 Background Session Management
* Runs an Android Foreground Service (`TerminalService`) with `WAKELOCK` to keep long-running scripts alive when switching apps.
* Multi-session tab bar supporting independent shell sessions simultaneously.
* Custom ExtraKeys bar (`ESC`, `TAB`, `CTRL`, `ALT`, `|`, `/`, `-`, `~`, arrow keys).

---

## 📊 Live Memory Benchmark

| Metric | Measurement |
| :--- | :--- |
| **Cell Struct Size** | `16 bytes` (Character + Bitflags + ANSI/RGB Color) |
| **Active Screen Memory (80x24)** | **~30.7 KB** |
| **Scrollback Memory (1,000 lines)** | **~1.28 MB** (pre-allocated ring buffer) |
| **Core Binary Footprint** | **~270 KB** (stripped, LTO enabled, `opt-level = "z"`) |
| **Release APK Size** | **~3.6 MB** (optimized with R8, ProGuard keep rules, and arm64-v8a native payload) |

---

## 📂 Project Structure

```text
TerApp/
├── .github/workflows/          # GitHub Actions CI/CD workflows
│   ├── ci.yml                  # PR and push CI build & test
│   └── release.yml             # Automated release APK pipeline & checksum publisher
├── Cargo.toml                  # Workspace root & release profile configuration
├── README.md                   # Project documentation & roadmap
├── crates/
│   ├── terapp-core/            # 100% dependency-free VT100 / ANSI terminal engine
│   │   ├── src/cell.rs         # Compact cell & color representations
│   │   ├── src/buffer.rs       # Circular ring buffer & screen grid
│   │   ├── src/parser.rs       # ANSI escape sequence state machine & UTF-8 decoder
│   │   └── src/lib.rs
│   ├── terapp-pty/             # POSIX pseudo-terminal (PTY) spawner
│   │   └── src/lib.rs          # Cross-platform PTY abstraction & Unix /dev/pts
│   ├── terapp-cli/             # Interactive desktop test harness (Windows/Linux/macOS)
│   │   └── src/main.rs         # Raw-mode terminal UI with live system commands
│   └── terapp-android/         # Android JNI shared library (libterapp.so)
│       └── src/lib.rs          # JNI bindings for Android Activity / SurfaceView
└── android/                    # Android Application (Kotlin + Material 3)
    ├── app/
    │   ├── build.gradle.kts    # App build configuration & signing setup
    │   ├── proguard-rules.pro  # R8 / ProGuard rules for JNI preservation
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── jniLibs/arm64-v8a/
    │       │   ├── libbusybox.so           # Prebuilt standalone BusyBox 1.36.1
    │       │   └── libterapp_android.so    # Compiled Rust VT100 engine
    │       └── java/com/terapp/
    │           ├── MainActivity.kt         # Multi-session tab UI, Split View & Actions
    │           ├── TerminalView.kt         # Custom Canvas renderer, touch gestures & scrolling
    │           ├── TerminalColorizer.kt    # Real-time syntax highlighter & token classifier
    │           ├── TerminalSession.kt      # Process spawner, sentinel protocol & scrollback buffer
    │           ├── BootstrapManager.kt     # BusyBox userland installer, symlink healer & pkg manager
    │           ├── ThemeManager.kt         # 8 color palette presets
    │           ├── TerAppBridge.kt         # JNI bridge to Rust native engine
    │           ├── TerminalService.kt      # Background foreground service & wakelock
    │           └── ExtraKeysView.kt        # Mobile touch toolbar (Esc, Tab, Ctrl, Alt, Arrows)
    └── gradle.properties
```

---

## 🛠️ Build & Installation Guide

You do **not** need Android Studio installed. Everything builds and installs directly from the command line.

### Prerequisites
* **JDK 17** (`JAVA_HOME`)
* **Android SDK** with Platform Tools (`adb`)
* **Rust 1.85+** (`cargo`)

### 1. Run Rust Workspace Tests
```bash
cargo test --workspace
```

### 2. Build Debug APK (CLI)
```bash
cd android
./gradlew assembleDebug
```
*(On Windows: `.\gradlew.bat assembleDebug`)*

### 3. Build Minified & Signed Release APK
```bash
cd android
./gradlew assembleRelease
```
The output APK is generated at:
`android/app/build/outputs/apk/release/app-release.apk`

### 4. Install Directly to Device via ADB
```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

### 5. Launch TerApp
```bash
adb shell am start -n com.terapp/.MainActivity
```

---

## 🚀 GitHub Actions CI/CD & Automated Releases

TerApp comes with automated CI/CD pipelines out of the box:

### 1. Automated PR & Push Verification (`.github/workflows/ci.yml`)
* Triggered on every commit and pull request to `main`.
* Tests all Rust crates (`terapp-core`, `terapp-pty`, `terapp-cli`, `terapp-android`).
* Assembles the Android application and publishes the Debug APK artifact for testing.

### 2. Automated Release Pipeline (`.github/workflows/release.yml`)
* **Trigger via Git Tag:**
  ```bash
  git tag v1.1.1
  git push origin v1.1.1
  ```
* **Trigger Manually:** Run the **Release APK** workflow in GitHub Actions with custom tag parameters.
* **Automatic Outputs:**
  * Runs Rust test suite to verify stability.
  * Compiles both `TerApp-vX.Y.Z-release.apk` (minified, ~3.6 MB) and `TerApp-vX.Y.Z-debug.apk`.
  * Generates `sha256sums.txt` checksum file for binary authenticity.
  * Creates an official GitHub Release with downloadable assets attached and automatic release notes.

---

## 🗺️ Development Roadmap

- [x] **Milestone 1: Core Terminal Engine & Parser (Rust)**
  - [x] Bit-packed terminal cell design (`Cell`)
  - [x] ANSI/VT100 CSI state machine (Cursor, SGR colors, line/screen erase)
  - [x] Circular ring buffer for zero-fragmentation scrollback
  - [x] Interactive PC test harness (`terapp-cli`)
- [x] **Milestone 2: Android Host & IME Keyboard Bridge**
  - [x] JNI shared library bridge (`libterapp_android.so`)
  - [x] Custom high-performance `TerminalView` on Android Canvas
  - [x] Full Virtual Keyboard (IME) input connection (`BaseInputConnection`)
  - [x] ExtraKeys bar (Ctrl, Alt, Esc, Tab, Directional Arrows)
  - [x] Pinch-to-zoom dynamic font resizing
- [x] **Milestone 3: Process Management & Background Services**
  - [x] Android POSIX shell process spawner with environment configuration (`HOME`, `PREFIX`, `PATH`)
  - [x] Zero-latency sentinel protocol (`[TERAPP_DONE:${PWD}]`) for directory tracking
  - [x] Android Foreground Service with WakeLock for uninterrupted background tasks
  - [x] Multi-session tab bar
- [x] **Milestone 4: POSIX Userland & BusyBox Integration**
  - [x] Packaged native `libbusybox.so` (100+ applets: `ls`, `grep`, `awk`, `sed`, `tar`, `vi`, `curl`, `wget`, etc.)
  - [x] Self-healing symlink engine in `usr/bin/` across APK updates
  - [x] Sample executable scripts (`hello.sh`, `scripts/demo.sh`)
- [x] **Milestone 5: Advanced Terminal UI & Scrolling**
  - [x] Real-time syntax highlighting for commands, options, folders, `.sh`, and errors (`TerminalColorizer.kt`)
  - [x] Gesture-based smooth scrolling with momentum fling (`Scroller`)
  - [x] Dynamic translucent scrollbar indicator
  - [x] Auto-scroll to active prompt on keyboard input
- [x] **Milestone 6: Package Manager & Ecosystem Expansion**
  - [x] Built-in `pkg` lightweight package manager integration with doctor, search, and update commands
  - [x] Extended 8 theme presets (Dracula, Nord, Monokai, Solarized Dark/Light, Gruvbox, One Dark, Catppuccin)
  - [x] Split-screen / dual terminal pane support with independent shells
  - [x] One-tap screen clipboard Copy & Paste integration
  - [x] GitHub Actions CI/CD workflows for automated tests and APK releases

- [x] **Milestone 7: Anti-Slop Modern UI/UX Redesign & Reflow Engine (v1.1.0)**
  - [x] Anti-slop modern soft dark design system (disciplined palette, comfortable touch targets, tactile elevation)
  - [x] Breathable terminal insets and soft rounded cursor styling
  - [x] Ergonomic ExtraKeys toolbar with tactile pill keys and modifier toggle indicators
  - [x] Refined multi-session tab navigation and native split pane divider handle
  - [x] Dynamic text wrapping and responsive terminal column calculation

- [x] **Milestone 8: Android Permission & SELinux Execution Fix (v1.1.1)**
  - [x] Set `targetSdkVersion = 28` matching Termux architecture to prevent Android 10+ SELinux W^X `Permission denied` on app data files
  - [x] Enabled `android:extractNativeLibs="true"` and legacy storage in `AndroidManifest.xml`
  - [x] Implemented resilient bootstrap engine with direct APK extraction fallback
  - [x] Standardized executable permissions (`0755`) for BusyBox, applets, and package manager (`pkg`)

---

## 🤝 Contributing

Contributions are very welcome! Whether you are interested in:
* Optimizing the VT100 parser in `terapp-core`
* Adding new syntax highlighting rules in `TerminalColorizer.kt`
* Packaging lightweight userland packages

Feel free to fork the repository, open an issue, or submit a Pull Request.

---

## 📜 License

This project is licensed under either of:
* Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE))
* MIT License ([LICENSE-MIT](LICENSE-MIT))

at your option.

Special thanks to the [Termux Project](https://github.com/termux) for inspiring open-source terminal computing on Android!

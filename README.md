# WebShell

Start a server on your Android phone, open a link in any browser, get a
working shell on the phone. No root needed.

## Install

Latest debug APK (built by GitHub Actions on every push to main):

https://github.com/rootforsree/WebShell/releases/download/latest/app-debug.apk

Open the link on the phone, tap the APK, allow "Install unknown apps"
for the browser when prompted.

## Use

1. Open WebShell, tap **Start server**.
2. Open one of the shown links in any browser on the same Wi-Fi, or
   over Tailscale (the Tailscale link works from anywhere your tailnet
   reaches).
3. You get a terminal in the browser. The token is part of the link
   and changes every time the server starts.

Keep the server alive in the background with **Allow background
running** (battery-optimization exemption, asked in context). **Allow
all files access** is optional: without it the shell still works, but
`/sdcard` needs a per-folder grant.

## How it works

- Ktor (CIO) serves a bundled xterm.js page (vendored in
  `app/src/main/assets/web/`, pinned xterm.js 5.3.0 + addon-fit
  0.10.0, no CDN) and a WebSocket endpoint at `/ws`.
- Each browser connection gets its own `/system/bin/sh`.
- The shell prefers a real PTY: the server opens `/dev/ptmx`,
  finds the new `/dev/pts` slave, and gives the shell that slave as
  stdin/stdout/stderr. Tab completion, Ctrl-C, and full-screen apps
  behave like a real terminal. (`android.system.Os` has no `openpty`,
  `fork`, or `ioctl`, so the PTY is built from `Os.open` on
  `/dev/ptmx` plus `ProcessBuilder` redirects instead.)
- When a PTY is unavailable the server falls back to pipe-based
  stdin/stdout/stderr streaming, with Ctrl-C translated into SIGINT.
- Auth is a random token generated on each start, required as
  `?token=` on the page and the WebSocket. No open unauthenticated
  shell.
- The shell runs unprivileged as the app user. It can run commands
  and touch shared storage, but nothing system-level.

## Build

Push to `main`. GitHub Actions builds the debug APK and publishes it
to the rolling `latest` release. `./gradlew check` (unit tests +
Android Lint) runs on every push and PR. Never run `./gradlew` on
the dev machine: local Gradle pops approval cards on the owner's
phone and the daemon cannot connect. CI only.

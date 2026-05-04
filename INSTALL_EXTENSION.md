# Install the CPH Target Runner browser extension

The Submit button in the CPH tool window uses the Codeforces problem currently open in your browser. The CPH Target Runner browser extension reports the active tab URL to the IDE over `127.0.0.1`, receives one pending source-code submit job from the IDE, and submits it to Codeforces with your existing browser login session.

On submit, the extension keeps the active problem tab intact, opens a new **Submit Code** tab for the same contest, fills the official form, and lets Codeforces handle practice/live/virtual routing.

## Chrome / Edge / Brave (any Chromium browser)

1. Open `chrome://extensions`.
2. Toggle **Developer mode** on (top-right).
3. Click **Load unpacked**.
4. Select `build/distributions/cph-target-runner-browser-1.0.0/` after running `./gradlew buildPlugin`, or select `browser-extension/cph-bridge/` while developing from this checkout.
5. Done. The extension now reports active CF tabs and waits for submit jobs from the IDE.

## Firefox

The current bridge implementation targets Chromium Manifest V3 APIs (`chrome.scripting` and service workers). Use Chrome, Edge, Brave, or another Chromium browser for one-click submit.

## Verify it's working

- Open any CF problem page, e.g. `https://codeforces.com/contest/4/problem/A`.
- Make sure you are logged in to Codeforces in that same browser.
- Hover over the 📤 Submit button in the CPH tool window — the tooltip should read `Submit current file → 4/A`.
- The extension popup only displays a minimal usage guide; live status (active tab / port) is shown in the IDE CPH tool window, not in the popup.

## Custom IDE port

If you changed the CPH listening port in `Settings / Tools / CPH 竞赛伴侣`, also tell the extension:

1. Open `chrome://extensions/?id=<your-extension-id>` → **Service worker** → **Inspect** → DevTools console.
2. Run `chrome.storage.local.set({ customPorts: [10044] })` (replace with your port).

The extension tries `10043, 10044, 10045, 10046, 10047, 1327` by default.

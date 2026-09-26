# The app, in the browser

The Android app for iPhone users: same screens, same behaviour, same Persian copy, same numbers —
every feature ships on both (see `AGENTS.md`). The browser cannot read the SMS inbox, so the
ledger takes a pasted bank message instead. How the port maps onto the Kotlin, and how to work on
it, is in `DEVELOPMENT.md` § The PWA.

`npm ci && npm run check` runs the TypeScript, the unit tests (the parser against the shared golden
SMS corpus, the ledger engine, reports, budgets, sync, backup) and builds the production shell.

`npx playwright install chromium && npm run test:browser` tests the built application in Chromium.
`PLAYWRIGHT_CHROMIUM_EXECUTABLE` optionally selects an existing local browser.

`npm run dev` serves it at http://localhost:5173 with the public price endpoints proxied;
`?demo=1` seeds the phone's demo household.

The build generates `dist/sw.js` from `sw-template.js` and the complete emitted asset list. The
content hash changes with the shell or worker, and installation finishes only after every bundle,
font, icon and document is cached. A new worker waits for existing tabs to close before
activation. Sync API responses are never cached. A tapped notification opens the tab it is about.

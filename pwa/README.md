# Browser companion

`npm ci && npm run check` runs the TypeScript, parser, IndexedDB and sync tests and builds the production shell.

`npx playwright install chromium && npm run test:browser` tests the built application in Chromium, including offline reload, draft persistence, duplicate-submit prevention, pagination, transfer totals and synchronization across tabs. `PLAYWRIGHT_CHROMIUM_EXECUTABLE` optionally selects an existing local browser. CI can use `npx playwright install --with-deps chromium`.

Local records, pending writes, sync cursors, drafts and token recovery state are separated by API origin, household, scope and member. Switching under «خانواده‌ها» preserves each previous session and its unsent changes. A new invitation creates a separate session. Version-1 storage migrates to its existing session before switching; rows from an unrecognized legacy scope remain quarantined under `legacy-unassigned:<scope>` rather than appearing in another family's ledger. They are retained for recovery and never uploaded through an unrelated identity.

Sync sends batches bounded by both count and encoded bytes, drains the server's `hasMore` pages and acknowledges only the exact revision sent. A local edit made during a request remains pending. Token rotation is enabled only after the server advertises `rotationClientSecret`; recovery state is persisted before rotating and cleared with the new credential in one transaction.

The build generates `dist/sw.js` from `sw-template.js` and the complete emitted asset list. The content hash changes with the shell or worker, and installation finishes only after every bundle, font, icon and document is cached. A new worker waits for existing tabs to close before activation, keeping each open tab on one complete build. Sync API responses are never cached.

The ledger renders 50 records per page using an IndexedDB date index. Monthly totals query only the nearby month range and exclude encrypted transfer flags and effective transfer categories. Editing and deletion controls are available for this member's manual entries. Manual amounts accept Latin, Persian and Arabic digits as whole Toman; pasted SMS keeps the exact original Rial amount until the amount field changes.

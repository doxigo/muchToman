# Plan 003: Keep transaction details off the lock screen

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: compare the "Current state" excerpts below
> against the live `app/src/main/java/com/doxigo/muchtoman/Notify.kt`. On a
> mismatch, treat it as a STOP condition. (The repo had uncommitted changes
> when this was planned; `Notify.kt` and `Filing.kt` were **not** among them.
> `Budget.kt` was — which is why this plan touches only `Notify.kt`.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `26270fd`, 2026-09-10

## Why this matters

This is a money app that already treats screen privacy as a feature (`FLAG_SECURE` under the app
lock, an opt-in app lock, truncated-never-rounded figures). But its notifications carry the
merchant, the amount, and the direction of every landed transaction — and no notification in
`Notify.kt` sets a visibility or a public version, so on a locked phone with default settings the
lock screen shows exactly what the app lock hides. Anyone who can see the phone can read her
spending. The fix is the platform's own mechanism: mark the notifications `VISIBILITY_PRIVATE`
and give each a redacted `publicVersion` for secure lock screens.

## Current state

All in `app/src/main/java/com/doxigo/muchtoman/Notify.kt` (527 lines). There is **no**
`setVisibility` and **no** `setPublicVersion` anywhere in the file (verify:
`grep -n "setVisibility\|setPublicVersion" app/src/main/java/com/doxigo/muchtoman/Notify.kt`
returns nothing).

- `notifyBudget(context, budget)` (~L255–281): builds and posts the budget note inline. Title/body
  come from `budgetAlertTitle(budget)`/`budgetAlertBody(budget)` (pure, live in `Budget.kt` — out
  of scope, do not touch). Builder chain (excerpt):

  ```kotlin
  val note = NotificationCompat.Builder(context, BUDGET_CHANNEL)
      .setSmallIcon(R.drawable.ic_toman)
      .setColor(0xFF0A423B.toInt())
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setContentIntent(openBudgets(context))
      .setTicker("$title. $body")
      .build()
  runCatching { NotificationManagerCompat.from(context).notify(budget.goal.id, BUDGET_NOTE_ID, note) }
  ```

- `landedNote(context, entry): Notification` (~L341–366, `private`): one note per landed
  transaction; title/body from `landedTitle(entry)`/`landedBody(entry)` (pure, in `Filing.kt` —
  they contain merchant + amount). Same builder shape on `FILING_CHANNEL`, plus
  `.setGroup(FILING_GROUP)`, `.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)`,
  content intent `if (entry.needsReview) openDeck(context) else openLedgerTab(context)`.
- `filingSummary(context, alert): Notification` (~L385–421, `private`): the group summary; words
  from `filingAlertTitle(alert)`/`filingAlertBody(alert)` (pure, `Filing.kt`), plus
  `.setGroupSummary(true)` and `.setNumber(alert.waiting)`.
- `notifyFiling(news)` (~L301) posts `landedNote` children then the summary.
- Doc conventions in this file: every builder line that isn't obvious carries a *why* comment;
  the file's words are Persian, casual, and pure functions feed every user-visible string (see the
  comment above `filingSummary`: "what a test asserts and what lands on her lock screen have to be
  the same strings"). `faNumber(value: Double): String` in `Format.kt` renders Persian digits.
- Existing Robolectric test exemplars: `app/src/test/java/com/doxigo/muchtoman/SyncRecoveryTest.kt`
  (header: `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [28])`). There is no
  `NotifyTest.kt` yet.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests (both flavours) | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest` | BUILD SUCCESSFUL |
| Lint | `./gradlew lint` | BUILD SUCCESSFUL |
| One test class | `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.NotifyTest"` | passes |

(JDK 17+ and the Android SDK are required — they are configured on this machine already.)

## Scope

**In scope**:
- `app/src/main/java/com/doxigo/muchtoman/Notify.kt`
- `app/src/test/java/com/doxigo/muchtoman/NotifyTest.kt` (create)

**Out of scope** (do NOT touch):
- `Budget.kt`, `Filing.kt` (they hold the detailed words; the redacted words live in `Notify.kt`).
  `Budget.kt` has uncommitted local changes — touching it risks a merge mess.
- `AndroidManifest.xml`, channel ids, note ids, grouping, `PendingIntent` request codes — the
  existing notification behaviour must not change for an unlocked phone.

## Git workflow

You are in a managed worktree; **do not commit or push** — the operator reviews and commits.

## Steps

### Step 1: Add the redacted words as pure functions in `Notify.kt`

Add near the top of `Notify.kt` (after the constants), following the file's comment style — words
first, and say why they are vague on purpose:

```kotlin
/**
 * الفظ روی قفل‌صفحه — the public face of every note. A locked screen names the kind of news and
 * nothing else: no merchant, no figure, no category. The full words wait behind the lock, which is
 * where FLAG_SECURE already keeps the rest of her money.
 */
internal fun budgetPublicTitle(): String = "خبری از بودجه"

internal fun landedPublicTitle(): String = "تراکنش تازه"

internal fun filingPublicTitle(count: Int): String =
    if (count > 1) "${faNumber(count.toDouble())} تراکنش تازه" else landedPublicTitle()

internal fun publicBody(): String = "جزئیات توی برنامه"
```

(If review prefers different Persian phrasing, only these four functions change — that is the
point of making them pure and named.)

**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 2: Build a redacted public version helper and wire all three notes

Add one private helper that builds the public version — same channel, icon, colour, category and
content intent as its private counterpart, but redacted words, no BigText, no ticker, no number:

```kotlin
private fun publicVersion(
    context: Context,
    channel: String,
    category: String,
    title: String,
    intent: PendingIntent,
): Notification = NotificationCompat.Builder(context, channel)
    .setSmallIcon(R.drawable.ic_toman)
    .setColor(0xFF0A423B.toInt())
    .setContentTitle(title)
    .setContentText(publicBody())
    .setCategory(category)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setAutoCancel(true)
    .setContentIntent(intent)
    .build()
```

Then, in each of the three builders, add two lines before `.build()`:

- `notifyBudget` builder: `.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` and
  `.setPublicVersion(publicVersion(context, BUDGET_CHANNEL, NotificationCompat.CATEGORY_REMINDER, budgetPublicTitle(), openBudgets(context)))`.
- `landedNote`: visibility private + `.setPublicVersion(publicVersion(context, FILING_CHANNEL, NotificationCompat.CATEGORY_STATUS, landedPublicTitle(), if (entry.needsReview) openDeck(context) else openLedgerTab(context)))`.
- `filingSummary`: visibility private + `.setPublicVersion(publicVersion(context, FILING_CHANNEL, NotificationCompat.CATEGORY_STATUS, filingPublicTitle(alert.fresh + alert.filed), if (alert.waiting > 0) openDeck(context) else openLedgerTab(context)))`.
  Note: the group summary's public version deliberately does **not** call `.setGroup(...)`;
  a public version is rendered standalone by the lock screen.

Add a short *why* comment at the first wiring site (the platform shows `publicVersion` on a
secure lock screen; `VISIBILITY_PRIVATE` is what asks it to).

To make the two private builders testable, change `private fun landedNote` and
`private fun filingSummary` to `internal fun`, and extract the budget note construction from
`notifyBudget` into `internal fun budgetNote(context: Context, budget: BudgetProgress): Notification`
(the posting wrapper `notifyBudget` keeps its exact behaviour: `canNotify` check, `ensureChannel`,
`runCatching { …notify(…) }`). Do not change any posted id, tag, or channel.

**Verify**: `./gradlew compileFullDebugKotlin` → BUILD SUCCESSFUL.

### Step 3: Tests

Create `app/src/test/java/com/doxigo/muchtoman/NotifyTest.kt`, Robolectric, modelled on
`SyncRecoveryTest.kt`'s header:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotifyTest {
```

Build real inputs the cheap way: construct a `BudgetProgress` / `LedgerEntry` / `FilingAlert` the
same way `BudgetTest.kt` / `FilingTest.kt` construct theirs (open those files and copy a minimal
fixture — do not invent new factory helpers in prod code). Assert for each of the three notes:

1. `note.visibility == NotificationCompat.VISIBILITY_PRIVATE`.
2. `note.publicVersion != null`.
3. The public version's `EXTRA_TITLE`/`EXTRA_TEXT` (via `note.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE)`)
   equal the pure functions' output, and — the actual point — contain **neither** the merchant
   string nor any digit of the amount used in the fixture (assert `!contains(merchant)` and
   `!contains(faNumber(amount))`-style checks against the fixture's values).
4. The full (non-public) note still carries the detailed title from `landedTitle(entry)` — the
   unlocked experience is unchanged.
5. `filingPublicTitle(3)` renders Persian digits (`۳ تراکنش تازه`) and `filingPublicTitle(1) == landedPublicTitle()`.

**Verify**: `./gradlew testFullDebugUnitTest --tests "com.doxigo.muchtoman.NotifyTest"` → passes.

### Step 4: Full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → BUILD SUCCESSFUL, no
new lint warnings about notifications.

## Test plan

Covered in Step 3: five assertions across the three notification builders in a new `NotifyTest.kt`
(pattern: `SyncRecoveryTest.kt` for Robolectric setup; `FilingTest.kt`/`BudgetTest.kt` for
fixtures). The pure-words functions get direct string tests.

## Done criteria

- [ ] `grep -c "setPublicVersion" app/src/main/java/com/doxigo/muchtoman/Notify.kt` → 3
- [ ] `grep -c "VISIBILITY_PRIVATE" app/src/main/java/com/doxigo/muchtoman/Notify.kt` → ≥3
- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` exits 0; `NotifyTest` exists and passes
- [ ] No files outside the two in-scope files are modified (`git status --short`)
- [ ] `plans/README.md` status row updated (unless a reviewer maintains the index)

## STOP conditions

Stop and report back (do not improvise) if:

- `Notify.kt` already sets a visibility or public version somewhere (drift).
- Constructing a `BudgetProgress`/`LedgerEntry`/`FilingAlert` fixture requires touching
  production code (report which type and why).
- Robolectric cannot build the notification (e.g. `publicVersion` null on sdk 28 for a reason you
  can't fix by config) — report rather than dropping assertions.
- Lint flags `MissingPermission` or similar on the new helper in a way that demands manifest
  changes.

## Maintenance notes

- Any future notification (a new channel) must follow the same rule: private visibility + a
  redacted public version. The `publicVersion(…)` helper is the one place to reach for.
- Reviewer: check the three public titles read naturally in Persian and match the app's casual
  voice («the words are the interface»); phrasing changes belong in the four pure functions only.
- Deliberately deferred: a user-facing setting to show full details on the lock screen (the
  platform's own per-channel "sensitive content" setting already provides an override).
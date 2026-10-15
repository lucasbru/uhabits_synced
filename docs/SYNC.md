# Loop Sync: design

This fork adds optional, account-based sync to Loop Habit Tracker. The app keeps working fully offline. Every
change is written to the local SQLite database first, and a background job reconciles it with the server whenever
a network connection is available. The server lives in a separate repository,
[`lucasbru/uhabits_backend`](https://github.com/lucasbru/uhabits_backend).

## 1. Goals

* **Offline first.** No UI action waits for the network.
* **Incremental.** A sync round transfers only the records that changed since the last round, in both directions.
* **Linear order.** The server gives every accepted change a strictly increasing per-account sequence number.
  Clients read this change feed by cursor, so they see changes in one order and can resume page by page.
* **Deterministic conflict resolution.** All devices converge to the same state, whatever order they sync in.
* **FOSS-friendly.** The app ships no proprietary SDK, so F-Droid builds keep working. Login runs in a browser tab
  against our own server.
* Not in v1: real-time push (sync runs periodically and after changes), end-to-end encryption (§8), and sharing
  habits between accounts.

## 2. Pattern: field-level LWW map plus a server sequence log

This is the scheme behind CloudKit change tokens and WatermelonDB- or Linear-style sync engines. It has two parts.

1. **A state-based CRDT (last-writer-wins map).** A record is a map of fields. Each field carries a *hybrid
   logical clock* (HLC) and the id of the node (app install) that wrote it. To merge two versions of a record,
   take the value with the larger `(clock, node)` pair for each field. This merge is commutative, associative and
   idempotent, so replays and duplicates do no harm and every replica converges.
2. **A server change log.** When a record's merged state changes, the server stamps it with
   `seq = ++account.seq`. A pull asks for *records with `seq > cursor`, ordered by `seq`, at most N*. The client
   stores the last `seq` it applied as its cursor.

Because resolution is per field, renaming a habit on your phone and archiving it on your tablet both survive.

### Hybrid logical clocks

`clock = max(previous + 1, unix_millis * 1000)`: wall-clock time in microsecond units, with the counter folded
into the low digits. The values stay below 2^53, so they are safe as JSON numbers in any language. After every
pull the client moves its clock to at least the largest clock it has seen. That way a later local edit always
beats anything the device has already observed, even if its wall clock is behind. The server rejects clocks more
than one hour in the future (`400 clock_skew`), so a device with a wildly wrong clock cannot pin a field forever.

## 3. Data model

| type    | key                         | fields |
|---------|-----------------------------|--------|
| `habit` | habit `uuid`                | `name, description, question, freq_num, freq_den, color, position, reminder_hour, reminder_min, reminder_days, archived, type, target_value, target_type, unit, deleted` |
| `entry` | `<habit uuid>:<timestamp>`  | `value, notes` |

* Field names equal the SQLite column names. Local integer ids are never sent, because they differ between
  devices.
* Deleting a habit sets `deleted = 1`, which works as a tombstone. Clients drop entries of deleted habits, both
  when receiving them and when pushing.
* Loop never deletes a single entry ("unchecking" writes a value), so entries need no tombstone.
* `position` is resolved per habit like any other field. If two devices reorder at the same time, Loop's existing
  `rebuildOrder()` repairs the result into a valid order, which may not be the one either device chose.
  `findAll()` breaks ties by uuid, so the repair is the same on every device. Fractional indexing would be a
  later improvement.

### Local change tracking (`core/sync/SyncSchema.kt`)

Change tracking uses its own tables and **SQLite triggers**. It is created at startup by `SyncSchema.ensure()`
rather than by a numbered migration. That keeps `DATABASE_VERSION` at 25, so backups made with this fork still
open in upstream Loop.

```
SyncState(id=1, node, hlc, applying, server_cursor, schema_version)
SyncClock(rtype, rkey, field, hlc, node)   -- clock of every field's current local value
SyncDirty(rtype, rkey, hlc)                -- records with unpushed changes
SyncOrphan(rkey, habit, value…, notes…)    -- entries received before their habit
```

On `INSERT`, `UPDATE` and `DELETE` of `Habits`, and on `INSERT` and `UPDATE` of `Repetitions`, the triggers do
three things:

1. advance the HLC in `SyncState`;
2. stamp a `SyncClock` row for each column whose value actually changed (`OLD.x IS NOT NEW.x`);
3. mark the record in `SyncDirty`.

Because they are triggers, nothing can bypass them: not raw SQL such as reordering, not imports, not future code.
They skip their work while `applying = 1`, so applying remote changes does not echo them back.
`SQLiteEntryList.add` now does *insert-or-ignore, then update* instead of *delete, then insert*. Without that
change, every toggle would also restamp the notes field.

The triggers always run, including for users who never sign in. The cost is a few small inserts per edit. This is
what lets offline edits made before signing in still win over older data in the account.

## 4. Protocol

One endpoint does push and pull in a single round trip.

```
POST /v1/sync
Authorization: Bearer <access token>
{
  "cursor": 1200,            // last seq this device applied (0 = never)
  "node": "a1b2c3d4e5f60718",
  "limit": 1000,             // max records to return
  "changes": [               // dirty local records with all their fields, max 400 per request
    { "type": "habit", "key": "5f0e…",
      "fields": { "name":     {"v": "Run", "c": 1760430000000123, "n": "a1b2c3d4e5f60718"},
                  "archived": {"v": 1,     "c": 1760430000000124, "n": "a1b2c3d4e5f60718"}, … } },
    { "type": "entry", "key": "5f0e…:1760313600000",
      "fields": { "value": {"v": 2, "c": 1760430000000125, "n": "a1b2c3d4e5f60718"},
                  "notes": {"v": "",  "c": 1760430000000125, "n": "a1b2c3d4e5f60718"} } }
  ]
}

200 OK
{ "changes": [ {"type", "key", "seq", "fields": {…all fields…}} ], "cursor": 1288, "hasMore": false, "serverHlc": … }
```

**Server, atomically per account:**

1. Merge each pushed record into the stored one, field by field. If anything changed, write it with
   `seq = ++account.seq`.
2. Return up to `limit` records with `seq > cursor`, ordered by `seq`. Records that this push produced are left
   out when the client already knew everything else about them (their previous `seq` was at or below `cursor`):
   they are pure echoes. The returned `cursor` still moves past them.

If the cursor is ahead of the account (the account was deleted and recreated), the server starts again from 0.

**Client sync round** (`SyncEngine.sync()` in uhabits-core):

```
repeat:
  batch = up to 400 SyncDirty records, read with their current values and clocks
  resp  = POST /v1/sync(cursor, batch)
  in one DB transaction, with applying = 1:
     habits:  per field, take the remote value if its (c, n) is larger than the local clock
              deleted = 1 → delete the habit and its entries locally
     entries: same, or park in SyncOrphan if the habit is not known yet
     retry orphans whose habit has arrived
     clear the SyncDirty rows whose hlc did not change while the request was in flight
     cursor = resp.cursor; hlc = max(hlc, largest clock seen)
until the batch was not full and !hasMore
```

Remote changes are written to the tables directly. Afterwards `SQLiteHabitList.refreshFromDatabase()` updates the
in-memory `Habit` objects in place, so open screens keep valid references. Changed habits reload their entries and
recompute. Finally `SyncChangesAppliedCommand` goes to the `CommandRunner` listeners, so the habit list, widgets
and reminders refresh just as they do after a local edit.

**When it runs** (Android `SyncManager`, using WorkManager with a network constraint and exponential backoff):

* 5 s after the last local command;
* when the main screen resumes;
* every 6 hours;
* on "Sync now".

**Signing in on a device that already has data.** `LocalSyncStore.prepareForSignIn()` marks every record dirty
and resets the cursor to 0. Fields that were never stamped get clock `0`. The result is a merge where data already
in the account wins on conflicts, while habits and check-ins that exist only on this device are uploaded. Fields
edited after this version was installed keep their real clocks and therefore win if they are newer. The node id is
regenerated at sign-in, in case the database was restored from another device's backup.

**Errors.** `401` → sign out locally, keep the data, and ask the user to sign in again. `clock_skew` → tell the
user to fix the device clock. Network and `5xx` errors → retry with backoff.

## 5. Authentication

There are no passwords. The server runs every login flow itself; the app only opens a browser tab (Custom Tabs,
from the FOSS `androidx.browser` library) and receives the result through the deep link
`org.isoron.uhabits.sync://auth`. This keeps Google Play Services and other proprietary SDKs out of the app.
The scheme is `${applicationId}.sync`, so the debug build (application ID `org.isoron.uhabits.sync`, installable next
to the Play Store app) uses `org.isoron.uhabits.sync.sync://auth`; the server's `APP_REDIRECT_URIS` must list both.

```
app                                    server                                    provider
 |-- tab: /v1/auth/oauth/google/start?redirect_uri&code_challenge=S256(v) -->|
 |                                       |-- 302 to provider, encrypted state ------>|
 |                                       |<-- /v1/auth/oauth/google/callback?code&state
 |                                       |    exchange code (server PKCE + secret), read sub/email
 |<-- 302 org.isoron.uhabits.sync://auth?code=<one-time, 5 min, bound to the challenge>
 |-- POST /v1/auth/token {code, codeVerifier: v} -->|
 |<-- {accessToken (JWT, 1 h), refreshToken (opaque, 180 d, rotating), account} ----|
```

* **PKCE between app and server** (RFC 7636/8252). Another app that intercepts the custom-scheme redirect cannot
  redeem the code without the verifier.
* **Email.** `POST /v1/auth/email/start {email, redirectUri, codeChallenge}` sends an email with a sign-in link
  *and* a 6-digit code. Tapping the link on the phone shows a page whose button opens the app through the same
  deep link. The button is there because mail scanners open links and would otherwise use up the token. If the
  email was opened on another device, the user types the code into the app instead
  (`POST /v1/auth/email/verify`). Codes expire after 15 minutes and allow 5 attempts. Sending is rate-limited per
  address and per IP.
* **Providers in v1:** Google, GitHub and Microsoft (personal and work accounts), plus email. Each one is enabled
  by configuring it on the server; the app only shows the buttons the server lists in `GET /v1/auth/providers`.
  Apple is not included: it only matters for an iOS app and needs a paid developer account.
* **Account linking:** an identity is `(provider, subject)`. A new identity joins an existing account only through
  an email address that both sides have *verified*: Google `email_verified`, GitHub's verified primary email, or
  our own email flow. Microsoft does not guarantee verified emails, so it never links automatically.
* **Tokens:** the refresh token is stored on the server only as a SHA-256 hash and rotates on every use. Reusing an
  old one more than two minutes later revokes the session. The two-minute grace period covers a retry after a lost
  response.
* **Account deletion:** `DELETE /v1/account` removes all records, identities and sessions. Google Play requires
  this.

In the app, the account screen is *Settings → Cloud sync*. The server URL is fixed at build time: the build
property `loopSyncServerUrl`, defaulting to the deployed Cloud Run service. Self-hosters build with their own URL.

## 6. Backend

See the backend README for the API, configuration and deployment. In short:

* Kotlin + Ktor 3, packaged as a container;
* storage behind a `Store` interface: `FirestoreStore` (Google Cloud), `SqlStore` (PostgreSQL or SQLite, for
  self-hosting) and `MemoryStore` (tests);
* recommended hosting: **Cloud Run + Firestore**, which costs nothing while idle and stays within the free tiers
  for personal use.

## 7. Limits

* 400 pushed records per request from the app (the server accepts 500); up to 1,000 returned per request.
* A fixed set of fields per type; strings ≤ 10,000 characters; values are JSON scalars.
* 200,000 records per account, roughly 50 habits × 10 years of daily entries.

## 8. Future work

* **End-to-end encryption:** the server only compares clocks, so field *values* could be encrypted on the device
  with a key derived from a passphrase, without changing the protocol.
* Fractional indexing for `position`.
* Push notifications (UnifiedPush, or FCM in Play builds) to sync within seconds instead of minutes.
* Server-side cleanup of entries that belong to deleted habits.

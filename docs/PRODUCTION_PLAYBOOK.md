# Production Playbook: Campus Marketplace

This playbook is for Claude Code. It turns the current app into something that looks and behaves
like a product built by an experienced team. Work through it **one phase at a time**:

1. Read the phase in full, then read the files it lists.
2. Do the phase as a series of small, tested commits.
3. Run the phase's **Verify** checklist.
4. Tick the boxes in [Progress](#progress), commit, push, and **stop**. Report to the user: what
   changed, what you verified, and what's left. Wait for "go" before you deploy or start the next
   phase.

`CLAUDE.md` has the project rules (secrets, schema, money, patterns). They apply throughout.

Deadline: **showcase on 12 Oct 2026**. Phases 0–6 and Phase 8's showcase prep come first.
Phase 7 is a menu: do as much of it as the time allows.

---

## Decisions (defaults Claude Code may assume)

Before Phase 3, ask the user to confirm or change these. If they don't answer, use the defaults
and say which ones you used.

| # | Question | Default |
|---|---|---|
| D1 | Fee formula for both **Feature** and **Reserve** | 3% of the item price if price < $20, otherwise 5%. Round half-up to the cent. Minimum $0.50 (Stripe's floor). |
| D2 | Does the reservation deposit count toward the price? | **Yes.** At "Buy", the buyer pays `price − deposit`. |
| D3 | How long does a reservation last? | **48 hours.** After that it expires and the item becomes available again. |
| D4 | Deposit when the **seller** cancels a reservation | Full automatic refund through the Stripe Refund API. |
| D5 | Deposit when the **buyer** cancels, or doesn't buy before it expires | Kept (non-refundable). The UI says so before the buyer pays. |
| D6 | Who gets the purchase money? | It is collected on the platform's Stripe account, and an `Order` records the amount owed to the seller. Real payouts (Stripe Connect) come after the showcase. |
| D7 | Free items (price $0) | Allowed, but they can't be reserved or bought. Users message the giver instead. Paid listings must be at least $1.00. |
| D8 | Chat transport | Spring WebSocket + STOMP, with `@stomp/stompjs` on the client. See [why not Socket.IO](#why-stomp-and-not-socketio). |

**Confirmed by the user on 2026-10-05: D1–D8 all use the defaults above.**

### Fee examples (D1). Implement these as unit tests.

| Price | Rate | Raw | Charged |
|---|---|---|---|
| $12.00 | 3% | $0.36 | **$0.50** (minimum) |
| $19.99 | 3% | $0.5997 | **$0.60** |
| $20.00 | 5% | $1.00 | **$1.00** |
| $150.00 | 5% | $7.50 | **$7.50** |

---

## Phase 0: Guardrails and baseline

**Goal:** a green, trustworthy starting point and an automatic safety net.

1. `git pull`. Run `./mvnw test`, `npm run lint` and `npm run build`. Record the test count in the
   report. If anything fails, fix that first.
2. Add `spring-boot-starter-actuator`. Expose only `health`
   (`management.endpoints.web.exposure.include=health`) and permit `GET /actuator/health` in
   `SecurityConfig`. Today it returns 401, which is wrong for a health check.
3. Add GitHub Actions CI at `.github/workflows/ci.yml`. It runs on push and pull requests to `main`
   with two jobs:
   - **api:** Temurin 17, `./mvnw -B test`
   - **web:** Node 22, `npm ci && npm run lint && npm run build`
   - Cache Maven and npm.
4. Check `.gitignore` covers `y/`, `.firebase/`, `.env`, `target/`, `dist/` and `node_modules/`.
5. Ask the user to confirm the forgot-password email really arrives. It was never tested against
   the real SMTP server.

**Verify:** CI is green on GitHub. `curl <api>/actuator/health` returns `{"status":"UP"}` after
the next deploy.

---

## Phase 1: Accounts (phone number, real profile)

**Read first:** `entity/User.java`, `dto/SignupRequest.java`, `service/AuthService.java`,
`dto/CurrentUserResponse.java`, `controller/UserController.java`, `security/UserPrincipal.java`,
and on the web side `pages/SignupPage.tsx`, `api/auth.ts`, `api/users.ts`, `context/AuthProvider.tsx`.

### Backend
1. **Phone number on `User`.** Store it in E.164 form (`+8801XXXXXXXXX`), with a unique
   constraint. The column is **nullable**, because existing users have no phone.
2. **Signup validation.** `SignupRequest.phone` is required. Normalize it: strip spaces and dashes,
   then accept `01XXXXXXXXX`, `8801…` or `+8801…`. After normalizing it must match
   `^\+8801[3-9]\d{8}$`.
   - Put this in a reusable `@BangladeshiPhone` validator plus a `PhoneNumbers.normalize()` helper,
     following the style of `AllowedEmailDomain`.
   - A duplicate phone returns 409, with the same message style as a duplicate email.
3. **`GET /api/users/me`** returns `id, fullName, email, phone, role, createdAt`. Load the user from
   the database; `UserPrincipal` doesn't carry `fullName`.
4. **`PATCH /api/users/me`** with `{fullName?, phone?}` updates the profile, with the same
   validation as signup.
5. **`POST /api/users/me/password`** with `{currentPassword, newPassword}`.
   - Check the current password.
   - Set `passwordChangedAt`, which logs out every other session. That logic already exists for reset.
   - Return a fresh token pair so the current tab stays logged in.
6. **Privacy rule.** A user's phone number is never in a public DTO. It is shown only to the other
   side of an active reservation or order (Phase 4).
7. **Tests:**
   - Validator: valid and invalid formats.
   - Normalization.
   - Signup with a duplicate phone.
   - `/me` shape.
   - PATCH validation.
   - Password change: wrong current password, and old refresh tokens rejected afterwards.

### Frontend
1. Add a phone field to signup with the hint `01XXXXXXXXX`. Validate it on the client too, and
   show the server's field errors.
2. Add a `/profile` page: edit name and phone, change password, show email (read-only) and
   "Member since".
3. Existing users without a phone see a dismissible banner: "Add your phone number so buyers can
   reach you", linking to `/profile`.
4. The auth context stores the full `/me` response, including `fullName`, and refreshes it after a
   profile edit.

**Verify:** sign up with a new phone; a duplicate phone is rejected; the profile edit persists
after a reload; after a password change, another browser's session is logged out.

---

## Phase 2: Design system and core page redesign

The UI must look like a real marketplace (OLX, Carousell, Facebook Marketplace, Vinted), not a
template or a school project. Build the foundation first, so the pages added in later phases
(orders, chat) are born in the new design.

### Diagnosis of the current UI. Fix all of it.
- The browse page is mostly empty space. One small, heavily framed card sits in a wide column.
  There is no category navigation and nothing invites a user to explore.
- The header shows "Log out (email)" instead of the user. There is no avatar and no menu.
- There are no images or placeholders that feel intentional; a missing photo looks broken.
- The condensed display font is used everywhere, which reads like a poster, not a product.
- There are no loading skeletons, no toasts, and the mobile layout is weak.

### Design direction
- **Image first.** On a marketplace the photo sells the item. Cards are mostly photo: square
  `aspect-square` with `object-cover`, a 12px radius, and no card border. The price is large and
  bold under the photo, then a one-line title, then muted meta text ("Electronics, 2h ago").
- **Density.** The grid is 2 columns on mobile, 3 on tablet, and 4–5 on desktop
  (`max-w-7xl`). Use 16px gaps.
- **Brand.** Keep SEU navy `#1b2a4a` as the brand color for the header, primary buttons and links.
  Pages are white. Use one warm accent (the current gold `#d9a441`) **only** for the "Featured"
  badge and the Feature button.
  - Neutrals are a gray scale (`#f6f7f9` page background, `#e5e7eb` borders, `#6b7280` muted text).
  - Status colors: green for success and available, amber for reserved, gray for sold, red for
    errors.
- **Type.** Use one sans family for everything: **Plus Jakarta Sans** from Google Fonts, weights
  400/500/600/700. Weight and size carry the hierarchy. Prices use `tabular-nums`. Drop Barlow
  Condensed, except optionally in the logo wordmark.
- **Restraint.** No gradient blobs, no glassmorphism, no emoji in the UI, no ALL-CAPS eyebrow
  labels, no "→" on every button. Use shadows only on menus, dialogs and the sticky action panel.
- **Motion.** 150ms color and opacity transitions, plus skeleton shimmer. Nothing bounces.

### Libraries (add these)
- `lucide-react` for icons.
- `@radix-ui/react-dropdown-menu` and `@radix-ui/react-dialog` for accessible menus, modals and the
  mobile nav sheet.
- `sonner` for toasts. They replace inline success alerts for actions.
- `@tanstack/react-query` for server state: caching, refetching, mutations, and invalidation after
  payments and chat. Migrate `useFetch` callers page by page. Keep the existing axios client and its
  token refresh.

### Components (`src/components/ui/`)
`Button` (primary / secondary / ghost / danger, sizes, loading state), `Input`, `Select`,
`Textarea`, `Badge`, `Avatar` (initials fallback, deterministic background color), `Skeleton`,
`EmptyState` (icon, title, body, action), `Dialog`, `DropdownMenu`, `PriceTag`, `ImageWithFallback`
(category icon on a neutral background when there's no image).

### Header
- **Left:** the logo.
- **Center:** a prominent search input that submits to `/listings?q=…`.
- **Right:**
  - a "Sell" primary button;
  - a messages icon with an unread badge (wired in Phase 6);
  - the **user menu**. The trigger is an avatar with initials and the first name. The menu shows
    the full name and email at the top, then My listings, Purchases, Sales, Saved, Messages,
    Profile, Admin (admins only), a divider, and Log out.
- Logged-out users see "Log in" and "Sign up".
- **Mobile:** logo, search icon and avatar. A hamburger opens a sheet with the same links.

### Pages to redesign in this phase
1. **Browse (`/listings`)**
   - A category chip row with an icon per category.
   - A "Featured" horizontal row at the top.
   - Filters: price range, sort, and a condition filter once Phase 7 adds it. On mobile they live
     in a sheet.
   - A result count, the grid, skeletons while loading, an empty state with a "Sell something"
     call to action, and pagination (or "Load more").
   - **Featured listings sort first by default.** Today the search ignores featured status; add
     that to the backend sort.
2. **Listing detail**
   - Desktop has two columns. On the left, a large image (a gallery once Phase 7 adds multiple
     images). On the right, a **sticky** panel with:
     - price, title and status badge;
     - the actions (Feature / Reserve / Buy / Message seller), each showing its server-quoted fee;
     - a seller card: avatar, name, "Member since", and a link to the seller's other listings;
     - safety tips: meet on campus in public, inspect before paying the balance.
   - Below: the description and the category.
   - On mobile, the action bar is fixed at the bottom of the screen.
3. **Auth pages** (login, signup, verify OTP, forgot and reset password): a centered card on a
   quiet background with a short SEU-specific line ("Only @seu.edu.bd students").
4. **My listings:** tabs for Active / Reserved / Sold. Each row shows the image, title, price,
   status, featured-until date, and actions (edit, mark sold, feature, delete with confirmation).
5. **Create/edit listing:** an image dropzone with preview, a live price input, a category select,
   and a description character counter. On success, show a toast and redirect to the listing.
6. **404, error boundary and payment success/cancel pages** in the new style.

### Visual QA loop (required)
After each page, run `npm run dev` and use Playwright to take screenshots at 390×844 and 1440×900:
`npx playwright screenshot` or a small script. Install with `npx playwright install chromium` if
it's missing.
- **Look at the screenshots.** Fix overflow, cramped spacing, unreadable contrast, broken
  alignment, and anything that looks unfinished.
- Check keyboard focus is visible and every image has `alt` text.
- Put the final screenshots in `docs/screenshots/` so the user can review them.

**Verify:**
- Lint and build pass.
- Screenshots exist for every page at both sizes.
- Lighthouse accessibility is ≥ 90 on browse and detail.
- The header shows the user's name, not "Log out (email)".

---

## Phase 3: Fees calculated from the price

**Read first:** `config/PaymentProperties.java`, `service/PaymentService.java`, `application.yml`
(`app.payments`), `dto/CheckoutSessionResponse.java`, `components/CheckoutButton.tsx`,
`components/ListingPaymentActions.tsx`.

### Backend
1. Replace the fixed `featureListingAmount` / `reserveItemAmount` with a configurable fee policy:
   ```yaml
   app.payments.fee:
     threshold: ${PAYMENT_FEE_THRESHOLD:20.00}      # price below this uses low-rate
     low-rate: ${PAYMENT_FEE_LOW_RATE:0.03}
     high-rate: ${PAYMENT_FEE_HIGH_RATE:0.05}
     minimum-cents: ${PAYMENT_FEE_MINIMUM_CENTS:50}
   ```
   Validate it at startup: rates are in (0, 1), and the minimum is ≥ 50 for USD.
2. Add `FeeCalculator`, a pure class with no Spring dependencies. Use it like this:
   ```java
   long feeCents(BigDecimal price)
   ```
   It applies the D1 rules. Unit-test the table above, plus the edge cases: exactly the threshold,
   $0.01 below it, large prices, and price = 0 (rejected for paid flows, see D7).
3. Add `GET /api/listings/{id}/fees`. It returns a quote that the UI shows on the buttons:
   ```json
   { "featureCents": 50, "reserveCents": 50, "purchaseCents": 1150, "currency": "usd",
     "rate": 0.03, "basis": "12.00" }
   ```
   `purchaseCents` is `price − deposit` (D2). It is only meaningful for the current reserver.
4. `PaymentService.createCheckoutSession` uses the calculator. Store the audit fields on `Payment`
   (all nullable): `basisPriceCents` and `rateBasisPoints`.
5. **Lock the price while reserved.** `ListingService.update` rejects a price change on a reserved
   listing with 409 "Price can't change while the item is reserved".
6. Listing validation follows D7: the price is 0 (free) or at least 1.00.
7. Update `.env.example` and remove the dead `PAYMENT_*_AMOUNT` variables. Tell the user to remove
   them from Cloud Run too, using `--remove-env-vars`.

### Frontend
- Buttons read the quote. For example, "Feature for 7 days: $0.50" and "Reserve: $0.50 deposit".
  Put a `(3% of price, min $0.50)` tooltip or help text under the button.
- Before checkout, the reserve button opens a dialog with the D3/D4/D5 terms: 48 hours, deposit
  credited toward the price, refundable only if the seller cancels. The user ticks "I understand"
  before continuing.

**Verify:**
- Stripe test card `4242 4242 4242 4242`.
- A $12 listing charges $0.50, a $25 listing charges $1.25, and the Stripe dashboard shows the same
  amounts.
- Webhook replays don't double-apply.

---

## Phase 4: Reserve, then buy, with orders

**Goal:** the student who reserved an item can click **Buy**, pay the balance, and the sale is
recorded. Everyone sees the right state.

### Data model (additive)
- `ListingStatus`: add `RESERVED`? **No.** Keep `ACTIVE`/`SOLD` and derive "reserved" from
  `reservedBy`, as the code does today. This avoids migrating existing rows.
- `Listing`: add `reservationExpiresAt` (nullable), `buyer` (nullable FK) and `soldAt` (nullable).
- `PaymentPurpose`: add `PURCHASE`. Add `PaymentStatus.REFUNDED`.
- `Payment`: add `stripePaymentIntentId` (nullable). Today only the Checkout Session ID is stored,
  but refunds need the PaymentIntent. Save `session.getPaymentIntent()` in `markSucceeded`.
  For deposits that were paid before this change, look it up from the session
  (`Session.retrieve(id).getPaymentIntent()`) behind the `CheckoutGateway` interface.
- New `Order` entity (`orders` table):
  - `id`, `listing` (SET NULL on delete), `buyer`, `seller`
  - `itemPriceCents`, `depositCents`, `balanceCents`
  - `status`: `PAID` → `COMPLETED`, or `CANCELLED`
  - `payoutStatus`: `PENDING` or `PAID_OUT`, set by an admin
  - `createdAt`, `completedAt`

### Flows
1. **Reserve.** This flow already exists. On success, set `reservationExpiresAt = now + 48h` (D3)
   and email both sides. The email to the seller says who reserved and includes the buyer's phone.
   The email to the buyer includes the seller's phone and the expiry time.
2. **Buy.** Calls `POST /api/payments/checkout-session` with `purpose=PURCHASE`.
   - Allowed only for `listing.reservedBy == caller`, while the reservation hasn't expired and the
     listing is `ACTIVE`.
   - The amount is `priceCents − depositCents` (look up the SUCCEEDED `RESERVE_ITEM` payment).
   - In the webhook:
     - lock the listing and the payment;
     - mark the listing SOLD and set `buyer`, `soldAt`;
     - clear the reservation;
     - create the `Order`;
     - email both sides.
   - This must be idempotent, like the existing handlers.
3. **Seller cancels a reservation:** `POST /api/listings/{id}/reservation/cancel`. The deposit is
   refunded with `Refund.create(payment_intent)` behind the `CheckoutGateway` interface (add a
   `refund` method so tests can fake it). The payment becomes `REFUNDED` and the listing becomes
   available again.
4. **Buyer cancels:** `POST /api/listings/{id}/reservation/release`. The deposit is not refunded
   (D5), and the listing becomes available again.
5. **Expiry.** A `@Scheduled(fixedDelay = 5 min)` job (with `@EnableScheduling`) releases
   reservations whose `reservationExpiresAt < now`. It does not refund. Make it safe to run on
   several instances: select with `FOR UPDATE SKIP LOCKED`, or a conditional update
   `WHERE reserved_by IS NOT NULL AND reservation_expires_at < now`.
6. **Fix the existing race.** When two deposits succeed for the same item, the second one is
   **automatically refunded**. The current code only logs "deposit needs refunding".
7. **Endpoints:**
   - `GET /api/orders/purchases` and `GET /api/orders/sales` (paged).
   - `GET /api/listings/{id}` includes `reservedByMe`, `reservationExpiresAt`, and (only for the
     two parties) the counterparty contact.
   - Admin: `GET /api/admin/orders` and `PATCH /api/admin/orders/{id}/payout`.

### Frontend
- **Listing detail action panel, by viewer:**

  | Viewer | What they see |
  |---|---|
  | Anyone, active and not reserved | Reserve (with the quote) and Message seller |
  | The reserver | "Reserved by you: expires in 1d 4h", **Buy now: $11.50** (balance shown as price − deposit), "Release reservation", and the seller's phone |
  | Other viewers of a reserved item | "Reserved", disabled, with "Message seller" still available |
  | The owner | Feature, Edit, Mark sold, and, when reserved, the buyer's name and phone, "Cancel reservation (refunds deposit)" with a confirmation dialog |
  | Anyone, sold | A "Sold" badge, no actions |
- Add **Purchases** and **Sales** pages (from the user menu): order cards with the image, price
  breakdown (item / deposit / balance), counterparty, date and status.
- **Payment success page:**
  - It polls `GET /api/listings/{id}` (or the payment) until the webhook has applied, with a
    spinner and a 15s timeout. Then it shows the right message: "Featured until…", "Reserved for
    48h", or "Purchase complete".
  - On timeout it shows "Payment received; it may take a minute to show".

**Verify:** an end-to-end run with two test accounts (A sells, B buys), covering:
- reserve → buy → the item is sold and the order appears in both accounts;
- reserve → seller cancels → refunded in the Stripe dashboard;
- reserve → wait (temporarily set expiry to 2 minutes in a test profile) → auto-released.

There are tests for every authorization rule (only the reserver can buy, only the owner can cancel)
and for webhook idempotency on `PURCHASE`.

---

## Order of work from here (agreed 2026-10-05)

P4 → P4a (deployed 2026-10-05) → P5 → P6 → **P4d** → **P4c** → **deploy checkpoint** (give the deploy plan, wait for
"go") → **P3b** → **P4b**. (Revised 2026-10-05; the earlier order was P3b, P4b, P4c, P5, P6.)
Stripe stays in test mode (no real money). The reserve dialog and the checkout result pages carry the notice
"Demo mode: payments are simulated. Use test card 4242 4242 4242 4242." After each phase, say whether it is
safe to deploy again. Commit and push after every finished step.

---

## Phase 3b: Posting fee (new revenue stream)

- When a student creates a listing priced **above $5.00**, they pay a posting fee. Same `FeeCalculator` as D1
  (3% below $20, otherwise 5%, round half-up, minimum $0.50). Prices of $5.00 or less, including free, post for
  nothing.
- `ListingStatus.PENDING_PAYMENT` (additive). A pending listing is hidden from browse, search and other users'
  detail views. After creating it, the owner goes to Stripe Checkout (`PaymentPurpose.POSTING_FEE`). The webhook
  sets the listing ACTIVE; idempotent and row-locked like the existing handlers.
- If checkout is abandoned or fails, the listing stays `PENDING_PAYMENT`. My listings shows it with a
  "Pay to publish" button. The scheduled job deletes `PENDING_PAYMENT` listings older than 24h.
- Store `postingFeeBasisCents` on `Listing` (nullable). Once published, the price cannot be raised above the
  price that was paid for: 409, tell the owner to repost. Existing ACTIVE listings are grandfathered.
- Config: `PAYMENT_POSTING_FEE_ENABLED` (default true) and `PAYMENT_POSTING_FEE_THRESHOLD` (default 5.00), so it
  can be switched off for the demo.
- The create page shows a live quote ("Posting fee: $0.60 (3%), paid after you submit"; "Free" at $5 or less).
- The startup enum-constraint sync also covers `ListingStatus.PENDING_PAYMENT`, `PaymentPurpose.POSTING_FEE` and
  `listings_status_check`. It touches only the enum CHECK constraints on `payments` and `listings`.
- Tests: fee boundaries ($5.00 vs $5.01), webhook idempotency, hidden-until-paid, the price-raise block.

## Phase 4b: Revenue ledger and admin revenue page (after P3b)

- Revenue sources: `POSTING_FEE`, `FEATURE_FEE`, `FORFEITED_DEPOSIT` (a deposit the platform keeps when the buyer
  releases or the reservation expires). Not revenue: a deposit while held, and purchase money that belongs to
  sellers (shown as "owed to sellers"). Refunds are subtracted.
- New additive table `ledger_entries`: id, type, amountCents (signed, refunds negative), currency, paymentId,
  listingId, userId, occurredAt. Entries are written in the same transaction as the event. Backfill existing
  succeeded FEATURE_LISTING payments, idempotently.
- `GET /api/admin/revenue?from&to&groupBy=day|week|month` (totals per source, net total, series) and
  `GET /api/admin/revenue/entries` (paged).
- Admin page: date range picker, tiles (net revenue, revenue per source, owed to sellers), a stacked per-day
  chart by source, and a history table (date, source, listing, user, amount) with CSV export. Note on the page:
  "before Stripe processing fees".
- Tests: refunds subtract, a deposit counts only when forfeited, backfill is idempotent, admin-only access.

## Phase 4d: Meetup spot and receipt confirmation (before P4c)

- Listings get an optional `meetupSpot` (nullable enum: MAIN_GATE, LIBRARY, CAFETERIA, PERMANENT_CAMPUS, OTHER).
  Add it to the create and edit forms, show it on the listing detail page, and show it on both sides of an order.
- ONLINE orders start as PAID. The buyer sees "Confirm I received it" and "Report a problem" on the order.
  Confirm sets COMPLETED and `completedAt`.
- A scheduled job auto-completes PAID orders 72 hours after payment unless a problem report exists. A report
  freezes the order (no auto-complete, no payout) and shows in an admin queue.
- Admin payout is allowed only for COMPLETED orders.
- Both sides see each other's phone and the meetup spot on the order; nobody else does.
- Tests: only the buyer can confirm, no double confirm, auto-complete timing, payout blocked while PAID or
  reported, and `meetupSpot` validation.

## Phase 4c: Cash at meetup (after P4d)

- The reserver chooses "Pay online" (the existing Buy flow) or "Pay cash at meetup". The cash choice shows the
  seller's phone, the meetup spot and the exact cash amount (price minus deposit).
- Orders get `paymentMethod` ONLINE or CASH. For cash, the seller confirms with "Confirm handover and cash
  received". That marks the listing SOLD and the order COMPLETED and emails both sides. Only that listing's
  seller can confirm.
- The deposit still counts toward the price. For a cash order, "owed to seller" is the deposit only, shown
  correctly on the admin revenue page. Normal expiry rules apply if the seller never confirms.
- Tests: only the seller can confirm, no double confirm, the owed-to-seller amount, an expired reservation can't
  be confirmed.

---

## Phase 5: Caching with Spring's cache annotations

**Goal:** reduce database load on hot reads using `@Cacheable`, `@CacheEvict`, `@CachePut` and
`@Caching`, done correctly.

### Setup
- Add the dependencies `spring-boot-starter-cache` and `com.github.ben-manes.caffeine:caffeine`.
- Add a `CacheConfig` with `@EnableCaching`. It builds a `CaffeineCacheManager`, or better, one
  `CaffeineCache` per cache name so each can have its own TTL and size:

| Cache | Key | What it holds | TTL / max |
|---|---|---|---|
| `listingById` | `#id` | `ListingResponse` (public view) | 5 min / 2,000 |
| `featuredListings` | `'home'` | list of featured `ListingResponse` | 1 min / 1 |
| `categoryCounts` | `'all'` | `Map<Category, Long>` for the chips | 5 min / 1 |
| `userProfile` | `#userId` | `CurrentUserResponse` | 10 min / 5,000 |
| `feeQuote` | `#listingId` | fee quote | 5 min / 2,000 |

### Rules (put a short comment about these in `CacheConfig`)
1. **Cache DTOs (immutable records), never JPA entities.** Entities hold lazy proxies, and those
   break outside the session.
2. **Self-invocation bypasses the proxy.** A `@Cacheable` method called from the same class is
   *not* cached. Put cached reads on methods called from controllers or other beans.
3. **Don't cache the personalized parts.** `reservedByMe` and counterparty contact depend on the
   caller. Cache the public `ListingResponse`, then add the viewer-specific fields in the
   controller or service *after* the cache lookup.
4. **Don't cache arbitrary search pages.** Every combination of keyword, filters and page is a
   separate key, so the hit rate is low and correct eviction is impossible. Say so in a comment.
5. **Evict on every write that changes what's cached.** Use `@Caching(evict = {...})`:
   - Listing update, mark sold, delete, admin delete → `listingById#id`, `featuredListings`
     (all entries), `categoryCounts`, `feeQuote#id`.
   - Listing create → `featuredListings`, `categoryCounts`.
   - Payment success (feature, reserve, purchase), reservation cancel, release and expiry →
     `listingById#listingId`, `featuredListings`. The webhook path isn't a simple method argument,
     so call a small `ListingCacheEvictor` bean that uses `CacheManager` directly. Do the eviction
     **after commit** with `TransactionSynchronization.afterCommit` or
     `@TransactionalEventListener(phase = AFTER_COMMIT)`, so a rollback can't leave the cache
     ahead of the database.
   - Profile update → `@CachePut` or `@CacheEvict` on `userProfile#userId`. User deactivation
     evicts `userProfile` and clears `listingById` (owner-inactive listings must disappear).
6. **Time-based staleness.** `featured` is computed at read time from `featuredUntil`, so a cached
   entry can say "featured" for up to the TTL after it expires. That's acceptable with a 5-minute
   TTL; note it in a comment.
7. **Multiple instances.** Caffeine is per instance, so short TTLs bound how long instances can
   disagree. If the service ever scales out a lot, the next step is Redis
   (`spring-boot-starter-data-redis`) with the same annotations.
8. Turn on `recordStats()` and expose `/actuator/caches` and `/actuator/metrics` to admins only, or
   leave them unexposed.

### Tests
A `@SpringBootTest` with a `@MockitoSpyBean` repository (or a call counter):
- Two `get(id)` calls hit the repository **once**.
- After `update`, `markSold`, `delete` or the payment webhook, the next `get` hits it again.
- Viewer-specific fields are correct for two different users after the response is cached.

**Verify:** tests are green. Locally, enable `logging.level.org.springframework.cache=TRACE` and
watch hits and misses while you click around.

---

## Phase 6: Real-time chat between students

**Goal:** a buyer can message a seller about a listing. Messages appear live, persist, and show
unread counts.

### Why STOMP and not Socket.IO
Socket.IO is its own protocol. A Spring server would need `netty-socketio`, which:
- runs a **second server on a second port**, which Cloud Run can't expose (one port per service);
- is barely maintained;
- duplicates the JWT security setup.

Spring's built-in WebSocket support with STOMP runs on the same port and the same security, and
it's the standard choice in Spring shops. The client uses `@stomp/stompjs` over a native
WebSocket. If the user still wants Socket.IO specifically, stop and discuss it. It would mean a
separate Node service.

### Backend
1. Add the dependency `spring-boot-starter-websocket`.
2. **Entities:**
   - `Conversation`: `id`, `listing` (SET NULL), `buyer`, `seller`, `createdAt`, `lastMessageAt`.
     Unique on `(listing_id, buyer_id)`.
   - `Message`: `id`, `conversation`, `sender`, `body` (≤ 2,000 characters), `createdAt`,
     `readAt`. Index on `(conversation_id, id)`.
3. **REST** is the source of truth for all writes, so the existing validation, error handling and
   auth just work:
   - `POST /api/conversations {listingId}` gets or creates a conversation. You can't message your
     own listing (400).
   - `GET /api/conversations` is the inbox: counterparty, listing thumbnail and title, last
     message preview, unread count. Newest first.
   - `GET /api/conversations/{id}/messages?beforeId=&size=30` uses cursor pagination. Participants
     only (403).
   - `POST /api/conversations/{id}/messages {body}` persists the message, **then pushes** it over
     WebSocket to both participants.
   - `POST /api/conversations/{id}/read` marks messages from the other side as read.
   - `GET /api/conversations/unread-count` drives the header badge.
4. **WebSocket, used for push only:**
   - Endpoint `/ws`. Broker `enableSimpleBroker("/queue")`, user prefix `/user`.
   - A `ChannelInterceptor` on `CONNECT`:
     - reads the `Authorization: Bearer …` STOMP header;
     - validates it with `JwtService` (access token, not expired, passes the `passwordChangedAt`
       check);
     - sets the `Principal` name to the user ID;
     - rejects anything invalid.
   - Push with `SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/messages", dto)`. Push
     unread-count updates the same way, on `/queue/unread`.
   - `SecurityConfig` permits the `/ws` handshake. Auth happens at STOMP CONNECT. Allowed origins
     come from `APP_CORS_ALLOWED_ORIGINS` (`setAllowedOriginPatterns`).
5. **Rate limit:** 20 messages per minute per user, returning `TooManyRequestsException`.
6. **Cloud Run settings.** Ask the user before applying them:
   - `--timeout=3600`: WebSocket connections are cut at the request timeout. The client reconnects.
   - `--max-instances=1`: the simple broker is in memory, so with two instances a message pushed on
     instance A won't reach a user connected to B. That's fine at campus scale. The scale-out path
     is a STOMP broker relay (RabbitMQ) or Redis pub/sub. Document it, don't build it.
7. **Tests:**
   - Participant authorization on every endpoint.
   - Get-or-create is idempotent.
   - Can't message yourself.
   - Unread counts.
   - A STOMP CONNECT with a bad token is rejected (an integration test with `WebSocketStompClient`).

### Frontend
1. Add `@stomp/stompjs`. Create one `ChatSocketProvider` that:
   - connects when the user is logged in and sends the access token in the CONNECT headers;
   - reconnects with backoff, using a fresh token after a refresh;
   - **on reconnect, refetches** the open thread and the inbox, so messages sent during the gap
     show up;
   - feeds incoming messages into the React Query caches.
2. **`/messages`:**
   - On desktop, two panes: the inbox list on the left and the thread on the right. On mobile,
     the list, then the thread.
   - The thread has date separators, grouped bubbles (mine on the right in navy, theirs on the
     left in light gray), timestamps, and "Seen" on the last message I sent.
   - The composer auto-grows, sends on Enter, and uses Shift+Enter for a newline. It sends
     optimistically and retries on failure.
   - A listing context card at the top of the thread, linking to the listing.
3. Add a "Message seller" button on listing detail that creates or opens the conversation.
4. The messages icon in the header shows the unread badge, updated live.

**Verify:**
- Two browsers (A and B): B messages A from a listing, A sees it live without refreshing, and the
  unread badge updates.
- Restart the backend locally. Both clients reconnect and catch up.

---

## Phase 7: Features that make it feel real (prioritized menu)

Do them in this order, as time allows. Each one needs backend tests and a UI built from the Phase 2
components.

1. **Item condition + campus meetup spot.** `condition` is an enum: New, Like new, Good, Fair.
   `meetupSpot` is an enum of real SEU spots: Main gate, Library, Cafeteria, Permanent campus,
   Other. Both are nullable columns. Show them on cards and in filters.
2. **Multiple photos (up to 5).** Add a `listing_images` table with `url`, `position`, and
   Cloudinary `publicId` so images can be deleted. Keep `imageUrl` as the cover so nothing old
   breaks. The detail page gets a gallery with thumbnails and swipe on mobile. The create page
   supports reorder and remove.
3. **Saved listings (wishlist).** `POST/DELETE /api/listings/{id}/save` and `GET /api/me/saved`.
   Add a heart on cards (optimistic) and a "Saved" page.
4. **Public seller profile** at `/users/{id}`: name, member since, active listings, items sold,
   and the rating (item 7). No email and no phone.
5. **Report a listing.** Reasons: scam, prohibited item, wrong category, other. Admins get a
   moderation queue in the dashboard with resolve, delete-listing and deactivate-user actions.
6. **In-app notifications.** A bell with a dropdown for: reserved, reservation expiring in 6h,
   purchased, new message, report resolved. Store them in a `notifications` table and push them
   over the Phase 6 socket on `/queue/notifications`.
7. **Ratings after purchase.** The buyer rates the seller 1–5 with an optional comment, once per
   order. Show the average on the seller profile and the listing seller card.
8. **Admin dashboard upgrade:**
   - KPI tiles: users, active listings, orders, fee revenue this week (sum of SUCCEEDED
     FEATURE + RESERVE payments, minus refunds).
   - Payouts owed to sellers (the D6 ledger).
   - A recent payments table.
9. **"Bump to top"**, a cheaper re-list fee. Only if the user wants another revenue stream.

---

## Phase 8: Production hardening and showcase prep

### Hardening
1. **Flyway.**
   - Add `flyway-core` and `flyway-database-postgresql`.
   - Generate `V1__baseline.sql` from the *current production schema*. Ask the user to run
     `pg_dump --schema-only` against Supabase, or use Supabase's schema export.
   - Set `spring.flyway.baseline-on-migrate=true` and `baseline-version=1`.
   - Switch `JPA_DDL_AUTO` to `validate` in production.
   - From then on, every schema change is a new `V{n}__*.sql`.
   - Do this **after** the showcase if time is tight. It's the riskiest item here.
2. **Rate limiting** on login, signup, forgot-password and OTP endpoints (Bucket4j in memory, per
   IP and per email). Return 429 with `Retry-After`.
3. **Security headers** on Firebase Hosting (`firebase.json` headers): CSP allowing self,
   Cloudinary, Stripe, the API origin and the `wss:` origin, plus `X-Content-Type-Options`,
   `Referrer-Policy` and `Permissions-Policy`.
4. **Structured logging.** Use JSON logs in production (`logging.structured.format.console=ecs`
   in Boot 3.4+) so Cloud Logging can parse them. Add a request ID to the MDC.
5. **API docs.** Add `springdoc-openapi-starter-webmvc-ui`, enabled only outside prod or behind
   admin.
6. **Frontend:**
   - code-split routes with `React.lazy`;
   - long-cache hashed assets and `no-cache` for `index.html` in `firebase.json`;
   - a favicon and Open Graph meta tags so shared links preview nicely.

### Showcase prep (do this regardless of what else got done)
1. **Seed demo data.** Write a script, `scripts/seed-demo.ts` or `.http`, that uses the real API:
   - 3 accounts: seller, buyer and admin, with the passwords given to the user privately (not
     committed);
   - about 20 realistic listings across all categories, with real-looking photos uploaded to
     Cloudinary and believable SEU-flavored titles and prices in the $3–$120 range;
   - 3 featured listings;
   - 1 reserved listing;
   - 1 sold listing with an order;
   - one chat thread.
2. **Demo script** at `docs/DEMO.md`: a 5-minute click path covering signup with OTP, browse,
   feature payment, reserve → buy, chat in two windows, and the admin dashboard. Include the
   Stripe test card and a fallback for each step if the network is slow.
3. **On showcase day:** `gcloud run services update campus-marketplace-api --region=asia-south1
   --min-instances=1` to avoid cold starts. **Set it back to 0 afterwards**, because an always-on
   instance costs money.
4. **Security housekeeping.** Some secrets were pasted into chats during setup, so remind the user
   to rotate them after the showcase:
   - Stripe webhook signing secret (Stripe → Webhooks → Roll secret, then update Secret Manager);
   - Cloudinary API secret;
   - SMTP mailbox password;
   - Supabase DB password;
   - the GitHub PAT.

---

## Phase 9: AI shopping assistant (Gemini, free tier)

An "Ask AI" button on the listing detail page. It opens a panel (a bottom sheet on phones, a side dialog on
desktop) with three tools:
1. **Full specs.** Gemini identifies the product from the cover photo, title and description, searches the
   web and lists its specifications. An optional "What will you use it for?" box tailors them and says whether
   the item fits that need.
2. **Is it a good buy?** Gemini looks up second-hand prices (Bangladesh first, then the region), compares them
   with this listing's price and condition, and returns a verdict: Good deal / Fair price / Overpriced / Not
   enough data, with 2 to 3 reasons and the prices it found (market + currency).
3. **Chat.** Free questions about the product, with up to 6 previous turns kept in the browser only.

Every answer shows its web sources and "AI estimate. Specs and prices may be wrong, check before you buy."

### Backend
- `POST /api/listings/{id}/assistant` (logged in). `AssistantRequest {mode SPECS|VERDICT|ASK, need ≤200,
  question ≤500 (required for ASK), history ≤6 turns of {role user|assistant, text ≤2000}}` →
  `AssistantResponse {answer, verdict (VERDICT only), sources[{title,url}], disclaimer, cached}`.
- `AssistantGateway` with `GeminiAssistantGateway` (RestClient, `x-goog-api-key`, `google_search` tool, timeout
  from config) and `FakeAssistantGateway` (`ASSISTANT_GATEWAY=fake`, for tests and local demos).
- `AssistantProperties` (`app.assistant.*`): `ASSISTANT_ENABLED` (default false), `ASSISTANT_MODEL`,
  `GEMINI_API_KEY` (Secret Manager only), per-user limit (10/hour), global limit (150/day), timeout (25s, raised to 45s on 2026-10-12).
- Off or no key → 503, and `GET /api/config` returns `assistantEnabled=false` so the UI hides the button.
- Gemini gets the first Cloudinary photo (fetched server-side, ≤4 MB, only from `res.cloudinary.com`), title,
  description, category, condition, price and currency. Never a name, email or phone.
- The seller's text is untrusted data in a delimited block; the system prompt says to ignore instructions in
  it, stay on topic, never invent prices or sources, and start VERDICT answers with a fixed `VERDICT:` line.
- `assistantAnswers` cache (6 h): SPECS without a need and VERDICT, keyed by listing + mode + price, evicted
  when the listing is edited, its photos change, it is sold or deleted. ASK and SPECS-with-need are never cached.
- Limits per user and global (sliding windows, 429 + Retry-After). Gemini 429/5xx/timeout → 503 "busy".
  Logs carry only mode, listing id, latency and status.

### Frontend
- Sparkles "Ask AI" button near the title (only when `assistantEnabled`; logged out: "Log in to ask AI about
  this item"). Radix Dialog panel, quick actions, need input, chat input, loading/busy/rate-limited/disabled
  states, verdict badge. Plain text only; source links open in a new tab with `rel="noopener noreferrer"`.
- Works at 360/390 px with the on-screen keyboard open.

### Verify
- `./mvnw test`, `npm run lint && npm run build` green.
- Local run with the fake gateway, headless browser at 390 px and desktop, screenshots in `docs/screenshots/`.
- A few real Gemini calls locally (key read from Secret Manager into that one process only).
- Deploy with the secret mounted and the flag off, smoke-test, then turn it on and test live.

---

## Progress

Claude Code ticks these off as it goes (`[x]`) and commits the change with the phase.

- [x] **P0** Baseline green · actuator health · CI · .gitignore · reset-email confirmed  _(CI green)_
- [x] **P1** Phone at signup · /me with name · profile + change password · phone banner
- [x] **P2** Design system · header + user menu · browse · detail · auth pages · my listings · create/edit · screenshots
- [x] **P3** FeeCalculator + tests · fee quote endpoint · price lock · UI quotes + reserve terms dialog
- [x] **P4** Order entity · PURCHASE flow · cancel/release/expiry · race refund · purchases/sales pages · success-page polling
- [x] **P4a** Phase 4 finished: purchase/cancel/release/expiry UI · real-Stripe verification · deploy checkpoint
- [x] **P3b** Posting fee: PENDING_PAYMENT · POSTING_FEE · hidden until paid · stale cleanup · price-raise block · live quote
- [x] **P4b** Revenue ledger · /api/admin/revenue · admin revenue page (chart, CSV)
- [x] **P4d** meetupSpot on listings · buyer confirms receipt / reports a problem · 72h auto-complete · admin problem queue · payout only when COMPLETED
- [x] **P4c** Cash at meetup · paymentMethod · seller confirms handover · owed-to-seller for cash
- [x] **P5** Caffeine caches · @Cacheable/@CacheEvict/@Caching · after-commit eviction · cache tests
- [x] **P6** Conversations/messages · STOMP auth · inbox/thread UI · unread badge · reconnect catch-up
- [x] **P7** (deployed 2026-10-05, backend rev 00016, scripted smoke test passed) condition · up to 5 photos (`listing_images`) · saved listings · seller profile · reports + admin queue · notifications (bell + live push, 6h reservation reminder) · ratings · admin KPI tiles
- [~] **P8** (subset done 2026-10-05) rate limits · route code-splitting · OG tags + favicon · hosting headers (no CSP) · README + DEMO.md. Not done: Flyway, structured logging, API docs, seed script (seeded by hand)
- [~] **P9** AI shopping assistant: Gemini gateway + fake · /api/config · limits (off by default) · cache · Ask AI panel · Check live prices links · README/DEMO/privacy line. Free tier has no Search grounding for current models, so answers come from model knowledge and the price verdict ships switched off (`ASSISTANT_VERDICT_ENABLED=false`). Deploy pending

## Report template (end of every phase)

```
Phase N — <name>: DONE / PARTIAL
Changed:   <bullet list of user-visible changes>
Backend:   <endpoints/entities added or changed>
Tests:     <count before → after>, all green
Verified:  <what you actually clicked/ran, with results>
Deploy:    <exact commands to run, env vars to add/remove>  ← waiting for your go-ahead
Open:      <anything skipped, risks, questions>
```

# Campus Marketplace

A marketplace where Southeast University students (`@seu.edu.bd` emails only) buy and sell second-hand items.
Sellers list items, buyers reserve them with a small deposit, then buy online or pay cash at the meetup. Students
chat in real time, and admins see revenue, orders and moderation queues.

Live: https://campusmarketplacebackend.web.app (payments run in Stripe **test mode**).

## Features

- **Accounts:** signup with a Bangladeshi phone number, email OTP verification, login with refresh tokens,
  forgot/reset password, profile and password change. Rate-limited login, signup and OTP endpoints.
- **Listings:** photos (Cloudinary), categories, meetup spot, search, filters, sort (featured first by default),
  category counts, a Featured row.
- **Fees (Stripe Checkout):** a posting fee for listings above $5.00, a paid Feature (7 days), and a reservation
  deposit. Fees are computed on the server from the price: 3% below $20, 5% from $20, minimum $0.50.
- **Reserve, then buy:** a deposit holds an item for 48 hours and counts toward the price. The reserver pays the
  balance online or in cash at the meetup. The buyer confirms receipt (or reports a problem); orders auto-complete
  after 72 hours unless reported. Seller cancel refunds the deposit; release or expiry keeps it.
- **Chat:** conversations per listing, live over WebSocket (STOMP), unread badge, read receipts.
- **Admin:** users, listings, orders and payouts, problem reports, a revenue ledger with a chart, history and CSV.
- **AI shopping assistant:** "Ask AI" on a listing, powered by Google Gemini (see below).

## AI shopping assistant

On a listing page, logged-in students tap **Ask AI** (sparkle icon). A bottom sheet (phones) or side panel
(desktop) offers:

- **Full specs:** Gemini identifies the product from the cover photo, title and description and lists its
  specifications. An optional "What will you use it for?" box ("CSE coursework", "gaming") tailors the list and says
  whether the item fits that need.
- **Chat:** any question about the item, in English or Bangla. Follow-ups keep up to 6 previous turns. The
  conversation lives only in the browser; the server stores nothing.
- **Check live prices:** buttons that open Google AI Mode, Bikroy and Daraz searches for the identified product in
  a new tab (links built in the browser; nothing is fetched from those sites).

Answers are plain text labelled "From AI knowledge, not web-checked. Check before you buy." If Gemini is out of
quota or failing, the panel says so (with the admin's contact) and the rest of the page keeps working.

**Model and limits.** `ASSISTANT_MODEL` (default `gemini-3.8-flash`) on the Gemini API free tier. Google Search
grounding is not available on the free tier for current models (it returns 429), so answers come from the model's
own knowledge and **"Is it a good buy?" is switched off** (`ASSISTANT_VERDICT_ENABLED=false`): without a search
source it could only make prices up. The price-check code stays in place for when a search source is added: Gemini
would report Bangladeshi prices in BDT in a fixed JSON block, and the server converts them with
`ASSISTANT_BDT_PER_USD`, takes the median and decides the verdict (≤90% good deal, ≤110% fair, above that
overpriced, fewer than 2 prices not enough data). Even then the search can't be strictly limited to Bangladesh: it
is steered by the prompt and filtered by source domain (`*.bd` plus an allowlist), and prices count only from
Bangladeshi pages the answer was grounded on. Free-tier quotas are set per Google project and shown in AI Studio;
our own per-user and global limits exist (`ASSISTANT_PER_USER_PER_HOUR`, `ASSISTANT_GLOBAL_PER_DAY`) but are off
(0 = unlimited) so the app never refuses a request itself. SPECS answers (without a need) are cached for 6 hours
per listing and price and evicted when the listing changes.

**Privacy.** Only the listing's cover photo, title, description, category, condition and price are sent to Google.
No names, emails or phone numbers. The seller's text is treated as untrusted data in the prompt. On the free tier,
Google may use the content it receives to improve its products. The site footer says this too.

**Switches.** `ASSISTANT_ENABLED` (default false), `GEMINI_API_KEY` (from Secret Manager only), `ASSISTANT_MODEL`,
`ASSISTANT_SEARCH_ENABLED`, `ASSISTANT_VERDICT_ENABLED`, `ASSISTANT_THINKING_LEVEL`, `ASSISTANT_TIMEOUT` (25s),
`ASSISTANT_BDT_PER_USD` (123), `ASSISTANT_BANGLADESH_DOMAINS`. `ASSISTANT_GATEWAY=fake` gives canned answers for
local demos without a key.

## Architecture

```
  Browser (React SPA, Firebase Hosting)
     |  HTTPS (REST + JSON, JWT)          |  WSS /ws (STOMP, token at CONNECT)
     v                                    v
  +--------------------------------------------------+      +------------------+
  |  Spring Boot API (Cloud Run, 1 instance)         |<---->|  Stripe          |
  |  controllers -> services -> repositories         | web- |  Checkout +      |
  |  JWT security | caches (Caffeine) | schedulers   | hook |  refunds         |
  |  simple in-memory STOMP broker                   |      +------------------+
  +-----+----------------+----------------+----------+
        |                |                |
        v                v                v
   PostgreSQL        Cloudinary        SMTP server
   (Supabase)        (photos)          (OTP + order emails)
```

The WebSocket broker is in memory, so the service runs as a single Cloud Run instance
(`--max-instances=1`, `--timeout=3600`). Scaling out would need a STOMP broker relay or Redis pub/sub.

## Tech stack

- **Backend:** Java 17, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, PostgreSQL, WebSocket/STOMP,
  Caffeine, Stripe Java SDK, Cloudinary, Maven. Tests use H2.
- **Frontend:** React 19, Vite, TypeScript, Tailwind 4, React Router 6, TanStack Query, Radix UI, lucide-react,
  sonner, @stomp/stompjs.
- **Hosting:** Google Cloud Run (API), Firebase Hosting (SPA), Supabase (database).

## Running locally

Prerequisites: Java 17, Node 22, a PostgreSQL database, and test-mode keys for Stripe and Cloudinary.

```bash
# Backend (http://localhost:8080). Copy the example file, fill in values, and export them.
cd campus-marketplace-api
cp .env.example .env            # .env is git-ignored
set -a; . ./.env; set +a
./mvnw spring-boot:run

# Frontend (http://localhost:5173)
cd campus-marketplace-web
cp .env.example .env            # set VITE_API_URL to the API's address
npm ci
npm run dev
```

For Stripe webhooks locally, forward them with the Stripe CLI to `localhost:8080/api/payments/webhook` and put the
signing secret it prints in `STRIPE_WEBHOOK_SECRET`. Use test card `4242 4242 4242 4242`.

## Environment variables

Names only; see `campus-marketplace-api/.env.example`. Secrets belong in a secret manager, never in the repo.

- **Database:** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JPA_DDL_AUTO`
- **Auth:** `JWT_SECRET`, `JWT_ACCESS_EXPIRATION`, `JWT_REFRESH_EXPIRATION`, `ALLOWED_EMAIL_DOMAIN`,
  `OTP_EXPIRATION`, `OTP_MAX_ATTEMPTS`, `OTP_RESEND_COOLDOWN`
- **Mail:** `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_SMTP_*`
- **Images:** `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `CLOUDINARY_FOLDER`
- **Stripe and fees:** `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `PAYMENT_CURRENCY`, `PAYMENT_SUCCESS_URL`,
  `PAYMENT_CANCEL_URL`, `PAYMENT_FEE_THRESHOLD`, `PAYMENT_FEE_LOW_RATE`, `PAYMENT_FEE_HIGH_RATE`,
  `PAYMENT_FEE_MINIMUM_CENTS`, `PAYMENT_POSTING_FEE_ENABLED`, `PAYMENT_POSTING_FEE_THRESHOLD`,
  `PAYMENT_FEATURE_DURATION`, `RESERVATION_DURATION`, `ORDER_AUTO_COMPLETE_AFTER`
- **AI assistant:** `ASSISTANT_ENABLED`, `GEMINI_API_KEY`, `ASSISTANT_MODEL`, `ASSISTANT_*` (see above)
- **App:** `APP_CORS_ALLOWED_ORIGINS`, `FRONTEND_URL`, `APP_CACHE_ENABLED`, `APP_RATE_LIMIT_ENABLED`,
  `APP_TRUSTED_PROXIES`, `REPORTING_ZONE`, `SERVER_PORT`
- **Frontend:** `VITE_API_URL`

## Tests

```bash
cd campus-marketplace-api && ./mvnw test          # backend (H2)
cd campus-marketplace-web && npm run lint && npm run build && npm test
```

CI (`.github/workflows/ci.yml`) runs both on every push and pull request to `main`.

## Deploy

```bash
# Backend (from the repo root)
gcloud run deploy campus-marketplace-api --source=./campus-marketplace-api \
  --region=asia-south1 --allow-unauthenticated --quiet
# Chat needs one instance and long requests:
#   --max-instances=1 --timeout=3600   (leave min-instances unset)

# Frontend
cd campus-marketplace-web && npm run build && firebase deploy --only hosting
```

The schema is managed by Hibernate (`ddl-auto=update`), so changes are additive only. On start a small sync
re-creates the CHECK constraints on a few enum columns so new enum values are accepted (see
`EnumCheckConstraintSync`). Check `/actuator/health` after a deploy.

## More

- Project rules for contributors and AI assistants: [`CLAUDE.md`](CLAUDE.md)
- The phased work plan and its progress: [`docs/PRODUCTION_PLAYBOOK.md`](docs/PRODUCTION_PLAYBOOK.md)
- A 5-minute demo script: [`docs/DEMO.md`](docs/DEMO.md)

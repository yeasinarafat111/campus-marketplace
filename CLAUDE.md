# Campus Marketplace — notes for Claude Code

A marketplace where Southeast University students (`@seu.edu.bd` emails only) buy and sell
second-hand items. The showcase is on **12 October 2026**.

**Current work plan:** `docs/PRODUCTION_PLAYBOOK.md`. Read it before you change anything. Do one
phase at a time, and keep its progress checklist up to date.

## Layout

| Path | What it is |
|---|---|
| `campus-marketplace-api/` | Spring Boot 3.5 / Java 17 / Maven, JPA + PostgreSQL (Supabase), JWT auth, Stripe Checkout, Cloudinary images, SMTP mail |
| `campus-marketplace-web/` | React 19 + Vite 8 + TypeScript + Tailwind 4 + axios + react-router 6 |

Package root: `com.campus.marketplace` with `config/ controller/ dto/ entity/ exception/
repository/ security/ service/ validation/`.

## Commands

```bash
# Backend (from campus-marketplace-api/)
./mvnw test                       # all tests; uses H2 via src/test/resources/application.yml
./mvnw spring-boot:run            # local run; reads .env values from the environment

# Frontend (from campus-marketplace-web/)
npm run dev                       # http://localhost:5173
npm run lint && npm run build     # must both pass before any commit

# Deploy backend (from the repo root)
export PATH="$PATH:$HOME/CampusMarketPlace/y/google-cloud-sdk/bin"
gcloud run deploy campus-marketplace-api --source=./campus-marketplace-api \
  --region=asia-south1 --allow-unauthenticated --quiet

# Deploy frontend (from campus-marketplace-web/)
npm run build && firebase deploy --only hosting
```

Live URLs:
- Frontend: https://campusmarketplacebackend.web.app
- API: https://campus-marketplace-api-674209085573.asia-south1.run.app
- GCP project: `campusmarketplacebackend`, region `asia-south1`

To set env vars without touching others, use `gcloud run services update campus-marketplace-api
--region=asia-south1 --update-env-vars=...`. If a value contains commas, use the
`"^@^KEY=a,b,c"` delimiter form. Secrets live in Secret Manager. Never put them in env vars or in code.

## Rules

1. **Never print, log, commit or echo secrets.** `.env` is git-ignored. Never commit `y/` (the
   gcloud SDK) or `.firebase/`. If a secret shows up in output, say so and tell the user to rotate it.
2. **Production schema is managed by `ddl-auto=update`** until the playbook moves it to Flyway.
   Until then, make only additive changes. A new column must be nullable or have a DB default
   (`@ColumnDefault`), because existing rows have no value for it. Never rename or drop a column.
3. **Money:** amounts sent to Stripe are integer minor units (cents, `long`). Do price maths in
   `BigDecimal` with `RoundingMode.HALF_UP`. The server always computes amounts; the client only
   shows a server-provided quote. Stripe's minimum USD charge is 50 cents.
4. **Follow the existing patterns:**
   - DTOs are Java `record`s with Bean Validation.
   - Errors are domain exceptions handled by `GlobalExceptionHandler`.
   - Get the caller from `CurrentUserProvider`.
   - Lock rows in the webhook path (`findForUpdate...`).
   - Webhook handling must be idempotent.
   - Map to DTOs inside the transaction (`open-in-view` is off).
5. Any change to backend behaviour needs tests. `./mvnw test` must be green. On the frontend,
   `npm run lint && npm run build` must be green.
6. Make small commits, one logical step each, with messages in the imperative mood. Push at the end
   of each phase.
7. **Ask before you deploy.** Report what changed and what you verified, and wait for the user's
   go-ahead.
8. If the playbook and the code disagree, or a step is ambiguous in a way that matters (money,
   data loss, security), stop and ask. Don't guess.

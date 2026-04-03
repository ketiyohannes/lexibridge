# LexiBridge Operations Suite

Operations platform for content, moderation, bookings, leave workflows, payments, and admin controls.

## Quick Start (Docker only)

```bash
docker compose up --build
```

Open:

- App: `http://localhost:8081`
- Login: `http://localhost:8081/login`

Default local admin credentials:

- Username: `admin`
- Password: `AdminPass2026!`

## Architecture at a glance

- Spring Boot (`Java 21`) + Thymeleaf portal + MySQL 8
- Flyway-managed schema and policy controls
- Module domains: content, moderation, booking/attendance, leave, payments, admin

## Security controls implemented

- Location and actor scope checks across portal and API endpoints
- Device HMAC auth with admin key inventory/rotation/cutover workflows
- PII encryption at rest and masked list/review display patterns
- DB-level immutability triggers for audit and booking transition records
- Moderation enforcement: users with active suspension cannot create post/comment/Q&A targets
- Attendance scan enforcement: decoded booking ID is scope-checked before scan persistence

## Run Tests (Docker)

```bash
./run_test.sh
```

This script:

- starts `docker compose` in the background
- runs all Maven tests in a Dockerized Maven container
- stops the compose stack after tests

Keep the stack running after tests:

```bash
KEEP_STACK_UP=true ./run_test.sh
```

## Playwright E2E (Recorded)

```bash
npm install
npx playwright install
docker compose up --build -d
npx playwright test tests/e2e/major-flows.spec.ts
```

Artifacts:

- Video + traces: `test-results/`
- HTML report: `playwright-report/index.html`

## Local dev commands

Run all backend tests locally:

```bash
mvn test
```

Remove generated local artifacts:

```bash
rm -rf node_modules target test-results playwright-report
```

## Useful Commands

Stop services:

```bash
docker compose down
```

Full reset (containers + DB volume + local image):

```bash
docker compose down -v --remove-orphans --rmi local
```

## Troubleshooting

- If login says invalid credentials repeatedly, ensure you are using `AdminPass2026!` (without `#`).
- If account is temporarily locked from failed attempts, run a full reset command above or wait for lockout expiry.
- If UI changes do not appear, rebuild and hard refresh browser (`Cmd+Shift+R`).

## Docs

- Design: `docs/design.md`
- API overview: `docs/api-spec.md`

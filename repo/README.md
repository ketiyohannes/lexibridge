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

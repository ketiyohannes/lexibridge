# API Spec (High-Level)

Base path: `/api/v1`

## Modules

- Content: `/content/**`
- Moderation: `/moderation/**`
- Bookings: `/bookings/**`
- Leave: `/leave/**`
- Payments: `/payments/**`
- Admin: `/admin/**`

## Auth model

- Portal/API session authentication for role-protected routes
- Some API paths support HMAC client auth (device/integration flows)

## Common response format

- Most endpoints return JSON objects/arrays.
- Error responses use HTTP status + message body from Spring handlers.

## Typical status codes

- `200 OK`: successful read/action
- `201 Created`: new resource created
- `400 Bad Request`: validation or malformed input
- `401 Unauthorized`: unauthenticated
- `403 Forbidden`: authenticated but out of scope/role
- `404 Not Found`: resource missing

## Notes

- Detailed request/response schemas should be generated from controller DTOs or OpenAPI in a future revision.

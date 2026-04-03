# LexiBridge API Spec (High Level)

Base path: `/api/v1`

## Auth and scope model

- Session-authenticated admin/portal users for most API operations
- HMAC-authenticated device clients for selected operational callbacks
- Role + location scope checks enforced on protected endpoints

## Primary endpoint groups

- Admin: `/api/v1/admin/**`
- Content: `/api/v1/content/**`
- Moderation: `/api/v1/moderation/**`
- Booking: `/api/v1/bookings/**`
- Leave: `/api/v1/leave/**`
- Payments: `/api/v1/payments/**`

## Response and status conventions

- `200 OK`: successful read/action
- `201 Created`: resource created
- `400 Bad Request`: validation or payload error
- `401 Unauthorized`: authentication required
- `403 Forbidden`: authenticated but out of role/scope
- `404 Not Found`: missing resource
- `409 Conflict`: state transition or lifecycle conflict

## Security-sensitive behaviors

- PII fields are masked by default in list-oriented UI/API flows.
- Sensitive-value reveal flows are explicit and audit logged.
- Governance artifacts (audit records, booking transition history) are immutable at DB level.
- Moderation targets (posts/comments/Q&A) support media attachments with validated upload and controlled download.

## Device HMAC key lifecycle endpoints

All endpoints below are under `/api/v1/admin` and require admin authorization.

- `GET /device-clients/{clientKey}/hmac/keys`
  - Returns HMAC key inventory for the target device client.
  - Includes `keyVersion`, validity window, and active state.

- `POST /device-clients/{clientKey}/hmac/rotate`
  - Creates a new active key version and sets overlap window for old keys.
  - Request body:
    - `newKeyVersion` (integer, must be greater than currently active max)
    - `newSharedSecret` (string, required, cannot use insecure defaults)
    - `overlapDays` (integer, minimum `1`)
    - `reason` (string, minimum 8 chars; required for audit)
  - Emits audit event `DEVICE_HMAC_KEY_ROTATED`.

- `POST /device-clients/{clientKey}/hmac/cutover`
  - Deactivates old keys and keeps selected key version active.
  - Request body:
    - `activeKeyVersion` (integer, must exist for the client)
    - `reason` (string, minimum 8 chars; required for audit)
  - Emits audit event `DEVICE_HMAC_KEY_CUTOVER`.

## HMAC rotation behavior contract

- During overlap: requests signed with old and new key versions authenticate successfully.
- After cutover: only the selected active key version authenticates; old versions fail.
- Rotation and cutover actions are auditable and tied to the actor and reason.

## Moderation target media endpoints

Target-level media lifecycle for rich-text community artifacts:

- Moderator/admin target media:
  - `POST /api/v1/moderation/targets/{targetType}/{targetId}/media`
  - `GET /api/v1/moderation/targets/{targetType}/{targetId}/media`
  - `GET /api/v1/moderation/targets/{targetType}/{targetId}/media/{mediaId}/download`

- Community owner media (authenticated):
  - `POST /api/v1/moderation/community/posts/{postId}/media`
  - `POST /api/v1/moderation/community/comments/{commentId}/media`
  - `POST /api/v1/moderation/community/qna/{qnaId}/media`

- Owner retrieval mirrors upload routes with `GET .../media` and `GET .../media/{mediaId}/download`.

## Moderation posting suspension enforcement

- Community posting entry points:
  - `POST /api/v1/moderation/community/posts`
  - `POST /api/v1/moderation/community/comments`
  - `POST /api/v1/moderation/community/qna`
- If the actor has active suspension, posting is blocked with `403 Forbidden`.
- Blocked attempts are audit logged as `SUSPENDED_CONTENT_POST_BLOCKED`.

## Booking attendance scan scope enforcement

- Endpoint: `POST /api/v1/bookings/attendance/scan`
- Enforcement sequence:
  - actor identity must match authenticated principal
  - booking ID is decoded from QR token
  - booking scope (`location` / tenant access) is validated before attendance write
- Out-of-scope attendance attempts return `403 Forbidden`.

## Notes

- Detailed request/response contracts are represented by controller DTOs and module tests.
- For implementation details, inspect module controllers under `src/main/java/com/lexibridge/operations/modules/**/api`.

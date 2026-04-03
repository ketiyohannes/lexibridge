# LexiBridge Design Overview

## Runtime architecture

- Backend: Spring Boot (Java 21)
- Presentation: Thymeleaf server-rendered portal pages
- Persistence: MySQL 8 with Flyway migrations
- Local orchestration: Docker Compose (`app`, `mysql`, optional observability/security profiles)

## Domain modules

- Content lifecycle and media ingestion
- Moderation review and case handling
- Booking and attendance workflows
- Leave request and approval routing
- Payments and reconciliation
- Admin governance and webhook controls

## Security and governance controls

- Role-based portal/API access control with location scope checks
- Field encryption for sensitive values at rest
- PII masking in list views by default
- Audited reveal path for sensitive admin email inspection
- Operational HMAC key rotation lifecycle for device clients (inventory, rotate with overlap, cutover)
- Rich-text moderation targets (post/comment/Q&A) support first-class media attachments with upload/list/download lifecycle
- Suspension enforcement for community posting: active suspension blocks new post/comment/Q&A creation and records an audit event
- Attendance scan applies object-level booking scope checks after QR token decode before persistence
- Audit and booking-transition immutability enforced at DB level with triggers
- Retention purge scheduling with 7-year policy and legal-hold bypass protection

## Operational security workflows

- Device HMAC rotation is handled as a two-phase process:
  - rotate: introduce `N+1` key and maintain overlap for zero-downtime client rollout
  - cutover: deactivate old keys once all clients are switched
- Rotation and cutover actions write explicit audit events with actor identity and reason.
- Integration tests verify overlap acceptance and post-cutover rejection of old keys.
- Moderation suspension workflow:
  - confirmed moderation violations can trigger suspension windows
  - active suspension prevents new community target creation
  - blocked attempts are audit logged as `SUSPENDED_CONTENT_POST_BLOCKED`
- Attendance verification workflow:
  - QR token decode yields booking identifier
  - booking scope is validated before recording attendance

## Verification approach

- Unit tests for service behavior and policy enforcement
- Web MVC tests for authorization and controller behavior
- Integration tests with Testcontainers MySQL for workflow and DB governance guarantees

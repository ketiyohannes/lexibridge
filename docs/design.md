# Design Notes

## Architecture

- Backend: Spring Boot (Java)
- UI: Thymeleaf server-rendered templates
- Database: MySQL (Flyway migrations)
- Runtime: Docker Compose (`app` + `mysql`)

## Core domains

- Content management
- Moderation and case review
- Booking and attendance
- Leave request and approvals
- Payments and reconciliation
- Admin and security operations

## Security baseline

- Session auth for portal routes
- Role-based access control per module
- Account lockout after repeated failed logins
- CSRF protection for portal forms

## Local bootstrap behavior

- On fresh DB, bootstrap creates default admin user when enabled by environment.
- Compose defaults provide local bootstrap credentials for development.

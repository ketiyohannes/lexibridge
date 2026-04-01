# Question, My Understanding, and Solution

## 1) Single Portal Scope
**Question:** What does "single browser-based portal" mean in practice?  
**My Understanding:** Could be one app with role-based views, or separate mini-portals for each role.  
**Solution:** Build one unified web app (single domain) with strict RBAC and role-specific navigation/dashboard modules.

## 2) Tenant and Location Model
**Question:** Is this single-tenant or multi-location/multi-tenant?  
**My Understanding:** Prompt mentions per-location revenue splits, suggesting at least multi-location support.  
**Solution:** Use one tenant (business) with first-class `location` entities and data scoping by location.

## 3) API Access Actors
**Question:** Who can call REST APIs from local devices?  
**My Understanding:** Might be human users, scanners/terminals, or system integrations.  
**Solution:** Support both user tokens and device/service credentials, each scoped with minimal permissions.

## 4) Content Schema Flexibility
**Question:** Is the content schema fixed or customizable?  
**My Understanding:** Core fields are listed, but custom metadata is not specified.  
**Solution:** Keep a fixed required schema for core fields; allow optional extensible key-value metadata.

## 5) Deduplication Normalization Rules
**Question:** How exactly should dedup normalization work ("spelling + phonetic")?  
**My Understanding:** Duplicate detection depends on normalized text + normalized phonetic form.  
**Solution:** Normalize via lowercase, trim, collapse spaces, remove punctuation/diacritics; normalize phonetics similarly; compare exact normalized pair.

## 6) Import Error Handling
**Question:** What is import behavior on validation errors?  
**My Understanding:** Could be fail-fast whole file or partial row success.  
**Solution:** Use row-level validation with partial success; return detailed error report and downloadable reject file.

## 7) Inline Dedup Prompt UX
**Question:** How should inline dedup prompts work during import?  
**My Understanding:** Needs operator decision when possible duplicate appears.  
**Solution:** Provide per-row actions: `Skip`, `Create New`, `Merge Into Existing`, plus batch-apply option.

## 8) Moderation Content Source
**Question:** Are posts/comments/Q&A native entities or external feeds?  
**My Understanding:** Not explicit; moderation inbox implies internal or unified ingestion.  
**Solution:** Treat them as native internal entities in V1; design adapter layer for future external ingestion.

## 9) Penalty Catalog
**Question:** What penalties exist besides auto 30-day suspension?  
**My Understanding:** "Any penalties" implies multiple penalty types may be needed.  
**Solution:** Define penalty types: warning, temporary posting mute, 30-day suspension, permanent manual ban (admin-only).

## 10) "Why" Panel Contents
**Question:** What details should appear in the moderation "why" panel?  
**My Understanding:** Needs transparent reasoning for moderator actions.  
**Solution:** Show matched term/tag, policy rule ID, confidence/severity, snippet context, timestamp, and reviewer notes.

## 11) Slot vs Duration
**Question:** How are 15-minute booking slots mapped to service durations?  
**My Understanding:** Slot size is fixed, but booking length rules are not.  
**Solution:** Represent duration in slot units (e.g., 30 min = 2 slots) and lock all contiguous required slots.

## 12) Confirmed vs Unpaid State
**Question:** What counts as "unpaid/unconfirmed" in offline tender workflows?  
**My Understanding:** Confirmation may depend on terminal callback timing.  
**Solution:** Keep states separate: `reserved` -> `confirmed` only after valid tender/callback (or approved manual confirm reason).

## 13) QR Payload and Verification
**Question:** What should QR code payload/security look like?  
**My Understanding:** Must support reliable attendance verification.  
**Solution:** Encode signed token (order/member ID + expiry + HMAC signature); scanner validates signature offline-capable.

## 14) Booking Transition Strictness
**Question:** Are booking state transitions strict or flexible?  
**My Understanding:** Timeline implies canonical progression but overrides may be needed.  
**Solution:** Enforce state machine with allowed transitions; allow privileged override with mandatory reason and audit log.

## 15) Leave Form Configurability
**Question:** How configurable are leave request forms?  
**My Understanding:** "Configurable forms" implies admin-defined structure and rules.  
**Solution:** Add form builder with field types (text/date/select/file/number), required flags, validations, and versioned form definitions.

## 16) Approval Routing Conflicts
**Question:** What is routing precedence when approval rules conflict?  
**My Understanding:** Multiple dimensions (role/org/type/duration) can overlap.  
**Solution:** Precedence order: explicit user rule > org unit + leave type + duration > org unit > global default.

## 17) SLA Countdown Semantics
**Question:** How should SLA countdown be calculated?  
**My Understanding:** Calendar vs business-hours logic changes deadlines significantly.  
**Solution:** Use business-hours SLA with timezone + holiday calendar; pause when waiting for requester correction.

## 18) Version Retention Policy
**Question:** What does "retain up to 20 versions" do to old versions?  
**My Understanding:** Could hard-delete oldest or preserve key versions.  
**Solution:** Keep rolling 20 versions; protect currently published version from deletion; prune oldest non-protected first.

## 19) Immutable Audit vs Redaction
**Question:** How to reconcile immutable audits with correction/redaction needs?  
**My Understanding:** Compliance requires immutable history, but mistakes/PII issues happen.  
**Solution:** Never modify audit rows; append correction/redaction events with reason and actor; mask display where required.

## 20) File Safety Checks
**Question:** Is malware scanning required for uploaded files?  
**My Understanding:** Prompt requires strict file validation but doesn't explicitly mention AV.  
**Solution:** Add local antivirus scan (e.g., ClamAV) in upload pipeline in addition to MIME sniff + size/type checks.

## 21) Violation Window Logic
**Question:** How is "3 confirmed violations within 90 days" evaluated?  
**My Understanding:** Needs precise rolling-window logic and definition of "confirmed."  
**Solution:** Use rolling 90-day window from current violation timestamp; only moderator-confirmed violations count.

## 22) Callback Idempotency Key
**Question:** What key identifies idempotent payment callbacks?  
**My Understanding:** Terminal retries need stable uniqueness.  
**Solution:** Use terminal transaction ID + terminal ID as idempotency key; keep dedupe records for at least 30 days.

## 23) Revenue Split and Refunds
**Question:** How should revenue split config interact with refunds?  
**My Understanding:** Split defaults per location, but refund impact not defined.  
**Solution:** Apply split at original capture time; refunds reverse split proportionally using original split snapshot.

## 24) Refund Approval Threshold Interpretation
**Question:** How is "supervisor approval above $200" interpreted?  
**My Understanding:** Ambiguous across currency/tax/cumulative partials.  
**Solution:** Use location currency, pre-tax refund amount per refund event; require supervisor when refund event > 200.00.

## 25) Exception Queue Lifecycle
**Question:** How are reconciliation exceptions resolved operationally?  
**My Understanding:** Queue exists but closure workflow is unspecified.  
**Solution:** Add statuses (`open`, `in_review`, `resolved`, `reopened`) with reason codes and mandatory resolution notes.

## 26) HMAC Secret Rotation Process
**Question:** How should HMAC secret rotation work?  
**My Understanding:** Needs safe rollover without downtime.  
**Solution:** Per-client key pairs with active/next secrets; 24-hour overlap window; key version in request headers.

## 27) Nonce Replay Scope
**Question:** How should nonce replay protection be scoped?  
**My Understanding:** Nonce uniqueness depends on scope and clock drift handling.  
**Solution:** Nonce unique per client key for 5 minutes; allow +/-60s clock skew; reject reused nonce within validity window.

## 28) Rate Limit Scope
**Question:** Is rate limit 60 RPM global per user or endpoint-specific?  
**My Understanding:** Could significantly change throttle behavior.  
**Solution:** Enforce global per-user 60 RPM plus optional tighter endpoint-specific caps for sensitive endpoints.

## 29) Webhook IP Whitelist Enforcement
**Question:** How to enforce private-IP webhook whitelist robustly?  
**My Understanding:** DNS can resolve to changing IPs; validation time matters.  
**Solution:** Validate destination IP at registration and on each delivery; block non-RFC1918/private ranges.

## 30) PII/Sensitive Data Taxonomy
**Question:** What fields are considered PII/sensitive for masking/encryption?  
**My Understanding:** Policy exists but field-level taxonomy is missing.  
**Solution:** Define data classification catalog; apply deterministic masking rules in UI and AES encryption at rest for sensitive fields.

## 31) Local Observability Stack
**Question:** Which local metrics/tracing stack should be used?  
**My Understanding:** Storage must be local-first with alerting.  
**Solution:** Use Prometheus + Alertmanager + Grafana locally; OpenTelemetry for traces stored locally (e.g., Tempo/Jaeger).

## 32) 7-Year Retention Operations
**Question:** How should 7-year retention be implemented operationally?  
**My Understanding:** Need archival, retrieval, and purge mechanics.  
**Solution:** Partition records by month, archive to local immutable storage, enforce auto-purge after 7 years + legal-hold override.

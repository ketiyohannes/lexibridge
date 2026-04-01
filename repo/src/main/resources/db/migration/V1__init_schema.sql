CREATE TABLE location (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE org_unit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(128) NOT NULL,
    code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org_unit_location_code (location_id, code),
    CONSTRAINT fk_org_unit_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_org_unit_parent FOREIGN KEY (parent_id) REFERENCES org_unit (id)
);

CREATE TABLE app_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES app_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NULL,
    org_unit_id BIGINT NULL,
    username VARCHAR(128) NOT NULL UNIQUE,
    full_name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INT NOT NULL DEFAULT 0,
    lockout_until TIMESTAMP NULL,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_app_user_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_app_user_org_unit FOREIGN KEY (org_unit_id) REFERENCES org_unit (id)
);

CREATE TABLE app_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES app_role (id)
);

CREATE TABLE device_client (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    client_key VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    allowed_private_cidr VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_device_client_location FOREIGN KEY (location_id) REFERENCES location (id)
);

CREATE TABLE hmac_secret (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_type VARCHAR(32) NOT NULL,
    client_ref_id BIGINT NOT NULL,
    key_version INT NOT NULL,
    shared_secret VARBINARY(512) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hmac_secret_client_version (client_type, client_ref_id, key_version)
);

CREATE TABLE nonce_replay_guard (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_key VARCHAR(128) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_nonce_guard_client_nonce (client_key, nonce),
    KEY idx_nonce_guard_expires_at (expires_at)
);

CREATE TABLE content_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    term VARCHAR(255) NOT NULL,
    normalized_term VARCHAR(255) NOT NULL,
    phonetic VARCHAR(255) NULL,
    normalized_phonetic VARCHAR(255) NULL,
    category VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_version_no INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_item_dedup (location_id, normalized_term, normalized_phonetic),
    CONSTRAINT fk_content_item_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_content_item_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE content_item_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_item_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    grammar_point TEXT NULL,
    phrase_text TEXT NULL,
    example_sentence TEXT NULL,
    definition_text TEXT NULL,
    metadata_json JSON NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_version (content_item_id, version_no),
    CONSTRAINT fk_content_version_item FOREIGN KEY (content_item_id) REFERENCES content_item (id),
    CONSTRAINT fk_content_version_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE content_media (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_item_id BIGINT NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_media_checksum (checksum_sha256),
    CONSTRAINT fk_content_media_item FOREIGN KEY (content_item_id) REFERENCES content_item (id)
);

CREATE TABLE content_import_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    source_format VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_job_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_import_job_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES app_user (id)
);

CREATE TABLE content_import_row_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    action_taken VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    duplicate_content_item_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_row_result_job_row (job_id, row_no),
    CONSTRAINT fk_import_row_result_job FOREIGN KEY (job_id) REFERENCES content_import_job (id),
    CONSTRAINT fk_import_row_result_duplicate FOREIGN KEY (duplicate_content_item_id) REFERENCES content_item (id)
);

CREATE TABLE policy_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_code VARCHAR(64) NOT NULL UNIQUE,
    description_text VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sensitive_dictionary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term VARCHAR(255) NOT NULL,
    normalized_term VARCHAR(255) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    rule_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sensitive_dictionary_term_tag (normalized_term, tag),
    CONSTRAINT fk_sensitive_dictionary_rule FOREIGN KEY (rule_id) REFERENCES policy_rule (id)
);

CREATE TABLE community_post (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    content_type VARCHAR(16) NOT NULL,
    title VARCHAR(255) NULL,
    body_html MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_community_post_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_community_post_author FOREIGN KEY (author_user_id) REFERENCES app_user (id)
);

CREATE TABLE moderation_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    sensitive_hits_json JSON NULL,
    reviewer_user_id BIGINT NULL,
    reviewer_note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    KEY idx_moderation_case_status_created (status, created_at),
    CONSTRAINT fk_moderation_case_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES app_user (id)
);

CREATE TABLE user_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reporter_user_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT fk_user_report_reporter FOREIGN KEY (reporter_user_id) REFERENCES app_user (id)
);

CREATE TABLE user_penalty (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    penalty_type VARCHAR(32) NOT NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NULL,
    reason_text VARCHAR(500) NOT NULL,
    appeal_note TEXT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_penalty_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_user_penalty_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE booking_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    customer_name VARCHAR(128) NOT NULL,
    customer_phone VARCHAR(64) NULL,
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NOT NULL,
    slot_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    order_note TEXT NULL,
    override_reason VARCHAR(500) NULL,
    expires_at TIMESTAMP NULL,
    no_show_close_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_booking_location_start (location_id, start_at),
    KEY idx_booking_status (status),
    CONSTRAINT fk_booking_order_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_booking_order_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE booking_slot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    slot_start_at TIMESTAMP NOT NULL,
    slot_end_at TIMESTAMP NOT NULL,
    booking_order_id BIGINT NULL,
    occupancy_state VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_booking_slot_location_start (location_id, slot_start_at),
    KEY idx_booking_slot_booking_order (booking_order_id),
    CONSTRAINT fk_booking_slot_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_booking_slot_order FOREIGN KEY (booking_order_id) REFERENCES booking_order (id)
);

CREATE TABLE booking_state_transition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_order_id BIGINT NOT NULL,
    from_state VARCHAR(32) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NULL,
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_state_transition_order FOREIGN KEY (booking_order_id) REFERENCES booking_order (id),
    CONSTRAINT fk_booking_state_transition_user FOREIGN KEY (changed_by) REFERENCES app_user (id)
);

CREATE TABLE booking_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_order_id BIGINT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_attachment_order FOREIGN KEY (booking_order_id) REFERENCES booking_order (id),
    CONSTRAINT fk_booking_attachment_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE attendance_scan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_order_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scanned_by BIGINT NOT NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_valid BOOLEAN NOT NULL,
    UNIQUE KEY uk_attendance_scan_token_hash (token_hash),
    CONSTRAINT fk_attendance_scan_order FOREIGN KEY (booking_order_id) REFERENCES booking_order (id),
    CONSTRAINT fk_attendance_scan_scanned_by FOREIGN KEY (scanned_by) REFERENCES app_user (id)
);

CREATE TABLE leave_form_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_leave_form_definition_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_leave_form_definition_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE leave_form_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    form_definition_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    schema_json JSON NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_leave_form_version (form_definition_id, version_no),
    CONSTRAINT fk_leave_form_version_definition FOREIGN KEY (form_definition_id) REFERENCES leave_form_definition (id),
    CONSTRAINT fk_leave_form_version_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE leave_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    leave_type VARCHAR(64) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    duration_minutes INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step VARCHAR(64) NULL,
    sla_deadline_at TIMESTAMP NULL,
    sla_paused BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawn_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_leave_request_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_leave_request_requester FOREIGN KEY (requester_user_id) REFERENCES app_user (id)
);

CREATE TABLE approval_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    priority INT NOT NULL,
    leave_type VARCHAR(64) NULL,
    org_unit_id BIGINT NULL,
    min_duration_minutes INT NULL,
    max_duration_minutes INT NULL,
    approver_role_code VARCHAR(64) NOT NULL,
    approver_user_id BIGINT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_approval_rule_lookup (location_id, priority),
    CONSTRAINT fk_approval_rule_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_approval_rule_org_unit FOREIGN KEY (org_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_approval_rule_approver_user FOREIGN KEY (approver_user_id) REFERENCES app_user (id)
);

CREATE TABLE approval_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    leave_request_id BIGINT NOT NULL,
    approver_user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    due_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP NULL,
    decision_note VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_approval_task_approver_status (approver_user_id, status),
    CONSTRAINT fk_approval_task_leave_request FOREIGN KEY (leave_request_id) REFERENCES leave_request (id),
    CONSTRAINT fk_approval_task_approver FOREIGN KEY (approver_user_id) REFERENCES app_user (id)
);

CREATE TABLE tender_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    booking_order_id BIGINT NOT NULL,
    tender_type VARCHAR(32) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    terminal_id VARCHAR(64) NULL,
    terminal_txn_id VARCHAR(128) NULL,
    callback_received_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tender_entry_status (status),
    CONSTRAINT fk_tender_entry_booking_order FOREIGN KEY (booking_order_id) REFERENCES booking_order (id),
    CONSTRAINT fk_tender_entry_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE payment_split_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tender_entry_id BIGINT NOT NULL UNIQUE,
    merchant_ratio DECIMAL(5, 2) NOT NULL,
    platform_ratio DECIMAL(5, 2) NOT NULL,
    merchant_amount DECIMAL(12, 2) NOT NULL,
    platform_amount DECIMAL(12, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_split_tender FOREIGN KEY (tender_entry_id) REFERENCES tender_entry (id)
);

CREATE TABLE terminal_callback_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    terminal_id VARCHAR(64) NOT NULL,
    terminal_txn_id VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    processed_status VARCHAR(32) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_terminal_callback (terminal_id, terminal_txn_id)
);

CREATE TABLE refund_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tender_entry_id BIGINT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requires_supervisor BOOLEAN NOT NULL,
    approved_by BIGINT NULL,
    approved_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refund_request_tender FOREIGN KEY (tender_entry_id) REFERENCES tender_entry (id),
    CONSTRAINT fk_refund_request_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id),
    CONSTRAINT fk_refund_request_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE reconciliation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary_json JSON NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    UNIQUE KEY uk_recon_location_date (location_id, business_date),
    CONSTRAINT fk_reconciliation_run_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_reconciliation_run_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE reconciliation_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    exception_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    details_json JSON NOT NULL,
    resolution_note VARCHAR(500) NULL,
    resolved_by BIGINT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recon_exception_status (status),
    CONSTRAINT fk_recon_exception_run FOREIGN KEY (run_id) REFERENCES reconciliation_run (id),
    CONSTRAINT fk_recon_exception_resolved_by FOREIGN KEY (resolved_by) REFERENCES app_user (id)
);

CREATE TABLE webhook_endpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    callback_url VARCHAR(512) NOT NULL,
    whitelisted_ip VARCHAR(64) NOT NULL,
    signing_secret VARBINARY(512) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_webhook_endpoint_location FOREIGN KEY (location_id) REFERENCES location (id)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    actor_type VARCHAR(32) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    entity_type VARCHAR(128) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    location_id BIGINT NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_created_at (created_at),
    CONSTRAINT fk_audit_log_actor_user FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_audit_log_location FOREIGN KEY (location_id) REFERENCES location (id)
);

CREATE TABLE audit_redaction_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_log_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    redacted_fields_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_redaction_event_audit FOREIGN KEY (audit_log_id) REFERENCES audit_log (id),
    CONSTRAINT fk_redaction_event_requested_by FOREIGN KEY (requested_by) REFERENCES app_user (id)
);

CREATE TABLE retention_hold (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hold_ref VARCHAR(64) NOT NULL UNIQUE,
    entity_type VARCHAR(128) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_retention_hold_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE alert_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_code VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    details_json JSON NOT NULL,
    started_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_alert_event_status (status)
);

CREATE TABLE metric_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    metric_name VARCHAR(128) NOT NULL,
    metric_value DOUBLE NOT NULL,
    labels_json JSON NULL,
    observed_at TIMESTAMP NOT NULL,
    KEY idx_metric_event_name_time (metric_name, observed_at)
);

INSERT INTO location (code, name, timezone) VALUES ('MAIN', 'Main Learning Center', 'UTC');

INSERT INTO app_role (code, display_name) VALUES
('ADMIN', 'Administrator'),
('CONTENT_EDITOR', 'Content Editor'),
('MODERATOR', 'Moderator'),
('FRONT_DESK', 'Front Desk Operator'),
('EMPLOYEE', 'Employee'),
('MANAGER', 'Manager'),
('HR_APPROVER', 'HR Approver'),
('SUPERVISOR', 'Supervisor'),
('DEVICE_SERVICE', 'Device Service');

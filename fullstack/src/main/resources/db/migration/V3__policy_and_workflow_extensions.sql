create table if not exists payment_split_policy (
    id bigint primary key auto_increment,
    location_id bigint not null,
    merchant_ratio decimal(5,2) not null,
    platform_ratio decimal(5,2) not null,
    is_active boolean not null default true,
    created_by bigint not null,
    created_at timestamp not null default current_timestamp,
    unique key uk_payment_split_policy_location (location_id),
    constraint fk_payment_split_policy_location foreign key (location_id) references location(id),
    constraint fk_payment_split_policy_created_by foreign key (created_by) references app_user(id)
);

alter table leave_request
    add column form_version_id bigint null after duration_minutes,
    add column form_payload_json json null after form_version_id,
    add constraint fk_leave_request_form_version foreign key (form_version_id) references leave_form_version(id);

create table if not exists leave_request_attachment (
    id bigint primary key auto_increment,
    leave_request_id bigint not null,
    storage_path varchar(512) not null,
    mime_type varchar(128) not null,
    file_size_bytes bigint not null,
    checksum_sha256 char(64) not null,
    created_by bigint not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_leave_attachment_request foreign key (leave_request_id) references leave_request(id),
    constraint fk_leave_attachment_created_by foreign key (created_by) references app_user(id)
);

alter table user_report
    add column moderator_user_id bigint null after disposition,
    add column resolution_note varchar(500) null after moderator_user_id,
    add column penalty_summary varchar(255) null after resolution_note,
    add constraint fk_user_report_moderator foreign key (moderator_user_id) references app_user(id);

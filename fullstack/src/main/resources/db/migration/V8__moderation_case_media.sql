create table moderation_case_media (
    id bigint primary key auto_increment,
    case_id bigint not null,
    storage_path varchar(512) not null,
    mime_type varchar(128) not null,
    file_size_bytes bigint not null,
    checksum_sha256 char(64) not null,
    created_by bigint not null,
    created_at timestamp not null default current_timestamp,
    key idx_moderation_case_media_case (case_id, created_at),
    constraint fk_moderation_case_media_case foreign key (case_id) references moderation_case(id),
    constraint fk_moderation_case_media_created_by foreign key (created_by) references app_user(id)
);

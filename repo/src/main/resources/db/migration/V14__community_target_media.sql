create table community_target_media (
    id bigint primary key auto_increment,
    target_type varchar(16) not null,
    target_id bigint not null,
    storage_path varchar(512) not null,
    mime_type varchar(128) not null,
    file_size_bytes bigint not null,
    checksum_sha256 char(64) not null,
    created_by bigint not null,
    created_at timestamp not null default current_timestamp,
    key idx_community_target_media_target (target_type, target_id, created_at),
    key idx_community_target_media_checksum (checksum_sha256),
    constraint fk_community_target_media_created_by foreign key (created_by) references app_user(id)
);

create table binary_storage (
    storage_path varchar(512) primary key,
    checksum_sha256 char(64) not null,
    mime_type varchar(128) not null,
    file_size_bytes bigint not null,
    payload longblob not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp
);

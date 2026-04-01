alter table booking_order
    add column no_show_auto_close_disabled boolean not null default false after no_show_close_at,
    add column no_show_override_reason varchar(500) null after no_show_auto_close_disabled,
    add column no_show_overridden_by bigint null after no_show_override_reason,
    add column no_show_overridden_at timestamp null after no_show_overridden_by,
    add constraint fk_booking_no_show_overridden_by foreign key (no_show_overridden_by) references app_user(id);

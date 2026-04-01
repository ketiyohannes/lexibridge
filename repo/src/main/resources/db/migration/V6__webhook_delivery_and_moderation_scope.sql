create table webhook_delivery_attempt (
    id bigint primary key auto_increment,
    webhook_id bigint not null,
    event_type varchar(128) not null,
    attempt_no int not null,
    status varchar(32) not null,
    http_status int null,
    response_body text null,
    duration_ms bigint not null,
    created_at timestamp not null default current_timestamp,
    key idx_webhook_delivery_webhook_created (webhook_id, created_at),
    constraint fk_webhook_delivery_webhook foreign key (webhook_id) references webhook_endpoint(id)
);

alter table moderation_case
    add column location_id bigint null;

update moderation_case mc
join community_post cp
  on mc.target_type = 'POST' and cp.id = mc.target_id
set mc.location_id = cp.location_id
where mc.location_id is null;

update moderation_case mc
left join app_user au on au.id = mc.reviewer_user_id
set mc.location_id = coalesce(mc.location_id, au.location_id, 1)
where mc.location_id is null;

alter table moderation_case
    modify column location_id bigint not null,
    add key idx_moderation_case_location_status (location_id, status, created_at),
    add constraint fk_moderation_case_location foreign key (location_id) references location(id);

alter table user_report
    add column location_id bigint null;

update user_report ur
join community_post cp
  on ur.target_type = 'POST' and cp.id = ur.target_id
set ur.location_id = cp.location_id
where ur.location_id is null;

update user_report ur
left join app_user au on au.id = ur.reporter_user_id
set ur.location_id = coalesce(ur.location_id, au.location_id, 1)
where ur.location_id is null;

alter table user_report
    modify column location_id bigint not null,
    add key idx_user_report_location_created (location_id, created_at),
    add constraint fk_user_report_location foreign key (location_id) references location(id);

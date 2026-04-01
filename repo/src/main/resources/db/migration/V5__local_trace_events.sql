create table if not exists trace_event (
    id bigint primary key auto_increment,
    trace_id char(32) not null,
    span_id char(16) not null,
    method varchar(16) not null,
    path varchar(255) not null,
    status_code int not null,
    duration_ms bigint not null,
    source varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    key idx_trace_event_created_at (created_at),
    key idx_trace_event_trace_id (trace_id)
);

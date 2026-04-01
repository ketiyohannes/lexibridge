create table community_comment (
    id bigint primary key auto_increment,
    location_id bigint not null,
    post_id bigint not null,
    author_user_id bigint not null,
    body_text text not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    key idx_community_comment_location_created (location_id, created_at),
    constraint fk_community_comment_location foreign key (location_id) references location(id),
    constraint fk_community_comment_post foreign key (post_id) references community_post(id),
    constraint fk_community_comment_author foreign key (author_user_id) references app_user(id)
);

create table community_qna (
    id bigint primary key auto_increment,
    location_id bigint not null,
    author_user_id bigint not null,
    question_text text not null,
    answer_text text null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp on update current_timestamp,
    key idx_community_qna_location_created (location_id, created_at),
    constraint fk_community_qna_location foreign key (location_id) references location(id),
    constraint fk_community_qna_author foreign key (author_user_id) references app_user(id)
);

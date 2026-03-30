alter table webhook_endpoint
    add column whitelisted_cidr varchar(64) null after whitelisted_ip;

update webhook_endpoint
set whitelisted_cidr = concat(whitelisted_ip, '/32')
where whitelisted_cidr is null;

alter table webhook_endpoint
    modify column whitelisted_cidr varchar(64) not null;

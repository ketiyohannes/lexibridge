alter table booking_order
    add column customer_name_enc varchar(512) null after customer_name,
    add column customer_phone_enc varchar(512) null after customer_phone;

alter table app_user
    modify column email varchar(768) null;

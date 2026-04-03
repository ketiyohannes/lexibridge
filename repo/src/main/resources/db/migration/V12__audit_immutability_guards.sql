drop trigger if exists trg_audit_log_block_update;
drop trigger if exists trg_audit_log_block_delete;
drop trigger if exists trg_audit_redaction_event_block_update;
drop trigger if exists trg_audit_redaction_event_block_delete;

create trigger trg_audit_log_block_update
before update on audit_log
for each row
signal sqlstate '45000'
set message_text = 'audit_log is immutable';

create trigger trg_audit_log_block_delete
before delete on audit_log
for each row
begin
    if OLD.created_at >= date_sub(current_timestamp, interval 7 year) then
        signal sqlstate '45000' set message_text = 'audit_log delete is blocked before 7-year retention period';
    end if;

    if exists (
        select 1
        from retention_hold rh
        where rh.entity_type = 'audit_log'
          and rh.entity_id = cast(OLD.id as char)
          and rh.is_active = true
    ) then
        signal sqlstate '45000' set message_text = 'audit_log delete is blocked by active retention hold';
    end if;
end;

create trigger trg_audit_redaction_event_block_update
before update on audit_redaction_event
for each row
signal sqlstate '45000'
set message_text = 'audit_redaction_event is immutable';

create trigger trg_audit_redaction_event_block_delete
before delete on audit_redaction_event
for each row
begin
    if OLD.created_at >= date_sub(current_timestamp, interval 7 year) then
        signal sqlstate '45000' set message_text = 'audit_redaction_event delete is blocked before 7-year retention period';
    end if;

    if exists (
        select 1
        from retention_hold rh
        where rh.entity_type = 'audit_redaction_event'
          and rh.entity_id = cast(OLD.id as char)
          and rh.is_active = true
    ) then
        signal sqlstate '45000' set message_text = 'audit_redaction_event delete is blocked by active retention hold';
    end if;
end;

drop trigger if exists trg_booking_state_transition_block_update;
drop trigger if exists trg_booking_state_transition_block_delete;

create trigger trg_booking_state_transition_block_update
before update on booking_state_transition
for each row
signal sqlstate '45000'
set message_text = 'booking_state_transition is immutable';

create trigger trg_booking_state_transition_block_delete
before delete on booking_state_transition
for each row
begin
    if OLD.changed_at >= date_sub(current_timestamp, interval 7 year) then
        signal sqlstate '45000' set message_text = 'booking_state_transition delete is blocked before 7-year retention period';
    end if;

    if exists (
        select 1
        from retention_hold rh
        where rh.entity_type = 'booking_state_transition'
          and rh.entity_id = cast(OLD.id as char)
          and rh.is_active = true
    ) then
        signal sqlstate '45000' set message_text = 'booking_state_transition delete is blocked by active retention hold';
    end if;
end;

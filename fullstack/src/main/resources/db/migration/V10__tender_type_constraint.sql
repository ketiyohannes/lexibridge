update tender_entry
set tender_type = 'CARD_PRESENT'
where upper(tender_type) = 'CARD';

alter table tender_entry
    modify column tender_type enum('CASH', 'CHECK', 'CARD_PRESENT') not null;

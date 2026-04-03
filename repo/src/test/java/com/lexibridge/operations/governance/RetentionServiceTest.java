package com.lexibridge.operations.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void purgeMethods_shouldReturnDeletedCounts() {
        when(jdbcTemplate.update(anyString())).thenReturn(4).thenReturn(3).thenReturn(2).thenReturn(1);
        RetentionService service = new RetentionService(jdbcTemplate);
        assertEquals(4, service.purgeExpiredAuditRedactionEvents());
        assertEquals(3, service.purgeExpiredAuditLogs());
        assertEquals(2, service.purgeExpiredReconciliationExceptions());
        assertEquals(1, service.purgeExpiredReconciliationRuns());
    }
}

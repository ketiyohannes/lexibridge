package com.lexibridge.operations.modules.booking.service;

import com.lexibridge.operations.security.privacy.DataClassificationService;
import com.lexibridge.operations.security.privacy.FieldEncryptionService;
import com.lexibridge.operations.security.privacy.PiiMaskingService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BookingCustomerDataService {

    private final DataClassificationService dataClassificationService;
    private final FieldEncryptionService fieldEncryptionService;
    private final PiiMaskingService piiMaskingService;

    public BookingCustomerDataService(DataClassificationService dataClassificationService,
                                      FieldEncryptionService fieldEncryptionService,
                                      PiiMaskingService piiMaskingService) {
        this.dataClassificationService = dataClassificationService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.piiMaskingService = piiMaskingService;
    }

    public PreparedCustomer prepareForStorage(String customerName, String customerPhone) {
        String normalizedName = dataClassificationService.sanitizePiiName(customerName);
        String normalizedPhone = dataClassificationService.sanitizePiiPhone(customerPhone);
        dataClassificationService.validatePiiEnvelope(normalizedName, normalizedPhone);
        return new PreparedCustomer(
            normalizedName,
            normalizedPhone,
            fieldEncryptionService.encryptString(normalizedName),
            fieldEncryptionService.encryptString(normalizedPhone)
        );
    }

    public String maskedNameForDisplay(Map<String, Object> bookingRow) {
        return piiMaskingService.maskName(resolveCustomerName(bookingRow));
    }

    public String maskedPhoneForDisplay(Map<String, Object> bookingRow) {
        return piiMaskingService.maskPhone(resolveCustomerPhone(bookingRow));
    }

    private String resolveCustomerName(Map<String, Object> row) {
        String encrypted = (String) row.get("customer_name_enc");
        if (encrypted != null && !encrypted.isBlank()) {
            return fieldEncryptionService.decryptString(encrypted);
        }
        return (String) row.get("customer_name");
    }

    private String resolveCustomerPhone(Map<String, Object> row) {
        String encrypted = (String) row.get("customer_phone_enc");
        if (encrypted != null && !encrypted.isBlank()) {
            return fieldEncryptionService.decryptString(encrypted);
        }
        return (String) row.get("customer_phone");
    }

    public record PreparedCustomer(
        String normalizedName,
        String normalizedPhone,
        String encryptedName,
        String encryptedPhone
    ) {
    }
}

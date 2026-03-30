package com.lexibridge.operations.security.privacy;

import org.springframework.stereotype.Service;

@Service
public class DataClassificationService {

    public String sanitizePiiName(String name) {
        if (name == null) {
            return null;
        }
        return name.trim().replaceAll("\\s+", " ");
    }

    public String sanitizePiiPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.replaceAll("[^0-9+]", "");
    }

    public void validatePiiEnvelope(String name, String phone) {
        if (name != null && name.length() > 128) {
            throw new IllegalArgumentException("Customer name exceeds 128 characters before encryption.");
        }
        if (phone != null && phone.length() > 64) {
            throw new IllegalArgumentException("Customer phone exceeds 64 characters before encryption.");
        }
    }
}

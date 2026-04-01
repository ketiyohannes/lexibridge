package com.lexibridge.operations.security.privacy;

import org.springframework.stereotype.Service;

@Service
public class PiiMaskingService {

    public String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "****";
        }
        String suffix = digits.substring(digits.length() - 4);
        return "***-***-" + suffix;
    }

    public String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        if (fullName.length() <= 2) {
            return fullName.charAt(0) + "*";
        }
        return fullName.charAt(0) + "***" + fullName.charAt(fullName.length() - 1);
    }

    public String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return email.charAt(0) + "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        return maskToken(local) + "@" + maskDomain(domain);
    }

    private String maskToken(String token) {
        if (token.length() <= 2) {
            return token.charAt(0) + "*";
        }
        return token.charAt(0) + "***" + token.charAt(token.length() - 1);
    }

    private String maskDomain(String domain) {
        int dot = domain.lastIndexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return maskToken(domain);
        }
        return maskToken(domain.substring(0, dot)) + domain.substring(dot);
    }
}

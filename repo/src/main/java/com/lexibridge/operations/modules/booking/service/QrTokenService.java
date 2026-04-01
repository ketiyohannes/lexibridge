package com.lexibridge.operations.modules.booking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

@Component
public class QrTokenService {

    private final byte[] secret;

    public QrTokenService(@Value("${lexibridge.booking.qr-secret:qr-demo-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String createToken(long bookingOrderId, LocalDateTime expiresAt) {
        long expiryEpochSeconds = expiresAt.toEpochSecond(ZoneOffset.UTC);
        String payload = bookingOrderId + ":" + expiryEpochSeconds;
        String signature = hmacHex(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public long validateAndExtractBookingId(String token) {
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] parts = decoded.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid token format.");
        }
        long bookingId = Long.parseLong(parts[0]);
        long expiry = Long.parseLong(parts[1]);
        String signature = parts[2];

        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), hmacHex(parts[0] + ":" + parts[1]).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid token signature.");
        }
        if (LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) > expiry) {
            throw new IllegalArgumentException("Token expired.");
        }
        return bookingId;
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash token", ex);
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : signature) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate token signature", ex);
        }
    }
}

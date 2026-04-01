package com.lexibridge.operations.modules.content.service;

import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import com.lexibridge.operations.security.antivirus.MalwareScanResult;
import com.lexibridge.operations.security.antivirus.MalwareScannerService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class MediaValidationService {

    private static final Map<String, Rule> RULES = Map.of(
        "jpg", new Rule("image/jpeg", 20L * 1024 * 1024),
        "jpeg", new Rule("image/jpeg", 20L * 1024 * 1024),
        "png", new Rule("image/png", 20L * 1024 * 1024),
        "mp3", new Rule("audio/mpeg", 50L * 1024 * 1024),
        "mp4", new Rule("video/mp4", 200L * 1024 * 1024)
    );

    private final ContentRepository contentRepository;
    private final MalwareScannerService malwareScannerService;

    public MediaValidationService(ContentRepository contentRepository,
                                  MalwareScannerService malwareScannerService) {
        this.contentRepository = contentRepository;
        this.malwareScannerService = malwareScannerService;
    }

    public FileValidationResult validate(String originalFilename, byte[] content) {
        String extension = extension(originalFilename);
        Rule rule = RULES.get(extension);
        if (rule == null) {
            return new FileValidationResult(false, null, null, "Unsupported file extension", null, null);
        }

        if (content.length > rule.maxBytes) {
            return new FileValidationResult(false, null, null, "File exceeds max size for extension", null, null);
        }

        String detected = detectMime(content);
        if (detected == null || !mimeMatches(detected, rule.mime)) {
            return new FileValidationResult(false, detected, null, "MIME sniffing mismatch", null, null);
        }

        String checksum = checksum(content);
        if (contentRepository.checksumExists(checksum)) {
            return new FileValidationResult(false, detected, checksum, "Duplicate file checksum", null, null);
        }

        MalwareScanResult scanResult = malwareScannerService.scan(originalFilename, content);
        if (!scanResult.clean()) {
            return new FileValidationResult(false, detected, checksum, "Antivirus scan failed", scanResult.engine(), scanResult.message());
        }

        return new FileValidationResult(true, detected, checksum, null, scanResult.engine(), scanResult.message());
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String detectMime(byte[] content) {
        try {
            return URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(content));
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean mimeMatches(String detected, String expected) {
        if (detected.equals(expected)) {
            return true;
        }
        return expected.equals("image/jpeg") && detected.equals("image/pjpeg");
    }

    private String checksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute checksum", ex);
        }
    }

    private record Rule(String mime, long maxBytes) {
    }
}

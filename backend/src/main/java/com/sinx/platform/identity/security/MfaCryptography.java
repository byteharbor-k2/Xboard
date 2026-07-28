package com.sinx.platform.identity.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class MfaCryptography {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] SECRET_CONTEXT =
        "sinx-admin-totp-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_CONTEXT =
        "sinx-admin-recovery-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaCryptography(IdentitySecurityProperties properties) {
        try {
            encryptionKey = Base64.getDecoder().decode(
                properties.mfaEncryptionKey()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "MFA encryption key must be valid Base64",
                exception
            );
        }
        if (encryptionKey.length != 32) {
            throw new IllegalStateException(
                "MFA encryption key must decode to exactly 32 bytes"
            );
        }
    }

    public String encryptSecret(String secret) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(SECRET_CONTEXT);
            byte[] encrypted = cipher.doFinal(
                secret.getBytes(StandardCharsets.UTF_8)
            );
            return "v1."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                + "."
                + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Unable to encrypt MFA secret",
                exception
            );
        }
    }

    public String decryptSecret(String encryptedSecret) {
        String[] parts = encryptedSecret.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalStateException("Unsupported MFA secret format");
        }
        try {
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(SECRET_CONTEXT);
            return new String(
                cipher.doFinal(encrypted),
                StandardCharsets.UTF_8
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unable to decrypt MFA secret",
                exception
            );
        }
    }

    public String hashRecoveryCode(String rawCode) {
        String normalized = normalizeRecoveryCode(rawCode);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(encryptionKey, "HmacSHA256"));
            mac.update(RECOVERY_CONTEXT);
            mac.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(normalized.length())
                .array());
            return HexFormat.of().formatHex(
                mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Unable to hash MFA recovery code",
                exception
            );
        }
    }

    public String normalizeRecoveryCode(String rawCode) {
        return rawCode.replace("-", "").replace(" ", "")
            .toUpperCase(java.util.Locale.ROOT);
    }
}

package com.sinx.platform.identity.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private final IdentitySecurityProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpService(
        IdentitySecurityProperties properties,
        Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    public String newSecret() {
        byte[] secret = new byte[20];
        secureRandom.nextBytes(secret);
        return encodeBase32(secret);
    }

    public OptionalLong matchingTimeStep(String secret, String rawCode) {
        if (rawCode == null || !rawCode.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long currentStep = Instant.now(clock).getEpochSecond()
            / TIME_STEP_SECONDS;
        for (long candidate = currentStep - 1; candidate <= currentStep + 1;
                candidate++) {
            String expected = generateCode(secret, candidate);
            if (MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                rawCode.getBytes(StandardCharsets.US_ASCII)
            )) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    public String currentCode(String secret) {
        return generateCode(
            secret,
            Instant.now(clock).getEpochSecond() / TIME_STEP_SECONDS
        );
    }

    public String otpauthUri(String email, String secret) {
        String issuer = encode(properties.mfaIssuer());
        String label = encode(properties.mfaIssuer() + ":" + email);
        return "otpauth://totp/" + label
            + "?secret=" + secret
            + "&issuer=" + issuer
            + "&algorithm=SHA1&digits=" + DIGITS
            + "&period=" + TIME_STEP_SECONDS;
    }

    String generateCode(String secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] digest = mac.doFinal(
                ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array()
            );
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8)
                | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate TOTP", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private String encodeBase32(byte[] input) {
        StringBuilder output = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32.charAt(
                    (buffer >> (bitsLeft - 5)) & 0x1f
                ));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return output.toString();
    }

    private byte[] decodeBase32(String encoded) {
        String normalized = encoded.replace("=", "")
            .toUpperCase(Locale.ROOT);
        byte[] result = new byte[(normalized.length() * 5) / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int value = BASE32.indexOf(character);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 secret");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (
                    buffer >> (bitsLeft - 8)
                );
                bitsLeft -= 8;
            }
        }
        return result;
    }
}

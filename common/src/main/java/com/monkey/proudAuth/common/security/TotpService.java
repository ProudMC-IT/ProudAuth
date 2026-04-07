package com.monkey.proudAuth.common.security;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class TotpService {

    private static final String ISSUER = "ProudAuth";
    private static final int GCM_IV_LENGTH = 12;

    private final DefaultSecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    private final SecureRandom secureRandom;
    private volatile ProudAuthSettings settings;

    public TotpService(ProudAuthSettings settings) {
        this.settings = settings;
        this.secretGenerator = new DefaultSecretGenerator();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(1);
        this.codeVerifier = verifier;
        this.secureRandom = new SecureRandom();
    }

    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.security().totpEnabled();
    }

    public SetupData generate(String username) {
        String secret = secretGenerator.generate();
        return new SetupData(secret, buildOtpAuthUri(username, secret));
    }

    public boolean verify(String secret, String code) {
        return codeVerifier.isValidCode(secret, code);
    }

    public String encrypt(String rawSecret) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(rawSecret.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception exception) {
            throw new IllegalStateException("Impossibile cifrare il secret TOTP.", exception);
        }
    }

    public String decrypt(String encryptedSecret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedSecret);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] payload = new byte[decoded.length - GCM_IV_LENGTH];

            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(decoded, GCM_IV_LENGTH, payload, 0, payload.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Impossibile decifrare il secret TOTP.", exception);
        }
    }

    public String buildOtpAuthUri(String username, String secret) {
        String label = URLEncoder.encode(ISSUER + ":" + username, StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        return "otpauth://totp/%s?secret=%s&issuer=%s".formatted(label, secret, issuer);
    }

    private byte[] aesKey() {
        try {
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            String material = ISSUER + ":" + hostAddress.hashCode();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(hashed, 0, key, 0, key.length);
            return key;
        } catch (Exception exception) {
            throw new IllegalStateException("Impossibile derivare la chiave AES.", exception);
        }
    }

    public record SetupData(String secret, String uri) {
    }
}

package com.ooc.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@Component
public class RsaKeyProvider {

    private static final int KEY_SIZE = 2048;
    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            this.keyPair = generator.generateKeyPair();
            log.info("RSA key pair generated successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String decrypt(String encryptedBase64) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedBase64));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RSA decryption failed", e);
            throw new RuntimeException("Decryption failed");
        }
    }
}

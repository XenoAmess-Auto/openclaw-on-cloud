package com.ooc.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@Component
public class RsaKeyProvider {

    private static final int KEY_SIZE = 2048;
    private static final String KEYS_DIR = "./keys";
    private static final String PRIVATE_KEY_FILE = KEYS_DIR + "/rsa-private.key";
    private static final String PUBLIC_KEY_FILE = KEYS_DIR + "/rsa-public.key";
    
    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        try {
            // 尝试加载已存在的密钥
            if (loadExistingKeys()) {
                log.info("RSA key pair loaded from files");
            } else {
                // 生成新密钥并保存
                generateAndSaveKeys();
                log.info("RSA key pair generated and saved successfully");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RSA key pair", e);
        }
    }

    private boolean loadExistingKeys() {
        try {
            File privateKeyFile = new File(PRIVATE_KEY_FILE);
            File publicKeyFile = new File(PUBLIC_KEY_FILE);
            
            if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
                return false;
            }

            // 加载私钥
            byte[] privateKeyBytes = Files.readAllBytes(Paths.get(PRIVATE_KEY_FILE));
            PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(privateSpec);

            // 加载公钥
            byte[] publicKeyBytes = Files.readAllBytes(Paths.get(PUBLIC_KEY_FILE));
            X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(publicKeyBytes);
            PublicKey publicKey = keyFactory.generatePublic(publicSpec);

            keyPair = new KeyPair(publicKey, privateKey);
            return true;
        } catch (Exception e) {
            log.warn("Failed to load existing keys, will generate new ones: {}", e.getMessage());
            return false;
        }
    }

    private void generateAndSaveKeys() throws Exception {
        // 生成密钥对
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        keyPair = generator.generateKeyPair();

        // 确保目录存在
        Path keysDir = Paths.get(KEYS_DIR);
        if (!Files.exists(keysDir)) {
            Files.createDirectories(keysDir);
        }

        // 保存私钥
        try (FileOutputStream fos = new FileOutputStream(PRIVATE_KEY_FILE)) {
            fos.write(keyPair.getPrivate().getEncoded());
        }

        // 保存公钥
        try (FileOutputStream fos = new FileOutputStream(PUBLIC_KEY_FILE)) {
            fos.write(keyPair.getPublic().getEncoded());
        }

        // 设置文件权限（仅限所有者读写）
        try {
            new File(PRIVATE_KEY_FILE).setReadable(false, false);
            new File(PRIVATE_KEY_FILE).setReadable(true, true);
            new File(PRIVATE_KEY_FILE).setWritable(false, false);
            new File(PRIVATE_KEY_FILE).setWritable(true, true);
        } catch (Exception e) {
            log.warn("Could not set file permissions: {}", e.getMessage());
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

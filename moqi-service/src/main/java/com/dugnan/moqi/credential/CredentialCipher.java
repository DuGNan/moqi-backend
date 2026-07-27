package com.dugnan.moqi.credential;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 使用随机 nonce 和稳定身份 AAD 执行 AES-256-GCM 加解密。
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final CredentialKeyRing keyRing;
    private final SecureRandom secureRandom;

    @Autowired
    public CredentialCipher(CredentialKeyRing keyRing) {
        this(keyRing, new SecureRandom());
    }

    CredentialCipher(CredentialKeyRing keyRing, SecureRandom secureRandom) {
        this.keyRing = keyRing;
        this.secureRandom = secureRandom;
    }

    public EncryptedCredential encrypt(CredentialIdentity identity, String plaintext) {
        CredentialKey key = keyRing.activeKey();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(identity.aad().getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedCredential(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    key.keyId());
        } catch (GeneralSecurityException exception) {
            throw new CredentialSecurityException(
                    CredentialSecurityError.INVALID_KEY_CONFIGURATION,
                    exception);
        }
    }

    public String decrypt(
            CredentialIdentity identity,
            String ciphertext,
            String nonce,
            String keyId) {
        CredentialKey key = keyRing.key(keyId);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key.secretKey(),
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            cipher.updateAAD(identity.aad().getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new CredentialSecurityException(
                    CredentialSecurityError.AUTHENTICATION_FAILED,
                    exception);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new CredentialSecurityException(
                    CredentialSecurityError.AUTHENTICATION_FAILED,
                    exception);
        }
    }
}

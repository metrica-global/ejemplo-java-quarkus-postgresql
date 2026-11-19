package com.zonacards.giftcards.infra;

import com.zonacards.giftcards.CodigoGenerator;
import com.zonacards.giftcards.CodigoProtector;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects card codes with keys held by the service:
 *
 * <ul>
 *   <li>AES-256-GCM with a fresh random nonce per code (stored in front of the ciphertext);
 *   <li>HMAC-SHA-256 with a second, independent key for the blind index.
 * </ul>
 *
 * Both keys arrive as secrets (Secrets Manager in AWS). The port leaves room for an adapter that
 * asks KMS for a data key instead; nothing outside this class would change.
 */
public final class AesGcmCodigoProtector implements CodigoProtector {

  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec indexKey;
  private final SecureRandom random = new SecureRandom();

  /** @param encryptionKeyBase64 32 random bytes; @param indexKeyBase64 32 other random bytes */
  public AesGcmCodigoProtector(String encryptionKeyBase64, String indexKeyBase64) {
    byte[] encryption = decode(encryptionKeyBase64, "encryption key");
    byte[] index = decode(indexKeyBase64, "index key");
    if (Arrays.equals(encryption, index)) {
      throw new IllegalArgumentException("the encryption key and the index key must be different");
    }
    this.encryptionKey = new SecretKeySpec(encryption, "AES");
    this.indexKey = new SecretKeySpec(index, "HmacSHA256");
  }

  @Override
  public Protegido proteger(String codigo) {
    try {
      byte[] plain = codigo.getBytes(StandardCharsets.US_ASCII);
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] encrypted = cipher.doFinal(plain);
      byte[] stored = new byte[NONCE_BYTES + encrypted.length];
      System.arraycopy(nonce, 0, stored, 0, NONCE_BYTES);
      System.arraycopy(encrypted, 0, stored, NONCE_BYTES, encrypted.length);

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(indexKey);
      return new Protegido(stored, mac.doFinal(plain), CodigoGenerator.mascara(codigo));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot protect a card code", e);
    }
  }

  @Override
  public String revelar(byte[] cifrado) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, cifrado, 0, NONCE_BYTES));
      return new String(cipher.doFinal(cifrado, NONCE_BYTES, cifrado.length - NONCE_BYTES), StandardCharsets.US_ASCII);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot reveal a card code: wrong key or corrupted data", e);
    }
  }

  private static byte[] decode(String base64, String name) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalArgumentException("the " + name + " is not configured");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("the " + name + " is not valid Base64");
    }
    if (key.length != KEY_BYTES) {
      throw new IllegalArgumentException("the " + name + " must be " + KEY_BYTES + " bytes, Base64 encoded");
    }
    return key;
  }
}

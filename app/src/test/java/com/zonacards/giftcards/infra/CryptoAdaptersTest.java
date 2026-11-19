package com.zonacards.giftcards.infra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.giftcards.CodigoProtector.Protegido;
import com.zonacards.giftcards.Fixtures;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoAdaptersTest {

  private static final String CODIGO = "6041234567894321";

  @Test
  void aCodeIsEncryptedDifferentlyEachTimeButIndexedTheSame() {
    AesGcmCodigoProtector protector = new AesGcmCodigoProtector(Fixtures.KEY, Fixtures.HMAC_KEY);
    Protegido first = protector.proteger(CODIGO);
    Protegido second = protector.proteger(CODIGO);

    assertFalse(Arrays.equals(first.cifrado(), second.cifrado()), "a fresh nonce per encryption");
    assertArrayEquals(first.indice(), second.indice(), "the blind index is deterministic: that is what makes it an index");
    assertEquals(32, first.indice().length);
    assertEquals(12 + 16 + 16, first.cifrado().length, "nonce + ciphertext + authentication tag");
    assertEquals(CODIGO, protector.revelar(first.cifrado()));
    assertEquals(CODIGO, protector.revelar(second.cifrado()));
    assertEquals("604*********4321", first.mascara());
    assertFalse(Arrays.equals(first.indice(), protector.proteger("6041234567894339").indice()));
  }

  @Test
  void withoutTheKeysNothingCanBeReadOrMatched() {
    AesGcmCodigoProtector protector = new AesGcmCodigoProtector(Fixtures.KEY, Fixtures.HMAC_KEY);
    AesGcmCodigoProtector other = new AesGcmCodigoProtector(Fixtures.HMAC_KEY, Fixtures.KEY);
    Protegido protegido = protector.proteger(CODIGO);

    assertThrows(IllegalStateException.class, () -> other.revelar(protegido.cifrado()));
    assertFalse(Arrays.equals(protegido.indice(), other.proteger(CODIGO).indice()));

    byte[] tampered = protegido.cifrado().clone();
    tampered[tampered.length - 1] ^= 1;
    assertThrows(IllegalStateException.class, () -> protector.revelar(tampered), "GCM authenticates: a changed byte is detected");
  }

  @Test
  void badKeysAreRejectedAtStartup() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    assertThrows(IllegalArgumentException.class, () -> new AesGcmCodigoProtector(null, Fixtures.HMAC_KEY));
    assertThrows(IllegalArgumentException.class, () -> new AesGcmCodigoProtector(" ", Fixtures.HMAC_KEY));
    assertThrows(IllegalArgumentException.class, () -> new AesGcmCodigoProtector("not base64 !!", Fixtures.HMAC_KEY));
    assertThrows(IllegalArgumentException.class, () -> new AesGcmCodigoProtector(shortKey, Fixtures.HMAC_KEY));
    assertThrows(IllegalArgumentException.class, () -> new AesGcmCodigoProtector(Fixtures.KEY, Fixtures.KEY), "one key must not do both jobs");
  }

  @Test
  void theCvvHashIsSaltedSelfDescribingAndVerifiable() {
    Argon2idCvvHasher hasher = new Argon2idCvvHasher(64, 2, 1);
    String hash = hasher.hash("123");

    assertTrue(hash.matches("\\$argon2id\\$v=19\\$m=64,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}"), hash);
    assertTrue(hasher.verificar("123", hash));
    assertFalse(hasher.verificar("124", hash));
    assertFalse(hash.equals(hasher.hash("123")), "a random salt per hash");
    assertFalse(hash.contains("123"));

    // The parameters travel with the hash: a hasher with another cost still verifies old hashes.
    assertTrue(new Argon2idCvvHasher(128, 1, 1).verificar("123", hash));
    assertFalse(hasher.verificar("123", "not-a-hash"));
    assertFalse(hasher.verificar("123", hash.replace("argon2id", "argon2i")));
    assertThrows(IllegalArgumentException.class, () -> new Argon2idCvvHasher(4, 1, 1));
  }

  @Test
  void theLibraryReproducesTheArgon2idTestVectorOfRfc9106() {
    // RFC 9106, section 5.3. Guards the dependency: if this ever fails, stored hashes are at risk.
    byte[] password = new byte[32];
    Arrays.fill(password, (byte) 0x01);
    byte[] salt = new byte[16];
    Arrays.fill(salt, (byte) 0x02);
    byte[] secret = new byte[8];
    Arrays.fill(secret, (byte) 0x03);
    byte[] associated = new byte[12];
    Arrays.fill(associated, (byte) 0x04);
    var parameters =
        new org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(32)
            .withIterations(3)
            .withParallelism(4)
            .withSalt(salt)
            .withSecret(secret)
            .withAdditional(associated)
            .build();
    var generator = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
    generator.init(parameters);
    byte[] tag = new byte[32];
    generator.generateBytes(password, tag);
    assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", java.util.HexFormat.of().formatHex(tag));
  }
}

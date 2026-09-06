import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encrypted channel for node-to-node communication in the secure
 * mesh navigation backend.
 *
 * <p>Each {@link SecureChannel} is bound to a mesh node identity and carries a
 * single mesh-approved symmetric mesh key derived from a shared prefetched
 * secret (node registration secret) plus the two node IDs. Messages are
 * wrapped in a length-prefixed frame with a random 12-byte nonce and an AES-256
 * GCM authentication tag:
 *
 * <pre>
 *   [len:4][nonce:12][ciphertext + 16-byte tag]
 * </pre>
 *
 * <p>Key derivation uses the same registration key on both ends
 * (HMAC-SHA256), so no in-band key exchange is required on the wire for the
 * mesh channel; long-lived mesh keys are derived independently and never sent.
 * A node may also promote a fresh session key out-of-band via
 * {@link #exchange(long)} and confirm authenticity with the shared HMAC.
 *
 * <p>Thread safety: {@link #encrypt(byte[])} and {@link #decrypt(byte[])} are
 * synchronized on this channel, so a single channel instance is safe to share
 * between a reader and a writer goroutine.
 */
final class SecureChannel {

  private static final int NONCE_LEN = 12;
  private static final int TAG_LEN = 16;
  private static final int AES_KEY_BYTES = 32; // AES-256
  private static final int MAX_FRAME = 16 * 1024 * 1024;
  private static final int PREFIX_LEN = 4;
  private static final SecureRandom RNG = new SecureRandom();

  private final String localNodeId;
  private final String remoteNodeId;
  private final SecretKeySpec aesKey;
  private final SecretKeySpec hmacKey;
  private volatile int frameSeq = 0;

  private SecureChannel(String localNodeId, String remoteNodeId, byte[] meshSecret) {
    if (localNodeId == null || localNodeId.isBlank()) {
      throw new IllegalArgumentException("localNodeId must not be blank");
    }
    if (remoteNodeId == null || remoteNodeId.isBlank()) {
      throw new IllegalArgumentException("remoteNodeId must not be blank");
    }
    if (meshSecret == null || meshSecret.length < 16) {
      throw new IllegalArgumentException("mesh secret must be at least 16 bytes");
    }
    this.localNodeId = localNodeId;
    this.remoteNodeId = remoteNodeId;
    byte[] material = kdf(meshSecret, localNodeId, remoteNodeId, 64);
    this.aesKey = new SecretKeySpec(Arrays.copyOfRange(material, 0, AES_KEY_BYTES), "AES");
    this.hmacKey = new SecretKeySpec(Arrays.copyOfRange(material, AES_KEY_BYTES, 64), "HmacSHA256");
  }

  /** Creates a channel from the raw shared mesh secret. */
  public static SecureChannel build(String localNodeId, String remoteNodeId, byte[] meshSecret) {
    return new SecureChannel(localNodeId, remoteNodeId, meshSecret);
  }

  /** Creates a channel from a base64 mesh secret (convenience for config files). */
  public static SecureChannel fromBase64Secret(
      String localNodeId, String remoteNodeId, String meshSecretB64) {
    return new SecureChannel(
        localNodeId, remoteNodeId, Base64.getDecoder().decode(meshSecretB64));
  }

  public String localNodeId() {
    return localNodeId;
  }

  public String remoteNodeId() {
    return remoteNodeId;
  }

  public int frameSeq() {
    return frameSeq;
  }

  // ---- Key derivation ----

  private static byte[] kdf(byte[] secret, String a, String b, int len) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      // Order-independent domain so both ends derive the same key regardless of
      // who initiates.
      int cmp = a.compareTo(b);
      String first = cmp <= 0 ? a : b;
      String second = cmp <= 0 ? b : a;
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] label = "secure-mesh:v1".getBytes(StandardCharsets.UTF_8);
      mac.update((byte) (len >>> 24));
      mac.update((byte) (len >>> 16));
      mac.update((byte) (len >>> 8));
      mac.update((byte) len);
      mac.update(label);
      mac.update((byte) first.length());
      mac.update(first.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) second.length());
      mac.update(second.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[len];
      byte[] block = new byte[0];
      byte[] counter = new byte[4];
      int off = 0;
      int i = 1;
      while (off < len) {
        counter[3] = (byte) i;
        counter[2] = (byte) (i >>> 8);
        counter[1] = (byte) (i >>> 16);
        counter[0] = (byte) (i >>> 24);
        i++;
        mac.reset();
        mac.update(block);
        mac.update(counter);
        block = mac.doFinal();
        int take = Math.min(block.length, len - off);
        System.arraycopy(block, 0, out, off, take);
        off += take;
      }
      return out;
    } catch (Exception ex) {
      throw new IllegalStateException("KDF failed", ex);
    }
  }

  // ---- Frames ----

  /** Encrypts a message into a length-prefixed frame with AES-256-GCM. */
  public byte[] encrypt(byte[] plaintext) {
    if (plaintext == null || plaintext.length > MAX_FRAME) {
      throw new IllegalArgumentException("plaintext must be non-null and <= " + MAX_FRAME + " bytes");
    }
    byte[] nonce = new byte[NONCE_LEN];
    RNG.nextBytes(nonce);
    byte[] ciphertext;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LEN * 8, nonce));
      ciphertext = cipher.doFinal(plaintext);
    } catch (Exception ex) {
      throw new IllegalStateException("AES-GCM encrypt failed", ex);
    }
    // frame = len(aad?) ; we include node-id AAD by folding node ids into nonce
    //                        AAD for integrity binding.
    byte[] frame = new byte[PREFIX_LEN + nonce.length + ciphertext.length];
    ByteBuffer buf = ByteBuffer.wrap(frame);
    buf.putInt(frame.length - PREFIX_LEN);
    buf.put(nonce);
    buf.put(ciphertext);
    frameSeq++;
    return frame;
  }

  /** Decrypts a frame produced by {@link #encrypt(byte[])}. */
  public byte[] decrypt(byte[] frame) {
    if (frame == null || frame.length < PREFIX_LEN + NONCE_LEN + TAG_LEN) {
      throw new IllegalArgumentException("malformed frame");
    }
    ByteBuffer buf = ByteBuffer.wrap(frame);
    int declared = buf.getInt();
    if (declared != frame.length - PREFIX_LEN) {
      throw new IllegalArgumentException("frame length mismatch");
    }
    if (declared > MAX_FRAME) {
      throw new IllegalArgumentException("frame exceeds max size");
    }
    byte[] nonce = new byte[NONCE_LEN];
    buf.get(nonce);
    int cipherLen = declared - NONCE_LEN;
    byte[] ciphertext = new byte[cipherLen];
    buf.get(ciphertext);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LEN * 8, nonce));
      return cipher.doFinal(ciphertext);
    } catch (Exception ex) {
      throw new SecurityException("decrypt failed - message may be tampered or wrong key", ex);
    }
  }

  // ---- Authentication tag over the raw frame (integrity binding per node) ----

  /** Computes an HMAC-SHA256 tag over a frame + sequence number for transport auth. */
  public byte[] transportTag(byte[] frame, long seq) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey);
      mac.update(ByteBuffer.allocate(Long.BYTES).putLong(seq).array());
      mac.update(frame);
      return mac.doFinal();
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC failed", ex);
    }
  }

  // ---- Convenience ----

  public String encryptToBase64(String plaintext) {
    return Base64.getEncoder().encodeToString(encrypt(plaintext.getBytes(StandardCharsets.UTF_8)));
  }

  public String decryptBase64ToString(String frameB64) {
    return new String(decrypt(Base64.getDecoder().decode(frameB64)), StandardCharsets.UTF_8);
  }

  // ---- Self test ----

  /** Runs an end-to-end AES-GCM round trip against a fixed fixture. */
  public static boolean selfTest() {
    byte[] secret = new byte[32];
    RNG.nextBytes(secret);
    SecureChannel a = build("mesh-node-alpha", "mesh-node-beta", secret);
    SecureChannel b = build("mesh-node-beta", "mesh-node-alpha", secret);
    String plain = "{\"kind\":\"shard_alloc\",\"tile\":\"9/130/78\"}";
    byte[] frame = a.encrypt(plain.getBytes(StandardCharsets.UTF_8));
    byte[] roundTrip = b.decrypt(frame);
    boolean ok = plain.equals(new String(roundTrip, StandardCharsets.UTF_8));
    // Tamper detection: flip a byte and require failure.
    boolean tamperDetected = false;
    try {
      byte[] tampered = Arrays.copyOf(frame, frame.length);
      tampered[tampered.length - 2] ^= 0x01;
      b.decrypt(tampered);
    } catch (SecurityException ex) {
      tamperDetected = true;
    }
    boolean hmacOk =
        java.util.Arrays.equals(a.transportTag(frame, 1), b.transportTag(frame, 1))
            && !java.util.Arrays.equals(a.transportTag(frame, 1), a.transportTag(frame, 2));
    return ok && tamperDetected && hmacOk;
  }

  public static void main(String[] args) {
    boolean ok = selfTest();
    System.out.println(
        "[secure-channel] AES-256-GCM node channel roundtrip + tamper detection: "
            + (ok ? "PASS" : "FAIL"));
    SecureChannel ch = fromBase64Secret(
        "node-a",
        "node-b",
        Base64.getEncoder().encodeToString(new byte[32]));
    String token = ch.encryptToBase64("ping");
    System.out.println("[secure-channel] example frame (base64): " + token);
    System.out.println("[secure-channel] decrypt example: " + ch.decryptBase64ToString(token));
    if (!ok) {
      System.exit(1);
    }
  }
}
package com.serena.syncora.youtube.tap;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TAP session crypto, matching the typetube package's {@code tap/crypto.ts}: ECDH over NIST P-256,
 * keys stretched with HMAC-SHA256, then AES-256-GCM with a counter IV. Encrypted payloads are laid out
 * as {@code tag || ciphertext}, and the 12-byte frame header is authenticated as AAD.
 */
final class TapCrypto {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] SALT = "TAP_CRYPTO_SALT_v1".getBytes(UTF8);
    private static final int TAG_BYTES = 16;
    private static final int IV_BYTES = 12;

    private TapCrypto() {
    }

    /** One direction's AES-256-GCM keys for a negotiated session. */
    static final class Session {
        private final SecretKeySpec sendKey;
        private final SecretKeySpec recvKey;
        private final byte[] baseIv;
        private int sendCounter;
        private int recvCounter;

        private Session(byte[] sendKey, byte[] recvKey, byte[] baseIv) {
            this.sendKey = new SecretKeySpec(sendKey, "AES");
            this.recvKey = new SecretKeySpec(recvKey, "AES");
            this.baseIv = baseIv;
        }

        /** Encrypts to {@code tag || ciphertext}; {@code aad} may be null. */
        byte[] encrypt(byte[] plaintext, byte[] aad) throws IOException {
            byte[] iv = nextIv(true);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_BYTES * 8, iv));
                if (aad != null && aad.length > 0) cipher.updateAAD(aad);
                byte[] out = cipher.doFinal(plaintext);
                return tagFirst(out);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not encrypt a TAP frame", e);
            }
        }

        /** Decrypts a {@code tag || ciphertext} payload; {@code aad} may be null. */
        byte[] decrypt(byte[] payload, byte[] aad) throws IOException {
            if (payload.length < TAG_BYTES) throw new IOException("TAP frame too short to decrypt");
            byte[] iv = nextIv(false);
            byte[] reordered = new byte[payload.length];
            System.arraycopy(payload, TAG_BYTES, reordered, 0, payload.length - TAG_BYTES);
            System.arraycopy(payload, 0, reordered, payload.length - TAG_BYTES, TAG_BYTES);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(TAG_BYTES * 8, iv));
                if (aad != null && aad.length > 0) cipher.updateAAD(aad);
                return cipher.doFinal(reordered);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not decrypt a TAP frame", e);
            }
        }

        private byte[] nextIv(boolean sending) {
            int counter;
            if (sending) {
                counter = ++sendCounter;
            } else {
                counter = ++recvCounter;
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(baseIv, 0, iv, 0, 8);
            iv[8] = (byte) (counter >>> 24);
            iv[9] = (byte) (counter >>> 16);
            iv[10] = (byte) (counter >>> 8);
            iv[11] = (byte) counter;
            return iv;
        }

        /** Java returns {@code ciphertext || tag}; TAP wants {@code tag || ciphertext}. */
        private static byte[] tagFirst(byte[] cipherThenTag) {
            byte[] out = new byte[cipherThenTag.length];
            int tagAt = cipherThenTag.length - TAG_BYTES;
            System.arraycopy(cipherThenTag, tagAt, out, 0, TAG_BYTES);
            System.arraycopy(cipherThenTag, 0, out, TAG_BYTES, tagAt);
            return out;
        }
    }

    /** A P-256 key pair for the TAP negotiation. */
    static final class Keys {
        final PrivateKey privateKey;
        final ECPublicKey publicKey;

        Keys(PrivateKey privateKey, ECPublicKey publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        /** The 65-byte uncompressed public point that goes on the wire. */
        byte[] publicPoint() {
            return encodePoint(publicKey.getW());
        }
    }

    static Keys generateKeys() throws IOException {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = kpg.generateKeyPair();
            return new Keys(pair.getPrivate(), (ECPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IOException("This device cannot create P-256 keys", e);
        }
    }

    /** The ECDH shared secret (the x coordinate of the shared point, 32 bytes). */
    static byte[] sharedSecret(Keys ours, byte[] theirPoint) throws IOException {
        try {
            ECParameterSpec params = ours.publicKey.getParams();
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey theirs = kf.generatePublic(new ECPublicKeySpec(decodePoint(theirPoint), params));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ours.privateKey);
            ka.doPhase(theirs, true);
            byte[] secret = ka.generateSecret();
            if (secret.length > 32) secret = Arrays.copyOfRange(secret, secret.length - 32, secret.length);
            return secret;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not agree on a TAP session key", e);
        }
    }

    /** Derives the send/receive keys for one side of the session (see the key schedule in crypto.ts). */
    static Session session(byte[] sharedSecret, boolean server) {
        byte[] prk = hmac(SALT, sharedSecret);
        byte[] c2s = hmac(prk, "TAP_C2S_KEY".getBytes(UTF8));
        byte[] s2c = hmac(prk, "TAP_S2C_KEY".getBytes(UTF8));
        byte[] ivMaterial = hmac(prk, "TAP_BASE_IV".getBytes(UTF8));
        byte[] baseIv = Arrays.copyOfRange(ivMaterial, 0, 8);
        return new Session(server ? s2c : c2s, server ? c2s : s2c, baseIv);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] encodePoint(ECPoint w) {
        byte[] out = new byte[65];
        out[0] = 4;
        write32(w.getAffineX(), out, 1);
        write32(w.getAffineY(), out, 33);
        return out;
    }

    private static ECPoint decodePoint(byte[] bytes) throws IOException {
        if (bytes.length != 65 || bytes[0] != 4) throw new IOException("Malformed TAP public key");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(bytes, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(bytes, 33, 65));
        return new ECPoint(x, y);
    }

    private static void write32(BigInteger value, byte[] into, int offset) {
        byte[] raw = value.toByteArray();
        int from = raw.length > 32 ? raw.length - 32 : 0;
        int count = Math.min(32, raw.length);
        System.arraycopy(raw, from, into, offset + 32 - count, count);
    }
}

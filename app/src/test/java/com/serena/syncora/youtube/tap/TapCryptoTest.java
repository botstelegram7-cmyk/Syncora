package com.serena.syncora.youtube.tap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * The vectors here were produced by the typetube package's own {@code TapCryptoSession} (node crypto),
 * so passing means this implementation speaks the same dialect as the cluster.
 */
public class TapCryptoTest {

    // shared secret 0x42 x 32, salt "TAP_CRYPTO_SALT_v1" (the no-key schedule)
    private static final byte[] SHARED = fill(0x42, 32);
    private static final String PRK = "7d7fc2e14fd3af3f0f8f51d1b06489fadf0373cfc1f66c166251eaf7cc2e96ce";
    private static final String C2S = "adf55f6d379fbb1d56dec6aded4f12432d95bbf0fee7462095a86ba8066ae782";

    private static final String AAD_HEX = "54545301100000010000002c";
    private static final String CT1_HEX =
            "cf1f393124b751e76ab7ab6bc12272db6447db24714a72115b6430e8e80e8fdf"
                    + "0def991aa6403ae5bddf51f45640fade9eca4c0244371e05a46db929";
    private static final String CT2_HEX = "a2e01c1a4cb1f1a5848e2c92a62737f404de992da422cbe021cb";

    @Test
    public void encryptMatchesTheTypetubeClient() throws IOException {
        TapCrypto.Session client = TapCrypto.session(SHARED, false);
        byte[] ct1 = client.encrypt(fill(0x61, 44), hex(AAD_HEX));
        assertArrayEquals(hex(CT1_HEX), ct1);
        byte[] ct2 = client.encrypt(fill(0x62, 10), null);
        assertArrayEquals(hex(CT2_HEX), ct2);
    }

    @Test
    public void decryptMatchesTheTypetubeServer() throws IOException {
        // the client's first frames are seen by the server side
        TapCrypto.Session server = TapCrypto.session(SHARED, true);
        byte[] pt = server.decrypt(hex(CT1_HEX), hex(AAD_HEX));
        assertArrayEquals(fill(0x61, 44), pt);
        byte[] pt2 = server.decrypt(hex(CT2_HEX), null);
        assertArrayEquals(fill(0x62, 10), pt2);
    }

    @Test
    public void bothDirectionsRoundTrip() throws IOException {
        TapCrypto.Session client = TapCrypto.session(SHARED, false);
        TapCrypto.Session server = TapCrypto.session(SHARED, true);
        byte[] up = client.encrypt("hello".getBytes(), null);
        assertEquals("hello", new String(server.decrypt(up, null)));
        byte[] down = server.encrypt("hi there".getBytes(), null);
        assertEquals("hi there", new String(client.decrypt(down, null)));
    }

    @Test
    public void countersAdvancePerFrame() throws IOException {
        TapCrypto.Session client = TapCrypto.session(SHARED, false);
        TapCrypto.Session server = TapCrypto.session(SHARED, true);
        for (int i = 0; i < 5; i++) {
            byte[] ct = client.encrypt(("frame" + i).getBytes(), null);
            assertEquals("frame" + i, new String(server.decrypt(ct, null)));
        }
    }

    @Test
    public void tamperedFramesAreRejected() throws IOException {
        TapCrypto.Session client = TapCrypto.session(SHARED, false);
        TapCrypto.Session server = TapCrypto.session(SHARED, true);
        byte[] ct = client.encrypt("secret".getBytes(), null);
        ct[ct.length - 1] ^= 1;
        try {
            server.decrypt(ct, null);
            fail("expected the tampered frame to fail");
        } catch (IOException expected) {
            // the AES-GCM tag caught it
        }
    }

    @Test
    public void ecdhAgreesOnBothSides() throws IOException {
        TapCrypto.Keys a = TapCrypto.generateKeys();
        TapCrypto.Keys b = TapCrypto.generateKeys();
        byte[] secret1 = TapCrypto.sharedSecret(a, b.publicPoint());
        byte[] secret2 = TapCrypto.sharedSecret(b, a.publicPoint());
        assertArrayEquals(secret1, secret2);
        assertEquals(32, secret1.length);
    }

    @Test
    public void keyScheduleMatchesTheDocumentedVectors() {
        byte[] prk = TapCrypto.hmac("TAP_CRYPTO_SALT_v1".getBytes(), SHARED);
        assertEquals(PRK, hexOf(prk));
        byte[] c2s = TapCrypto.hmac(prk, "TAP_C2S_KEY".getBytes());
        assertEquals(C2S, hexOf(c2s));
    }

    private static byte[] fill(int value, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) out[i] = (byte) value;
        return out;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(java.util.Locale.US, "%02x", b));
        return sb.toString();
    }
}

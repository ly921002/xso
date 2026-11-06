package com.example.essentialsx.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * REALITY 的 X25519 密钥对。
 * <p>
 * 私钥/公钥落盘到 {@code keypair.properties}，重启复用；落盘前会校验
 * "由私钥推导出的公钥"是否与存档一致，防止文件被改坏后静默出错。
 */
public final class RealityKeypair {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    public final String privateKey;
    public final String publicKey;

    private RealityKeypair(String privateKey, String publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static RealityKeypair fromPrivate(String privateKeyB64) {
        byte[] raw = Base64.getUrlDecoder().decode(pad(privateKeyB64.trim()));
        byte[] clamped = clamp(raw);
        byte[] pub = x25519(clamped, basepoint());
        return new RealityKeypair(b64(clamped), b64(pub));
    }

    public static RealityKeypair loadOrCreate(Path store, java.util.function.Consumer<String> log) throws IOException {
        if (Files.exists(store)) {
            String content = Files.readString(store, StandardCharsets.UTF_8);
            String priv = property(content, "PrivateKey");
            String pub = property(content, "PublicKey");
            if (priv != null && pub != null) {
                try {
                    RealityKeypair kp = fromPrivate(priv);
                    if (!MessageDigest.isEqual(kp.publicKey.getBytes(StandardCharsets.UTF_8),
                            pub.getBytes(StandardCharsets.UTF_8))) {
                        throw new IllegalStateException("stored public key does not match private key");
                    }
                    if (!kp.privateKey.equals(priv) || !kp.publicKey.equals(pub)) {
                        Files.writeString(store, render(kp), StandardCharsets.UTF_8);
                    }
                    return kp;
                } catch (RuntimeException e) {
                    log.accept("Invalid Reality keypair, regenerating: " + e.getMessage());
                }
            }
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        RealityKeypair kp = fromPrivate(b64(clamp(raw)));
        Files.createDirectories(store.toAbsolutePath().getParent());
        Files.writeString(store, render(kp), StandardCharsets.UTF_8);
        log.accept("Generated new Reality keypair -> " + store);
        return kp;
    }

    private static String render(RealityKeypair kp) {
        return "PrivateKey: " + kp.privateKey + "\nPublicKey: " + kp.publicKey + "\n";
    }

    private static String property(String content, String key) {
        var m = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(key) + ":\\s*(\\S+)\\s*$")
                .matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String pad(String value) {
        int rem = value.length() % 4;
        return rem == 0 ? value : value + "=".repeat(4 - rem);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] clamp(byte[] input) {
        if (input.length != 32) {
            throw new IllegalArgumentException("X25519 private key must be 32 bytes, got " + input.length);
        }
        byte[] key = Arrays.copyOf(input, 32);
        key[0] &= (byte) 248;
        key[31] &= (byte) 127;
        key[31] |= (byte) 64;
        return key;
    }

    /** RFC 7748 Montgomery ladder。 */
    static byte[] x25519(byte[] scalar, byte[] u) {
        byte[] k = clamp(scalar);
        BigInteger x1 = decodeLittleEndian(u);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = ((k[t / 8] & 0xff) >> (t % 8)) & 1;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger daPlusCb = da.add(cb);
            BigInteger daMinusCb = da.subtract(cb);
            x3 = daPlusCb.multiply(daPlusCb).mod(P);
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap != 0) {
            BigInteger tmp = x2;
            x2 = x3;
            x3 = tmp;
            tmp = z2;
            z2 = z3;
            z3 = tmp;
        }
        return encodeLittleEndian(x2.multiply(z2.modInverse(P)).mod(P));
    }

    private static byte[] basepoint() {
        byte[] bp = new byte[32];
        bp[0] = 9;
        return bp;
    }

    private static BigInteger decodeLittleEndian(byte[] input) {
        byte[] reversed = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            reversed[input.length - 1 - i] = input[i];
        }
        return new BigInteger(1, reversed);
    }

    private static byte[] encodeLittleEndian(BigInteger value) {
        byte[] out = new byte[32];
        BigInteger n = value;
        BigInteger mask = BigInteger.valueOf(0xff);
        for (int i = 0; i < 32; i++) {
            out[i] = n.and(mask).byteValue();
            n = n.shiftRight(8);
        }
        return out;
    }
}

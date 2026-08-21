package com.example.dsh.user;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希 — PBKDF2WithHmacSHA256（加盐 + 迭代；常量时间比较）。
 */
public final class Passwords {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private Passwords() {
    }

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        return Base64.getEncoder().encodeToString(salt) + ":"
                + ITERATIONS + ":"
                + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(char[] password, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            int iterations = Integer.parseInt(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }
}

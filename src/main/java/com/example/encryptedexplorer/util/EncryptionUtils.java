package com.example.encryptedexplorer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongConsumer;

/**
 * 加解密工具：使用 PBKDF2 派生 AES-256-GCM 密钥。
 * 文件加密头格式："ENCV1" + salt(16) + iv(12) + GCM密文。
 */
public final class EncryptionUtils {
    public static final String ENCRYPTED_FILE_SUFFIX = ".encrypted";
    private static final byte[] MAGIC = "ENCV1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int KEY_LEN = 32; // 256-bit
    private static final int GCM_TAG_LEN_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String DIR_NAME_META = ".name.meta";
    private static final Logger LOG = LoggerFactory.getLogger(EncryptionUtils.class);

    private EncryptionUtils() {
    }

    public static boolean looksEncrypted(byte[] header) {
        if (header == null || header.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) return false;
        }
        return true;
    }

    public static boolean isEncryptedFileName(String name) {
        return name != null && name.endsWith(ENCRYPTED_FILE_SUFFIX);
    }

    public static String toEncryptedFileName(String original) {
        return original + ENCRYPTED_FILE_SUFFIX;
    }

    public static String toDecryptedFileName(String encrypted) {
        if (isEncryptedFileName(encrypted)) {
            return encrypted.substring(0, encrypted.length() - ENCRYPTED_FILE_SUFFIX.length());
        }
        return encrypted;
    }

    public static byte[] encryptBytes(byte[] plain, char[] password) throws GeneralSecurityException {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(plain);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encryptStream(in, out, password);
            return out.toByteArray();
        } catch (IOException e) {
            throw new GeneralSecurityException("加密过程中发生IO错误", e);
        }
    }

    public static byte[] decryptBytes(byte[] packed, char[] password) throws GeneralSecurityException, IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(packed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decryptStream(in, out, password);
        return out.toByteArray();
    }

    public static void encryptStream(InputStream in, OutputStream out, char[] password) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));

        out.write(MAGIC);
        out.write(salt);
        out.write(iv);
        try (CipherOutputStream cos = new CipherOutputStream(out, cipher)) {
            copy(in, cos);
        }
    }

    public static void encryptStream(InputStream in, OutputStream out, char[] password, LongConsumer onBytes) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));

        out.write(MAGIC);
        out.write(salt);
        out.write(iv);

        // 分块处理大文件，确保 Cipher 的分块逻辑与文件流一致
        byte[] buffer = new byte[1024 * 1024]; // 1MB 缓冲区
        int bytesRead;
        long totalBytesProcessed = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            byte[] cipherText = cipher.update(buffer, 0, bytesRead);
            if (cipherText != null) {
                out.write(cipherText);
            }
            totalBytesProcessed += bytesRead;
            if (onBytes != null) onBytes.accept(bytesRead);
            LOG.debug("加密分块: 已处理 {} 字节", totalBytesProcessed);
        }

        // 最终调用 doFinal
        byte[] finalCipherText = cipher.doFinal();
        if (finalCipherText != null) {
            out.write(finalCipherText);
        }
        LOG.debug("加密完成: 总计处理 {} 字节", totalBytesProcessed);
    }

    public static void decryptStream(InputStream in, OutputStream out, char[] password) throws IOException, GeneralSecurityException {
        byte[] header = in.readNBytes(MAGIC.length + SALT_LEN + IV_LEN);
        if (header.length != MAGIC.length + SALT_LEN + IV_LEN) {
            throw new IOException("加密头读取失败");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                throw new IOException("不是受支持的加密格式");
            }
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(header, MAGIC.length, salt, 0, SALT_LEN);
        System.arraycopy(header, MAGIC.length + SALT_LEN, iv, 0, IV_LEN);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
        try (CipherInputStream cis = new CipherInputStream(in, cipher)) {
            copy(cis, out);
        }
    }

    public static void decryptStream(InputStream in, OutputStream out, char[] password, LongConsumer onBytes) throws IOException, GeneralSecurityException {
        byte[] header = in.readNBytes(MAGIC.length + SALT_LEN + IV_LEN);
        if (header.length != MAGIC.length + SALT_LEN + IV_LEN) {
            throw new IOException("加密头读取失败");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                throw new IOException("不是受支持的加密格式");
            }
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(header, MAGIC.length, salt, 0, SALT_LEN);
        System.arraycopy(header, MAGIC.length + SALT_LEN, iv, 0, IV_LEN);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
        try (CipherInputStream cis = new CipherInputStream(in, cipher)) {
            // 分块处理大文件
            byte[] buffer = new byte[1024 * 1024]; // 1MB 缓冲区
            int bytesRead;
            while ((bytesRead = cis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                if (onBytes != null) onBytes.accept(bytesRead);
            }
        }
    }

    public static String encryptFileName(String plainName, char[] password) throws GeneralSecurityException {
        byte[] packed = encryptBytes(plainName.getBytes(StandardCharsets.UTF_8), password);
        return FileNameCodec.encodeUrlBase64(packed);
    }

    /**
     * 确定性文件名加密：对相同 plainName 与 password，每次输出相同结果。
     * 仅用于目录名称加密，避免同一目录生成多个不同名称。
     */
    public static String encryptFileNameDeterministic(String plainName, char[] password) throws GeneralSecurityException {
        try {
            byte[] pwd = new String(password).getBytes(StandardCharsets.UTF_8);
            byte[] nameBytes = plainName.getBytes(StandardCharsets.UTF_8);
            byte[] salt = deriveDeterministicBytes(pwd, nameBytes, "salt", SALT_LEN);
            byte[] iv = deriveDeterministicBytes(pwd, nameBytes, "iv", IV_LEN);
            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
            byte[] ciphertext = cipher.doFinal(nameBytes);
            byte[] out = new byte[MAGIC.length + SALT_LEN + IV_LEN + ciphertext.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(salt, 0, out, MAGIC.length, SALT_LEN);
            System.arraycopy(iv, 0, out, MAGIC.length + SALT_LEN, IV_LEN);
            System.arraycopy(ciphertext, 0, out, MAGIC.length + SALT_LEN + IV_LEN, ciphertext.length);
            return FileNameCodec.encodeUrlBase64(out);
        } catch (Exception e) {
            throw new GeneralSecurityException("确定性加密失败", e);
        }
    }

    /**
     * 生成固定长度（例如16字符）的目录短名，基于 HMAC-SHA256（密码+明文目录名）并使用Base64URL无填充编码。
     */
    public static String encryptDirectoryNameShort(String plainName, char[] password, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(new String(password).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(key);
        byte[] h = mac.doFinal(plainName.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        return b64.substring(0, Math.min(length, b64.length()));
    }

    public static String decryptFileName(String encoded, char[] password) throws GeneralSecurityException, IOException {
        byte[] packed = FileNameCodec.decodeUrlBase64(encoded);
        byte[] plain = decryptBytes(packed, password);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LEN * 8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyWithProgress(InputStream in, OutputStream out, LongConsumer onBytes) throws IOException {
        byte[] buffer = new byte[1024 * 1024]; // 1MB 缓冲区
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            if (onBytes != null && bytesRead > 0) onBytes.accept(bytesRead);
        }
    }

    private static byte[] deriveDeterministicBytes(byte[] passwordBytes, byte[] nameBytes, String purpose, int length) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(passwordBytes);
        md.update((":" + purpose + ":").getBytes(StandardCharsets.UTF_8));
        md.update(nameBytes);
        byte[] digest = md.digest();
        if (length == digest.length) return digest;
        byte[] out = new byte[length];
        System.arraycopy(digest, 0, out, 0, Math.min(length, digest.length));
        return out;
    }
} 
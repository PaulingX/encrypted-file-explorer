package com.example.encryptedexplorer.util;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.function.LongConsumer;

import static com.example.encryptedexplorer.util.EncryptionUtils.*;

/**
 * 使用FileChannel优化加密文件处理速度的工具类
 * 结合FileChannel的高效文件操作和Cipher的加密功能
 */
public class EncryptedFileChannel {
    
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB缓冲区
    
    /**
     * 使用FileChannel加密复制文件
     * @param source 源文件路径
     * @param target 目标文件路径
     * @param password 密码
     * @param onProgress 进度回调
     */
    public static void encryptFile(Path source, Path target, char[] password, LongConsumer onProgress) 
            throws IOException, GeneralSecurityException {
        
        // 生成随机salt和iv
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        
        // 派生密钥
        SecretKey key = deriveKey(password, salt);
        
        // 初始化加密器
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
        
        // 使用FileChannel读取源文件
        try (FileChannel sourceChannel = FileChannel.open(source, StandardOpenOption.READ);
             FileOutputStream fos = new FileOutputStream(target.toFile());
             CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
            
            // 先写入文件头
            fos.write(MAGIC);
            fos.write(salt);
            fos.write(iv);
            
            // 使用ByteBuffer读取文件内容
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            long totalProcessed = 0;
            long fileSize = sourceChannel.size();
            
            while (totalProcessed < fileSize) {
                buffer.clear();
                int bytesRead = sourceChannel.read(buffer);
                if (bytesRead == -1) break;
                
                buffer.flip();
                byte[] data = new byte[bytesRead];
                buffer.get(data);
                
                cos.write(data);
                totalProcessed += bytesRead;
                
                if (onProgress != null) {
                    onProgress.accept(bytesRead);
                }
            }
        }
    }
    
    /**
     * 使用FileChannel解密复制文件
     * @param source 源文件路径（加密文件）
     * @param target 目标文件路径
     * @param password 密码
     * @param onProgress 进度回调
     */
    public static void decryptFile(Path source, Path target, char[] password, LongConsumer onProgress) 
            throws IOException, GeneralSecurityException {
        
        // 先读取文件头
        try (FileInputStream fis = new FileInputStream(source.toFile())) {
            byte[] header = fis.readNBytes(MAGIC.length + SALT_LEN + IV_LEN);
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
            
            // 派生密钥
            SecretKey key = deriveKey(password, salt);
            
            // 初始化解密器
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
            
            // 使用CipherInputStream解密
            try (CipherInputStream cis = new CipherInputStream(fis, cipher);
                 FileChannel targetChannel = FileChannel.open(target, StandardOpenOption.CREATE, 
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                byte[] chunk = new byte[BUFFER_SIZE];
                long totalProcessed = 0;
                
                int bytesRead;
                while ((bytesRead = cis.read(chunk)) != -1) {
                    ByteBuffer dataBuffer = ByteBuffer.wrap(chunk, 0, bytesRead);
                    targetChannel.write(dataBuffer);
                    totalProcessed += bytesRead;
                    
                    if (onProgress != null) {
                        onProgress.accept(bytesRead);
                    }
                }
            }
        }
    }
}
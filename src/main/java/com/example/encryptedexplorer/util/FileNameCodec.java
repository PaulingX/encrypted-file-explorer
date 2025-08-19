package com.example.encryptedexplorer.util;

import java.util.Base64;

/**
 * 文件名编码/解码工具：使用 Base64 URL 安全字母表，去除填充。
 */
public final class FileNameCodec {
	private FileNameCodec() {}

	public static String encodeUrlBase64(byte[] data) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

	public static byte[] decodeUrlBase64(String text) {
		return Base64.getUrlDecoder().decode(text);
	}
} 
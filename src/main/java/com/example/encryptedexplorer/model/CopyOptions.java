package com.example.encryptedexplorer.model;

import java.nio.file.Path;

/**
 * 复制/加解密选项。
 */
public class CopyOptions {
	public Path sourceDirectory;
	public Path targetDirectory;
	public boolean encryptFiles;
	public boolean decryptFiles;
	public boolean encryptDirectoryNames;
	public boolean decryptDirectoryNames;
	public char[] password;

	public void validate() {
		if (sourceDirectory == null || targetDirectory == null) {
			throw new IllegalArgumentException("源目录与目标目录不可为空");
		}
		if (encryptFiles && decryptFiles) {
			throw new IllegalArgumentException("不能同时选择加密与解密文件");
		}
		if (encryptDirectoryNames && decryptDirectoryNames) {
			throw new IllegalArgumentException("不能同时选择加密与解密目录名称");
		}
		if ((encryptFiles || decryptFiles || encryptDirectoryNames || decryptDirectoryNames)
				&& (password == null || password.length == 0)) {
			throw new IllegalArgumentException("开启加/解密时必须提供密码");
		}
	}
} 
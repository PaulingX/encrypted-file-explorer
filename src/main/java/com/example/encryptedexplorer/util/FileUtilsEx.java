package com.example.encryptedexplorer.util;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 文件工具扩展。
 */
public final class FileUtilsEx {
	private FileUtilsEx() {}

	/**
	 * 根据文件大小返回最佳缓冲区大小。
	 * 小(<10MB): 1MB；中(10-100MB): 256KB；大(>100MB): 64KB
	 */
	public static int suggestBufferSize(long fileSizeBytes) {
		long tenMB = 10L * 1024 * 1024;
		long hundredMB = 100L * 1024 * 1024;
		if (fileSizeBytes < tenMB) return 1024 * 1024;
		if (fileSizeBytes < hundredMB) return 256 * 1024;
		return 64 * 1024;
	}

	public static boolean hasEnoughDiskSpace(Path targetDir, long requiredBytes) {
		try {
			File root = targetDir.toFile();
			long free = root.getFreeSpace();
			return free > requiredBytes;
		} catch (Exception e) {
			return true; // 若无法判断，则不阻止
		}
	}

	public static void openWithDesktop(Path file) throws IOException {
		if (!Desktop.isDesktopSupported()) {
			throw new IOException("当前平台不支持 Desktop 打开");
		}
		Desktop.getDesktop().open(file.toFile());
	}

	public static boolean isImageFile(Path path) {
		String name = path.getFileName().toString().toLowerCase();
		return Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".pnm", ".ppm", ".tif", ".tiff")
				.stream().anyMatch(name::endsWith);
	}

	public static long safeSize(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return 0L;
		}
	}
} 
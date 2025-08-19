package com.example.encryptedexplorer.service;

import com.example.encryptedexplorer.util.EncryptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 缩略图缓存，异步加载，避免阻塞 UI。
 */
public class ThumbnailCache {
	private static final Logger LOG = LoggerFactory.getLogger(ThumbnailCache.class);
	private final Map<Path, ImageIcon> cache = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

	public void getThumbnail(Path path, int size, java.util.function.Consumer<ImageIcon> callback) {
		getThumbnail(path, size, false, null, callback);
	}

	/**
	 * 获取缩略图，支持尝试先解密。
	 */
	public void getThumbnail(Path path, int size, boolean tryDecrypt, char[] password, java.util.function.Consumer<ImageIcon> callback) {
		ImageIcon cached = cache.get(path);
		if (cached != null) {
			callback.accept(cached);
			return;
		}
		executor.submit(() -> {
			ImageIcon icon = loadThumbnail(path, size, tryDecrypt, password);
			if (icon != null) {
				cache.put(path, icon);
			}
			SwingUtilities.invokeLater(() -> callback.accept(icon));
		});
	}

	private ImageIcon loadThumbnail(Path path, int size, boolean tryDecrypt, char[] password) {
		try {
			if (!Files.exists(path)) return null;
			BufferedImage img;
			if (tryDecrypt || EncryptionUtils.isEncryptedFileName(path.getFileName().toString())) {
				try (InputStream in = Files.newInputStream(path)) {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					EncryptionUtils.decryptStream(in, bos, password != null ? password : new char[0]);
					byte[] bytes = bos.toByteArray();
					img = ImageIO.read(new ByteArrayInputStream(bytes));
				}
			} else {
				img = ImageIO.read(path.toFile());
			}
			if (img == null) return null;
			Image scaled = img.getScaledInstance(size, size, Image.SCALE_SMOOTH);
			return new ImageIcon(scaled);
		} catch (Exception e) {
			LOG.debug("缩略图生成失败: {} - {}", path, e.toString());
			return null;
		}
	}

	public void shutdown() {
		executor.shutdown();
		try {
			executor.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {}
	}
} 
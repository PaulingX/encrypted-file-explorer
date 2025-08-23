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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 缩略图缓存，异步加载，避免阻塞 UI。
 */
public class ThumbnailCache {
	private static final Logger LOG = LoggerFactory.getLogger(ThumbnailCache.class);
	// LRU 缓存，最多保存 300 个缩略图，自动淘汰最久未使用
	private final Map<Path, ImageIcon> cache = Collections.synchronizedMap(new LinkedHashMap<Path, ImageIcon>(128, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<Path, ImageIcon> eldest) { return size() > 300; }
	});
	// 限制并发加载线程数量，降低内存压力
	private final ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
	private final Semaphore permits = new Semaphore(2);

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
			ImageIcon icon = null;
			try {
				permits.acquire();
				icon = loadThumbnail(path, size, tryDecrypt, password);
			} catch (OutOfMemoryError oom) {
				LOG.warn("内存不足，清理缩略图缓存后重试: {}", oom.toString());
				cache.clear();
				System.gc();
				try {
					icon = loadThumbnail(path, size, tryDecrypt, password);
				} catch (Throwable t) {
					LOG.warn("缩略图重试失败: {}", t.toString());
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			} finally {
				permits.release();
			}
			if (icon != null) {
				cache.put(path, icon);
			}
			final ImageIcon toDeliver = icon;
			SwingUtilities.invokeLater(() -> callback.accept(toDeliver));
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
			// 优化缩略图生成，减少内存占用
			int newWidth = size;
			int newHeight = size;
			BufferedImage scaledImg = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g2d = scaledImg.createGraphics();
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2d.drawImage(img, 0, 0, newWidth, newHeight, null);
			g2d.dispose();
			return new ImageIcon(scaledImg);
		} catch (OutOfMemoryError oom) {
			throw oom;
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
package com.example.encryptedexplorer.service;

import com.example.encryptedexplorer.model.CopyOptions;
import com.example.encryptedexplorer.model.ErrorDecision;
import com.example.encryptedexplorer.model.Resolution;
import com.example.encryptedexplorer.util.EncryptedFileChannel;
import com.example.encryptedexplorer.util.EncryptionUtils;
import com.example.encryptedexplorer.util.FileUtilsEx;
import com.twelvemonkeys.lang.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * 复制服务：支持加密/解密、目录名转换、冲突与错误回调。
 */
public class CopyService {
	private static final Logger LOG = LoggerFactory.getLogger(CopyService.class);

	// 定义使用FileChannel复制的文件大小阈值(10MB)
	private static final long FILE_CHANNEL_THRESHOLD = 10 * 1024 * 1024L;

	public interface Callbacks {
		Resolution onConflict(Path targetPath);
		ErrorDecision onError(Path sourcePath, Exception error);
		void onProgress(String currentFile, long copiedBytes, long totalBytes);
		void onLog(String message);
		boolean isCancelled();
	}

	public void copyDirectory(CopyOptions options, Callbacks callbacks) throws IOException {
		Objects.requireNonNull(options);
		Objects.requireNonNull(callbacks);
		options.validate();

		Path src = options.sourceDirectory;
		Path dst = options.targetDirectory;
		LOG.info("开始复制: {} -> {} (encryptFiles={}, decryptFiles={}, encDir={}, decDir={})",
			src, dst, options.encryptFiles, options.decryptFiles, options.encryptDirectoryNames, options.decryptDirectoryNames);

		// 统计总大小（粗略，用于进度）
		final long totalBytes = Files.walk(src)
				.filter(Files::isRegularFile)
				.mapToLong(FileUtilsEx::safeSize)
				.sum();
		final long[] copied = {0L};

		Files.createDirectories(dst);

		Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (callbacks.isCancelled()) return FileVisitResult.TERMINATE;
				Path rel = src.relativize(dir);
                if (StringUtil.isEmpty(rel.getFileName().toString())) return FileVisitResult.CONTINUE;
				Path targetDir = resolveTargetPath(rel, dst, options, true);
				Files.createDirectories(targetDir);
				LOG.debug("创建目录: {}", targetDir);
				// 写入短名映射（父目录/.name.meta.jpg），用于查看时还原显示
				if (options.encryptDirectoryNames && rel.getNameCount() > 0) {
					String originalName = rel.getFileName().toString();
					String shortName = targetDir.getFileName().toString();
					Path mapFile = targetDir.getParent() != null ? targetDir.getParent().resolve(".name.meta.jpg") : null;
					if (mapFile != null) {
						try {
							Files.createDirectories(mapFile.getParent());
							Files.write(mapFile, (shortName + "=" + originalName + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
									StandardOpenOption.CREATE, StandardOpenOption.APPEND);
						} catch (Exception ex) {
							LOG.warn("写入目录映射失败: {} -> {} 于 {} - {}", shortName, originalName, mapFile, ex.toString());
						}
					}
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (callbacks.isCancelled()) return FileVisitResult.TERMINATE;
				Path rel = src.relativize(file);
				Path targetFile = resolveTargetPath(rel, dst, options, false);
				LOG.debug("处理文件: {} -> {}", file, targetFile);

				// 处理冲突
				if (Files.exists(targetFile)) {
					Resolution res = callbacks.onConflict(targetFile);
					LOG.info("冲突: {} 决策={}", targetFile, res);
					if (res == Resolution.CANCEL) return FileVisitResult.TERMINATE;
					if (res == Resolution.SKIP) {
						callbacks.onLog("跳过已存在文件: " + targetFile);
						return FileVisitResult.CONTINUE;
					}
				}

				// 目标磁盘空间简单检查
				long size = FileUtilsEx.safeSize(file);
				if (!FileUtilsEx.hasEnoughDiskSpace(targetFile.getParent(), size)) {
					ErrorDecision d = callbacks.onError(file, new IOException("磁盘空间可能不足"));
					LOG.warn("磁盘空间可能不足: {} -> {} 决策={}", file, targetFile, d);
					if (d == ErrorDecision.CANCEL) return FileVisitResult.TERMINATE;
					if (d == ErrorDecision.SKIP) return FileVisitResult.CONTINUE;
				}

				// 执行复制（可选加/解密）
				try {
					Files.createDirectories(targetFile.getParent());
					if (options.encryptFiles) {
						LOG.debug("加密复制文件: {}", file);
						// 对于大文件使用FileChannel优化加密复制性能
						if (size > FILE_CHANNEL_THRESHOLD) {
							LOG.debug("使用FileChannel加密复制大文件: {} (大小: {} 字节)", file, size);
							EncryptedFileChannel.encryptFile(file, targetFile, options.password, inc -> {
								copied[0] += inc;
								callbacks.onProgress(file.toString(), copied[0], totalBytes);
								LOG.debug("加密进度: 已处理 {} 字节 (总计: {} 字节)", copied[0], totalBytes);
							});
						} else {
							try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ);
								 OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								EncryptionUtils.encryptStream(in, out, options.password, inc -> {
									copied[0] += inc;
									callbacks.onProgress(file.toString(), copied[0], totalBytes);
									LOG.debug("加密进度: 已处理 {} 字节 (总计: {} 字节)", copied[0], totalBytes);
								});
							}
						}
					} else if (options.decryptFiles) {
						LOG.debug("解密复制文件: {}", file);
						// 对于大文件使用FileChannel优化解密复制性能
						if (size > FILE_CHANNEL_THRESHOLD) {
							LOG.debug("使用FileChannel解密复制大文件: {} (大小: {} 字节)", file, size);
							EncryptedFileChannel.decryptFile(file, targetFile, options.password, inc -> {
								copied[0] += inc;
								callbacks.onProgress(file.toString(), copied[0], totalBytes);
								LOG.debug("解密进度: 已处理 {} 字节 (总计: {} 字节)", copied[0], totalBytes);
							});
						} else {
							try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ);
								 OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								EncryptionUtils.decryptStream(in, out, options.password, inc -> {
									copied[0] += inc;
									callbacks.onProgress(file.toString(), copied[0], totalBytes);
									LOG.debug("解密进度: 已处理 {} 字节 (总计: {} 字节)", copied[0], totalBytes);
								});
							}
						}
					} else {
						// 对于大文件使用FileChannel优化复制性能
						if (size > FILE_CHANNEL_THRESHOLD) {
							LOG.debug("使用FileChannel复制大文件: {} (大小: {} 字节)", file, size);
							copyFileWithFileChannel(file, targetFile, size, copied, totalBytes, callbacks);
						} else {
							// 普通复制
							try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ);
								 OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								// 采用较小的分块，减少内存压力
								byte[] buf = new byte[(int)Math.min(256 * 1024, Math.max(64 * 1024, FileUtilsEx.suggestBufferSize(size)))];
								int r;
								while ((r = in.read(buf)) != -1) {
									out.write(buf, 0, r);
									copied[0] += r;
									callbacks.onProgress(file.toString(), copied[0], totalBytes);
								}
							}
						}
					}

					// 若是加密，重命名添加后缀；若解密，去后缀
					if (options.encryptFiles && !EncryptionUtils.isEncryptedFileName(targetFile.getFileName().toString())) {
						Path renamed = targetFile.resolveSibling(EncryptionUtils.toEncryptedFileName(targetFile.getFileName().toString()));
						Files.move(targetFile, renamed, StandardCopyOption.REPLACE_EXISTING);
						callbacks.onLog("已加密: " + renamed);
						LOG.debug("重命名(加密后缀): {} -> {}", targetFile, renamed);
					} else if (options.decryptFiles && EncryptionUtils.isEncryptedFileName(targetFile.getFileName().toString())) {
						Path renamed = targetFile.resolveSibling(EncryptionUtils.toDecryptedFileName(targetFile.getFileName().toString()));
						Files.move(targetFile, renamed, StandardCopyOption.REPLACE_EXISTING);
						callbacks.onLog("已解密: " + renamed);
						LOG.debug("重命名(去后缀): {} -> {}", targetFile, renamed);
					}

				} catch (GeneralSecurityException gse) {
					ErrorDecision d = callbacks.onError(file, gse);
					LOG.warn("安全错误: {} - {} 决策={}", file, gse.toString(), d);
					if (d == ErrorDecision.CANCEL) return FileVisitResult.TERMINATE;
					if (d == ErrorDecision.SKIP) return FileVisitResult.CONTINUE;
					return visitFile(file, attrs); // RETRY
				} catch (IOException ioe) {
					ErrorDecision d = callbacks.onError(file, ioe);
					LOG.warn("IO 错误: {} - {} 决策={}", file, ioe.toString(), d);
					if (d == ErrorDecision.CANCEL) return FileVisitResult.TERMINATE;
					if (d == ErrorDecision.SKIP) return FileVisitResult.CONTINUE;
					return visitFile(file, attrs); // RETRY
				}
				return FileVisitResult.CONTINUE;
			}
		});

		LOG.info("复制完成: {} -> {}", src, dst);
	}

	/**
	 * 使用FileChannel复制大文件以提高性能
	 * 利用操作系统的零拷贝功能减少数据复制次数
	 */
	private void copyFileWithFileChannel(Path source, Path target, long fileSize, long[] copied, long totalBytes, Callbacks callbacks) throws IOException {
		try (FileChannel sourceChannel = FileChannel.open(source, StandardOpenOption.READ);
			 FileChannel targetChannel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

			long position = 0;
			long count = fileSize;
			long transferred;

			// 使用transferTo进行高效复制，利用系统级零拷贝优化
			while (count > 0) {
				transferred = sourceChannel.transferTo(position, count, targetChannel);
				position += transferred;
				count -= transferred;
				copied[0] += transferred;
				callbacks.onProgress(source.toString(), copied[0], totalBytes);

				// 检查是否被取消
				if (callbacks.isCancelled()) {
					throw new IOException("复制被用户取消");
				}
			}
		}
	}

	private Path resolveTargetPath(Path relative, Path rootTarget, CopyOptions options, boolean isDirectory) {
		Path current = rootTarget;
		int nameCount = relative.getNameCount();
		for (int i = 0; i < nameCount; i++) {
			Path part = relative.getName(i);
			String name = part.toString();
			boolean thisIsDirectorySegment = (i < nameCount - 1) || isDirectory; // 中间段必为目录；最后一段依据 isDirectory
			if (thisIsDirectorySegment) {
				if (options.encryptDirectoryNames) {
					try {
						name = EncryptionUtils.encryptDirectoryNameShort(name, options.password, 16);
					} catch (Exception ignored) {}
				} else if (options.decryptDirectoryNames) {
					// 短名无法直接还原，需要外部映射，暂不在复制中做短名解密
				}
			}
			current = current.resolve(name);
		}
		return current;
	}
}
package com.example.encryptedexplorer.ui;

import com.example.encryptedexplorer.service.ThumbnailCache;
import com.example.encryptedexplorer.util.EncryptionUtils;
import com.example.encryptedexplorer.util.FileUtilsEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JViewport;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * “查看”选项卡：浏览文件夹，展示缩略图，支持解密查看。
 */
public class ViewPanel extends JPanel {
	private static final Logger LOG = LoggerFactory.getLogger(ViewPanel.class);
	private final JTextField folderField = new JTextField();
	private final JPasswordField passwordField = new JPasswordField();
	private final JCheckBox decryptFiles = new JCheckBox("解密文件");
	private final JCheckBox includeSubdirs = new JCheckBox("包含子文件夹");
	private volatile boolean directoryTransformEnabled = false; // 菜单总开关（不再作为还原显示的必要条件）
	private final JButton chooseButton = new JButton("选择文件夹");
	private final JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
	private final JScrollPane scrollPane = new JScrollPane(grid);
	private final ThumbnailCache thumbnailCache = new ThumbnailCache();

	private Path currentFolder = null;

	// 流式分页
	private DirectoryStream<Path> dirStream = null;
	private Iterator<Path> dirIter = null;
	private Stream<Path> walkStream = null;
	private Iterator<Path> walkIter = null;
	private int loadedCount = 0;
	private static final int PAGE_SIZE = 50;
	private volatile boolean isLoading = false;
	private volatile boolean endOfEntries = false;

	// 短名映射缓存（父目录 -> (短名->原名)） LRU 256
	private final Map<Path, Map<String, String>> dirMapCache = new LinkedHashMap<Path, Map<String,String>>(64, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<Path, Map<String, String>> eldest) { return size() > 256; }
	};

	public ViewPanel() {
		setLayout(new BorderLayout(10, 10));
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel top = new JPanel(new GridBagLayout());
		GridBagConstraints gc = new GridBagConstraints();
		gc.insets = new Insets(4, 4, 4, 4);
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.gridx = 0; gc.gridy = 0; top.add(new JLabel("当前文件夹:"), gc);
		gc.gridx = 1; gc.weightx = 1; top.add(folderField, gc);
		gc.gridx = 2; gc.weightx = 0; top.add(chooseButton, gc);
		gc.gridx = 0; gc.gridy = 1; top.add(new JLabel("密码:"), gc);
		gc.gridx = 1; gc.weightx = 1; top.add(passwordField, gc);
		gc.gridx = 2; gc.weightx = 0; JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); right.add(decryptFiles); right.add(includeSubdirs); top.add(right, gc);
		add(top, BorderLayout.NORTH);

		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(24);
		add(scrollPane, BorderLayout.CENTER);

		scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> maybeLoadMore());
		scrollPane.addMouseWheelListener(e -> { if (e.getWheelRotation() > 0) maybeLoadMore(); });

		chooseButton.addActionListener(e -> chooseFolder());
		decryptFiles.addActionListener(e -> { if (currentFolder != null) openFolder(currentFolder); });
		includeSubdirs.addActionListener(e -> { if (currentFolder != null) openFolder(currentFolder); });
	}

	public void setDirectoryTransformEnabled(boolean enabled) {
		this.directoryTransformEnabled = enabled;
		LOG.info("查看页-目录名加/解密总开关(仅用于菜单状态): {}", enabled ? "开启" : "关闭");
		if (currentFolder != null) openFolder(currentFolder);
	}

	private void closeStream() {
		try { if (dirStream != null) dirStream.close(); } catch (IOException ignored) {}
		dirStream = null; dirIter = null;
		try { if (walkStream != null) walkStream.close(); } catch (Exception ignored) {}
		walkStream = null; walkIter = null;
	}

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			Path folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
			openFolder(folder);
		}
	}

	private Map<String,String> getShortNameMapFor(Path parentDir) {
		if (parentDir == null) return Collections.emptyMap();
		Map<String,String> m = dirMapCache.get(parentDir);
		if (m != null) return m;
		Path mapFile = parentDir.resolve(".dirnames.map");
		Map<String,String> result = new HashMap<>();
		if (Files.isRegularFile(mapFile)) {
			try {
				for (String line : Files.readAllLines(mapFile, StandardCharsets.UTF_8)) {
					int eq = line.indexOf('=');
					if (eq > 0) {
						String k = line.substring(0, eq).trim();
						String v = line.substring(eq + 1).trim();
						if (!k.isEmpty() && !v.isEmpty()) result.put(k, v);
					}
				}
				LOG.debug("载入目录映射: {} 条 at {}", result.size(), mapFile);
			} catch (Exception ex) {
				LOG.warn("读取目录映射失败: {} - {}", mapFile, ex.toString());
			}
		}
		dirMapCache.put(parentDir, result);
		return result;
	}

	private void openFolder(Path folder) {
		closeStream();
		currentFolder = folder;
		folderField.setText(folder.toString());
		LOG.info("打开文件夹: {}，包含子文件夹= {}", folder, includeSubdirs.isSelected());
		try {
			if (includeSubdirs.isSelected()) {
				walkStream = Files.walk(currentFolder);
				walkIter = walkStream.iterator();
			} else {
				dirStream = Files.newDirectoryStream(currentFolder);
				dirIter = dirStream.iterator();
			}
			endOfEntries = false;
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "读取目录失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
			LOG.warn("读取目录失败: {} - {}", currentFolder, e.toString());
			return;
		}
		resetAndLoadFirstPage();
	}

	private void resetAndLoadFirstPage() {
		grid.removeAll();
		loadedCount = 0;
		// 向上一级
		if (currentFolder.getParent() != null) {
			JPanel up = new JPanel(new BorderLayout());
			up.setPreferredSize(new Dimension(140, 140));
			JLabel label = new JLabel("..", UIManager.getIcon("FileChooser.upFolderIcon"), SwingConstants.CENTER);
			label.setToolTipText(currentFolder.getParent().toString());
			label.setHorizontalTextPosition(SwingConstants.CENTER);
			label.setVerticalTextPosition(SwingConstants.BOTTOM);
			label.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openFolder(currentFolder.getParent()); } });
			up.add(label, BorderLayout.CENTER);
			grid.add(up);
		}
		appendNextPage();
	}

	private void maybeLoadMore() {
		if (isLoading || endOfEntries) return;
		JScrollBar v = scrollPane.getVerticalScrollBar();
		int value = v.getValue();
		int extent = v.getVisibleAmount();
		int max = v.getMaximum();
		if (value + extent >= max - 48) appendNextPage();
	}

	private boolean hasNextEntry() { return includeSubdirs.isSelected() ? (walkIter != null && walkIter.hasNext()) : (dirIter != null && dirIter.hasNext()); }
	private Path nextEntry() { return includeSubdirs.isSelected() ? walkIter.next() : dirIter.next(); }

	private void appendNextPage() {
		isLoading = true;
		int appended = 0;
		long t0 = System.currentTimeMillis();
		while (appended < PAGE_SIZE && hasNextEntry()) {
			Path p = nextEntry();
			if (includeSubdirs.isSelected() && currentFolder.equals(p)) continue; // 跳过根本身
			addEntryCell(p);
			appended++;
		}
		if (!hasNextEntry()) endOfEntries = true;
		loadedCount += appended;
		isLoading = false;
		revalidate();
		repaint();
		LOG.info("加载页面: +{} 项, 总已加载={}，耗时={}ms, 目录={}", appended, loadedCount, (System.currentTimeMillis() - t0), currentFolder);
	}

	private void addEntryCell(Path p) {
		JPanel cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(140, 140));
		String displayName = p.getFileName() != null ? p.getFileName().toString() : p.toString();
		try {
			if (Files.isDirectory(p) && decryptFiles.isSelected()) {
				// 1) 优先使用父目录的短名映射
				Map<String,String> parentMap = getShortNameMapFor(p.getParent());
				String mapped = parentMap.get(displayName);
				if (mapped != null && !mapped.isEmpty()) {
					displayName = mapped;
				} else {
					// 2) 尝试基于密码的可逆解密（兼容旧版非短名加密）
					displayName = EncryptionUtils.decryptFileName(displayName, passwordField.getPassword());
				}
			}
		} catch (Exception ignored) {}
		JLabel label = new JLabel(displayName, SwingConstants.CENTER);
		label.setHorizontalTextPosition(SwingConstants.CENTER);
		label.setVerticalTextPosition(SwingConstants.BOTTOM);
		label.setIconTextGap(6);
		label.setToolTipText(p.toString());

		boolean isDir = Files.isDirectory(p);
		boolean isEncrypted = !isDir && EncryptionUtils.isEncryptedFileName(displayName);
		boolean isImageByExt = !isDir && FileUtilsEx.isImageFile(p);
		if (isDir) {
			label.setIcon(UIManager.getIcon("FileView.directoryIcon"));
			label.addMouseListener(new MouseAdapter() {
				@Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) enterDirectory(p); }
			});
		} else if (isImageByExt || isEncrypted) {
			thumbnailCache.getThumbnail(p, 96, decryptFiles.isSelected() || isEncrypted, passwordField.getPassword(), icon -> label.setIcon(icon != null ? icon : UIManager.getIcon("FileView.fileIcon")));
			label.addMouseListener(new MouseAdapter() {
				@Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openImageViewer(p); }
			});
		} else {
			label.setIcon(UIManager.getIcon("FileView.fileIcon"));
			label.addMouseListener(new MouseAdapter() {
				@Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openFile(p); }
			});
		}
		cell.add(label, BorderLayout.CENTER);
		grid.add(cell);
	}

	private void enterDirectory(Path dir) { openFolder(dir); }

	private void openImageViewer(Path file) {
		try {
			java.util.List<Path> list = listCandidateImages(currentFolder);
			int idx = list.indexOf(file);
			if (idx < 0) idx = 0;
			ImageViewerDialog dlg = new ImageViewerDialog(SwingUtilities.getWindowAncestor(this), list, idx, decryptFiles.isSelected(), passwordField.getPassword());
			dlg.setVisible(true);
		} catch (Exception e) {
			LOG.warn("打开图片查看器失败: {} - {}", file, e.toString());
			JOptionPane.showMessageDialog(this, "打开图片失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
		}
	}

	private java.util.List<Path> listCandidateImages(Path folder) throws IOException {
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
			java.util.List<Path> all = new java.util.ArrayList<>();
			for (Path p : ds) {
				if (Files.isDirectory(p)) continue;
				if (FileUtilsEx.isImageFile(p) || EncryptionUtils.isEncryptedFileName(p.getFileName().toString())) {
					all.add(p);
				}
			}
			return all.stream().sorted().collect(Collectors.toList());
		}
	}

	private void openFile(Path file) {
		try {
			if (decryptFiles.isSelected() || EncryptionUtils.isEncryptedFileName(file.getFileName().toString())) {
				Path tmp = Files.createTempFile("dec_", "_preview");
				try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(tmp)) {
					EncryptionUtils.decryptStream(in, out, passwordField.getPassword());
				}
				LOG.info("临时解密并打开文件: {} -> {}", file, tmp);
				FileUtilsEx.openWithDesktop(tmp);
			} else {
				FileUtilsEx.openWithDesktop(file);
			}
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, "打开文件失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
			LOG.warn("打开文件失败: {} - {}", file, e.toString());
		}
	}

	/**
	 * 简易自动换行布局（从 Oracle 示例改造）。
	 */
	static class WrapLayout extends FlowLayout {
		public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
		@Override public Dimension preferredLayoutSize(Container target) { return layoutSize(target, true); }
		@Override public Dimension minimumLayoutSize(Container target) { return layoutSize(target, false); }
		private Dimension layoutSize(Container target, boolean preferred) {
			int availableWidth = target.getWidth();
			if (availableWidth <= 0) {
				JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, target);
				if (viewport != null) {
					availableWidth = viewport.getWidth();
				}
			}
			if (availableWidth <= 0) {
				Container parent = target.getParent();
				if (parent != null) availableWidth = parent.getWidth();
			}
			if (availableWidth <= 0) availableWidth = 800; // 合理默认值，避免首帧不换行

			int hgap = getHgap();
			int vgap = getVgap();
			Insets insets = target.getInsets();
			int maxWidth = Math.max(100, availableWidth - (insets.left + insets.right + hgap * 2));
			int x = 0, y = insets.top + vgap, rowHeight = 0;
			for (Component comp : target.getComponents()) {
				Dimension d = preferred ? comp.getPreferredSize() : comp.getMinimumSize();
				if (x > 0 && x + d.width > maxWidth) {
					x = 0; y += rowHeight + vgap; rowHeight = 0;
				}
				x += d.width + hgap;
				rowHeight = Math.max(rowHeight, d.height);
			}
			y += rowHeight + vgap + target.getInsets().bottom;
			return new Dimension(availableWidth, y);
		}
	}
} 
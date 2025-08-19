package com.example.encryptedexplorer.ui;

import com.example.encryptedexplorer.service.ThumbnailCache;
import com.example.encryptedexplorer.util.EncryptionUtils;
import com.example.encryptedexplorer.util.FileUtilsEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * “查看”选项卡：浏览文件夹，展示缩略图，支持解密查看。
 */
public class ViewPanel extends JPanel {
	private static final Logger LOG = LoggerFactory.getLogger(ViewPanel.class);
	private final JTextField folderField = new JTextField();
	private final JPasswordField passwordField = new JPasswordField();
	private final JCheckBox decryptFiles = new JCheckBox("解密文件");
	private volatile boolean directoryTransformEnabled = false; // 菜单总开关
	private final JButton chooseButton = new JButton("选择文件夹");

	private final JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
	private final JScrollPane scrollPane = new JScrollPane(grid);
	private final ThumbnailCache thumbnailCache = new ThumbnailCache();

	private Path currentFolder = null;

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
		gc.gridx = 2; gc.weightx = 0; JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); right.add(decryptFiles); top.add(right, gc);
		add(top, BorderLayout.NORTH);

		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(24);
		add(scrollPane, BorderLayout.CENTER);

		chooseButton.addActionListener(e -> chooseFolder());
		decryptFiles.addActionListener(e -> refreshGrid());
	}

	public void setDirectoryTransformEnabled(boolean enabled) {
		this.directoryTransformEnabled = enabled;
		LOG.info("查看页-目录名加/解密总开关: {}", enabled ? "开启" : "关闭");
		refreshGrid();
	}

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			Path folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
			openFolder(folder);
		}
	}

	private void openFolder(Path folder) {
		currentFolder = folder;
		folderField.setText(folder.toString());
		LOG.info("打开文件夹: {}", folder);
		refreshGrid();
	}

	private void refreshGrid() {
		grid.removeAll();
		if (currentFolder == null) {
			revalidate(); repaint(); return;
		}

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

		List<Path> entries = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(currentFolder)) {
			for (Path p : ds) entries.add(p);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "读取目录失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
			LOG.warn("读取目录失败: {} - {}", currentFolder, e.toString());
		}

		for (int i = 0; i < entries.size(); i++) {
			Path p = entries.get(i);
			JPanel cell = new JPanel(new BorderLayout());
			cell.setPreferredSize(new Dimension(140, 140));
			String displayName = p.getFileName().toString();
			// 规则：当菜单开启 且 勾选解密文件 时，目录名显示为解密后的名称
			if (Files.isDirectory(p) && directoryTransformEnabled && decryptFiles.isSelected()) {
				try { displayName = EncryptionUtils.decryptFileName(displayName, passwordField.getPassword()); } catch (Exception ignored) {}
			}
			JLabel label = new JLabel(displayName, SwingConstants.CENTER);
			label.setHorizontalTextPosition(SwingConstants.CENTER);
			label.setVerticalTextPosition(SwingConstants.BOTTOM);
			label.setIconTextGap(6);
			label.setToolTipText(p.toString());

			boolean isEncrypted = EncryptionUtils.isEncryptedFileName(p.getFileName().toString());
			boolean isImageByExt = FileUtilsEx.isImageFile(p);
			if (Files.isDirectory(p)) {
				label.setIcon(UIManager.getIcon("FileView.directoryIcon"));
				label.addMouseListener(new MouseAdapter() {
					@Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) enterDirectory(p); }
				});
			} else if (isImageByExt || isEncrypted) {
				// 规则：勾选解密文件时，缩略图用解密方式刷新；否则直接读取
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

		revalidate();
		repaint();
	}

	private void openImageViewer(Path file) {
		try {
			List<Path> list = listCandidateImages(currentFolder);
			int idx = list.indexOf(file);
			if (idx < 0) idx = 0;
			ImageViewerDialog dlg = new ImageViewerDialog(SwingUtilities.getWindowAncestor(this), list, idx, decryptFiles.isSelected(), passwordField.getPassword());
			dlg.setVisible(true);
		} catch (Exception e) {
			LOG.warn("打开图片查看器失败: {} - {}", file, e.toString());
			JOptionPane.showMessageDialog(this, "打开图片失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
		}
	}

	private List<Path> listCandidateImages(Path folder) throws IOException {
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
			List<Path> all = new ArrayList<>();
			for (Path p : ds) {
				if (Files.isDirectory(p)) continue;
				if (FileUtilsEx.isImageFile(p) || EncryptionUtils.isEncryptedFileName(p.getFileName().toString())) {
					all.add(p);
				}
			}
			return all.stream().sorted().collect(Collectors.toList());
		}
	}

	private void enterDirectory(Path dir) {
		openFolder(dir);
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
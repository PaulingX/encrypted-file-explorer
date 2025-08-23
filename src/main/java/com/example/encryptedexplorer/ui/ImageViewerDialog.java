package com.example.encryptedexplorer.ui;

import com.example.encryptedexplorer.util.EncryptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.List;

/**
 * 图片查看窗口：支持上一张/下一张、缩放与自适应、滚轮缩放/滚动与翻页，支持左键拖拽平移。
 */
public class ImageViewerDialog extends JFrame {
	private static final Logger LOG = LoggerFactory.getLogger(ImageViewerDialog.class);
	private final List<Path> images;
	private int index;
	private final boolean tryDecrypt;
	private final char[] password;

	private final JLabel imageLabel = new JLabel();
	private final JScrollPane scroll = new JScrollPane(imageLabel);
	private double zoom = 1.0;
	private BufferedImage currentImage;
	private Point dragStartLabelPoint = null;
	private Point dragStartViewPos = null;

	public ImageViewerDialog(Window owner, List<Path> images, int startIndex, boolean tryDecrypt, char[] password) {
		super("图片查看");
		this.images = images;
		this.index = startIndex;
		this.tryDecrypt = tryDecrypt;
		this.password = password;

		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setResizable(true);

		imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
		imageLabel.setVerticalAlignment(SwingConstants.CENTER);

		JButton prev = new JButton("上一张");
		JButton next = new JButton("下一张");
		JButton actual = new JButton("100%");
		JButton zoomIn = new JButton("+");
		JButton zoomOut = new JButton("-");

		setLayout(new BorderLayout());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getVerticalScrollBar().setUnitIncrement(32);
		add(scroll, BorderLayout.CENTER);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
		controls.add(prev);
		controls.add(next);
		controls.add(actual);
		controls.add(zoomOut);
		controls.add(zoomIn);
		add(controls, BorderLayout.SOUTH);

		setSize(1000, 800);
		setLocationRelativeTo(owner);

		prev.addActionListener(e -> { navigate(1 * -1); });
		next.addActionListener(e -> { navigate(1); });
		actual.addActionListener(e -> { setZoom(1.0); });
		zoomIn.addActionListener(e -> { setZoom(zoom * 1.25); });
		zoomOut.addActionListener(e -> { setZoom(zoom / 1.25); });

		imageLabel.addMouseWheelListener(this::onMouseWheel);

		// 左键双击自适应
		imageLabel.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
					fitToWindow();
				}
			}
			@Override public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					dragStartLabelPoint = e.getPoint();
					JViewport vp = scroll.getViewport();
					dragStartViewPos = vp.getViewPosition();
				}
			}
		});
		imageLabel.addMouseMotionListener(new MouseAdapter() {
			@Override public void mouseDragged(MouseEvent e) {
				if (dragStartLabelPoint != null && SwingUtilities.isLeftMouseButton(e)) {
					int dx = e.getX() - dragStartLabelPoint.x;
					int dy = e.getY() - dragStartLabelPoint.y;
					JViewport vp = scroll.getViewport();
					Point newPos = new Point(
						Math.max(0, dragStartViewPos.x - dx),
						Math.max(0, dragStartViewPos.y - dy)
					);
					// 约束在视图范围内
					Dimension viewSize = vp.getView().getPreferredSize();
					Dimension extent = vp.getExtentSize();
					newPos.x = Math.min(newPos.x, Math.max(0, viewSize.width - extent.width));
					newPos.y = Math.min(newPos.y, Math.max(0, viewSize.height - extent.height));
					vp.setViewPosition(newPos);
				}
			}
		});

		// 自动自适应：仅窗口尺寸变化时（不在视口尺寸变化时），避免放大后被重新适配变小
		addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) { fitToWindow(); }
		});

		addWindowListener(new WindowAdapter() {
			@Override public void windowOpened(WindowEvent e) { fitToWindow(); }
		});

		loadAndShow();
	}

	private void onMouseWheel(MouseWheelEvent e) {
		if (e.isControlDown()) {
			if (e.getWheelRotation() < 0) setZoom(zoom * 1.1); else setZoom(zoom / 1.1);
		} else {
			JScrollBar vbar = scroll.getVerticalScrollBar();
			boolean atTop = vbar.getValue() == vbar.getMinimum();
			boolean atBottom = vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum();
			if (e.getWheelRotation() < 0 && atTop) { navigate(-1); }
			else if (e.getWheelRotation() > 0 && atBottom) { navigate(1); }
		}
	}

	private void navigate(int delta) {
		int newIndex = index + delta;
		if (newIndex < 0 || newIndex >= images.size()) return;
		index = newIndex;
		loadAndShow();
	}

	private void fitToWindow() {
		if (currentImage == null) return;
		Dimension viewport = scroll.getViewport().getExtentSize();
		if (viewport.width <= 0 || viewport.height <= 0) return;
		double zx = (double) viewport.width / currentImage.getWidth();
		double zy = (double) viewport.height / currentImage.getHeight();
		setZoom(Math.max(0.05, Math.min(zx, zy)));
	}

	private void setZoom(double z) {
		zoom = Math.max(0.05, Math.min(z, 20));
		updateImageLabel();
	}

	private void loadAndShow() {
		Path path = images.get(index);
		setTitle(String.format("图片查看 (%d/%d): %s", index + 1, images.size(), path.getFileName()));
		try {
			currentImage = loadImage(path);
			fitToWindow();
		} catch (Exception e) {
			LOG.warn("打开图片失败: {} - {}", path, e.toString());
			JOptionPane.showMessageDialog(this, "打开图片失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void updateImageLabel() {
		if (currentImage == null) {
			imageLabel.setIcon(null);
			return;
		}
		int w = (int) Math.max(1, Math.round(currentImage.getWidth() * zoom));
		int h = (int) Math.max(1, Math.round(currentImage.getHeight() * zoom));
		Image scaled = currentImage.getScaledInstance(w, h, Image.SCALE_SMOOTH);
		imageLabel.setIcon(new ImageIcon(scaled));
		imageLabel.setPreferredSize(new Dimension(w, h));
		imageLabel.revalidate();
		imageLabel.repaint();
	}

	private BufferedImage loadImage(Path file) throws Exception {
		if (tryDecrypt || EncryptionUtils.isEncryptedFileName(file.getFileName().toString())) {
			try (InputStream in = Files.newInputStream(file)) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				EncryptionUtils.decryptStream(in, bos, password);
				byte[] bytes = bos.toByteArray();
				// 使用 ImageIO 的异步加载方式
				ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
				Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
				if (!readers.hasNext()) throw new GeneralSecurityException("解密后不是有效图片");
				ImageReader reader = readers.next();
				try {
					reader.setInput(iis);
					BufferedImage img = reader.read(0);
					if (img == null) throw new GeneralSecurityException("解密后不是有效图片");
					return img;
				} finally {
					reader.dispose();
					iis.close();
				}
			}
		} else {
			// 使用 ImageIO 的异步加载方式
			ImageInputStream iis = ImageIO.createImageInputStream(file.toFile());
			Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
			if (!readers.hasNext()) throw new IllegalArgumentException("不是有效图片文件");
			ImageReader reader = readers.next();
			try {
				reader.setInput(iis);
				BufferedImage img = reader.read(0);
				if (img == null) throw new IllegalArgumentException("不是有效图片文件");
				return img;
			} finally {
				reader.dispose();
				iis.close();
			}
		}
	}
} 
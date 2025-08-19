package com.example.encryptedexplorer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用程序入口。
 * 负责设置外观样式并启动主窗口。
 */
public class Main {
	private static final Logger LOG = LoggerFactory.getLogger(Main.class);
	public static void main(String[] args) {
		// 尝试设置系统原生外观，提升观感
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception ignored) {
			// 忽略外观设置异常，使用默认外观
		}

		// 扫描 ImageIO 插件，启用 WEBP 支持
		ImageIO.scanForPlugins();
		LOG.info("启动 应用: ImageIO 插件已加载");

		SwingUtilities.invokeLater(() -> {
			MainFrame frame = new MainFrame();
			frame.setVisible(true);
		});
	}
} 
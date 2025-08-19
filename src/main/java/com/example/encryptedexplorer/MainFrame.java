package com.example.encryptedexplorer;

import com.example.encryptedexplorer.ui.CopyPanel;
import com.example.encryptedexplorer.ui.ViewPanel;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主窗口：包含“文件复制”和“查看”两个选项卡，以及菜单“加密/解密”。
 */
public class MainFrame extends JFrame {
	private static final Logger LOG = LoggerFactory.getLogger(MainFrame.class);
	private final JTabbedPane tabbedPane;
	private final CopyPanel copyPanel;
	private final ViewPanel viewPanel;
	private boolean dirTransformEnabled = false;

	public MainFrame() {
		super("文件夹复制查看工具");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1000, 700);
		setLocationRelativeTo(null);

		this.copyPanel = new CopyPanel();
		this.viewPanel = new ViewPanel();
		this.tabbedPane = new JTabbedPane();
		tabbedPane.addTab("文件复制", copyPanel);
		tabbedPane.addTab("查看", viewPanel);

		setLayout(new BorderLayout());
		add(tabbedPane, BorderLayout.CENTER);

		setJMenuBar(createMenuBar());
	}

	private JMenuBar createMenuBar() {
		JMenuBar menuBar = new JMenuBar();
		JMenu encMenu = new JMenu("加密/解密");

		JCheckBoxMenuItem toggleDirTransform = new JCheckBoxMenuItem("加解密目录");
		toggleDirTransform.addActionListener(e -> {
			dirTransformEnabled = toggleDirTransform.isSelected();
			LOG.info("切换 菜单[加解密目录]: {}", dirTransformEnabled ? "开启" : "关闭");
			copyPanel.setDirectoryTransformEnabled(dirTransformEnabled);
			viewPanel.setDirectoryTransformEnabled(dirTransformEnabled);
		});

		encMenu.add(toggleDirTransform);
		menuBar.add(encMenu);
		return menuBar;
	}
} 
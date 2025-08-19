package com.example.encryptedexplorer.ui;

import com.example.encryptedexplorer.model.CopyOptions;
import com.example.encryptedexplorer.model.ErrorDecision;
import com.example.encryptedexplorer.model.Resolution;
import com.example.encryptedexplorer.service.CopyService;
import com.example.encryptedexplorer.ui.dialog.ConflictResolutionDialog;
import com.example.encryptedexplorer.ui.dialog.ErrorHandlingDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * “文件复制”选项卡面板。
 */
public class CopyPanel extends JPanel {
	private static final Logger LOG = LoggerFactory.getLogger(CopyPanel.class);
	private final JTextField sourceField = new JTextField();
	private final JTextField targetField = new JTextField();
	private final JCheckBox encryptFiles = new JCheckBox("加密文件");
	private final JCheckBox decryptFiles = new JCheckBox("解密文件");
	private final JPasswordField passwordField = new JPasswordField();
	private final JButton startButton = new JButton("开始复制");
	private final JButton cancelButton = new JButton("取消");
	private final JProgressBar progressBar = new JProgressBar();
	private final JLabel currentLabel = new JLabel("当前文件: -");
	private final JTextArea logArea = new JTextArea();

	// 由主菜单控制：是否对目录名进行加/解密（具体加密或解密取决于文件加/解密勾选）
	private volatile boolean directoryTransformEnabled = false;
	private volatile boolean cancelRequested = false;

	public CopyPanel() {
		setLayout(new BorderLayout(10, 10));
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel top = new JPanel(new GridBagLayout());
		GridBagConstraints gc = new GridBagConstraints();
		gc.insets = new Insets(4, 4, 4, 4);
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.gridx = 0; gc.gridy = 0; top.add(new JLabel("源文件夹:"), gc);
		gc.gridx = 1; gc.weightx = 1; top.add(sourceField, gc);
		gc.gridx = 2; gc.weightx = 0; JButton browseSrc = new JButton("浏览..."); top.add(browseSrc, gc);
		gc.gridx = 0; gc.gridy = 1; top.add(new JLabel("目标文件夹:"), gc);
		gc.gridx = 1; gc.weightx = 1; top.add(targetField, gc);
		gc.gridx = 2; gc.weightx = 0; JButton browseDst = new JButton("浏览..."); top.add(browseDst, gc);
		gc.gridx = 0; gc.gridy = 2; top.add(new JLabel("密码:"), gc);
		gc.gridx = 1; gc.weightx = 1; top.add(passwordField, gc);
		gc.gridx = 2; gc.weightx = 0; JPanel encPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); encPanel.add(encryptFiles); encPanel.add(Box.createHorizontalStrut(8)); encPanel.add(decryptFiles); top.add(encPanel, gc);

		add(top, BorderLayout.NORTH);

		JPanel center = new JPanel(new BorderLayout(6, 6));
		progressBar.setStringPainted(true);
		center.add(progressBar, BorderLayout.NORTH);
		center.add(new JScrollPane(logArea), BorderLayout.CENTER);
		logArea.setEditable(false);
		add(center, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		bottom.add(currentLabel);
		bottom.add(startButton);
		bottom.add(cancelButton);
		add(bottom, BorderLayout.SOUTH);

		browseSrc.addActionListener(e -> chooseDir(sourceField));
		browseDst.addActionListener(e -> chooseDir(targetField));
		startButton.addActionListener(e -> startCopy());
		cancelButton.addActionListener(e -> { cancelRequested = true; LOG.info("用户请求取消"); });

		ButtonGroup group = new ButtonGroup();
		group.add(encryptFiles);
		group.add(decryptFiles);
	}

	public void setDirectoryTransformEnabled(boolean enabled) {
		this.directoryTransformEnabled = enabled;
		LOG.info("复制页-目录名加/解密总开关: {}", enabled ? "开启" : "关闭");
	}

	private void chooseDir(JTextField target) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			target.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void startCopy() {
		cancelRequested = false;
		CopyOptions options = new CopyOptions();
		options.sourceDirectory = Paths.get(sourceField.getText()).toAbsolutePath().normalize();
		options.targetDirectory = Paths.get(targetField.getText()).toAbsolutePath().normalize();
		options.encryptFiles = encryptFiles.isSelected();
		options.decryptFiles = decryptFiles.isSelected();
		// 规则：菜单开启时，若选“加密文件”则目录名加密；若选“解密文件”则目录名解密
		options.encryptDirectoryNames = directoryTransformEnabled && encryptFiles.isSelected();
		options.decryptDirectoryNames = directoryTransformEnabled && decryptFiles.isSelected();
		options.password = passwordField.getPassword();

		try {
			options.validate();
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, ex.getMessage(), "参数错误", JOptionPane.ERROR_MESSAGE);
			LOG.warn("参数错误: {}", ex.getMessage());
			return;
		}

		startButton.setEnabled(false);
		progressBar.setValue(0);
		logArea.setText("");
		LOG.info("开始复制: src={}, dst={}, encFiles={}, decFiles={}, dirTransform={}, encDir={}, decDir={}",
				options.sourceDirectory, options.targetDirectory, options.encryptFiles, options.decryptFiles,
				directoryTransformEnabled, options.encryptDirectoryNames, options.decryptDirectoryNames);

		CopyService service = new CopyService();
		SwingWorker<Void, Void> worker = new SwingWorker<>() {
			private boolean conflictApplyAll = false;
			private Resolution lastConflictChoice = Resolution.REPLACE;
			private boolean errorApplyAll = false;
			private ErrorDecision lastErrorChoice = ErrorDecision.SKIP;

			@Override
			protected Void doInBackground() {
				try {
					service.copyDirectory(options, new CopyService.Callbacks() {
						@Override
						public Resolution onConflict(Path targetPath) {
							if (conflictApplyAll) return lastConflictChoice;
							ConflictResolutionDialog dlg = new ConflictResolutionDialog(SwingUtilities.getWindowAncestor(CopyPanel.this),
									"目标文件已存在：" + targetPath + "\n请选择操作");
							dlg.setVisible(true);
							conflictApplyAll = dlg.isApplyToAll();
							lastConflictChoice = dlg.getResolution();
							LOG.info("冲突决策: path={}, applyAll={}, decision={}", targetPath, conflictApplyAll, lastConflictChoice);
							return lastConflictChoice;
						}

						@Override
						public ErrorDecision onError(Path sourcePath, Exception error) {
							if (errorApplyAll) return lastErrorChoice;
							String msg = "处理文件时出错：" + sourcePath + "\n" + error.getClass().getSimpleName() + ": " + error.getMessage();
							ErrorHandlingDialog dlg = new ErrorHandlingDialog(SwingUtilities.getWindowAncestor(CopyPanel.this), msg);
							dlg.setVisible(true);
							errorApplyAll = dlg.isApplyToAll();
							lastErrorChoice = dlg.getDecision();
							LOG.warn("错误决策: path={}, applyAll={}, decision={}, error={}", sourcePath, errorApplyAll, lastErrorChoice, error.toString());
							return lastErrorChoice;
						}

						@Override
						public void onProgress(String currentFile, long copiedBytes, long totalBytes) {
							SwingUtilities.invokeLater(() -> {
								currentLabel.setText("当前文件: " + currentFile);
								int percent = totalBytes > 0 ? (int) (copiedBytes * 100 / totalBytes) : 0;
								progressBar.setValue(percent);
								progressBar.setString(percent + "%");
							});
						}

						@Override
						public void onLog(String message) {
							SwingUtilities.invokeLater(() -> {
								logArea.append(message + "\n");
							});
						}

						@Override
						public boolean isCancelled() {
							return cancelRequested;
						}
					});
				} catch (Exception ex) {
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(CopyPanel.this, ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
					LOG.error("复制任务失败: {}", ex.toString());
				}
				return null;
			}

			@Override
			protected void done() {
				startButton.setEnabled(true);
				LOG.info("复制任务结束");
			}
		};
		worker.execute();
	}
} 
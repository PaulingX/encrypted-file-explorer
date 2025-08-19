package com.example.encryptedexplorer.ui.dialog;

import com.example.encryptedexplorer.model.Resolution;

import javax.swing.*;
import java.awt.*;

/**
 * 文件冲突对话框：替换/跳过/取消 + 应用于后续。
 */
public class ConflictResolutionDialog extends JDialog {
	private Resolution resolution = Resolution.CANCEL;
	private boolean applyToAll = false;

	public ConflictResolutionDialog(Window owner, String message) {
		super(owner, "文件冲突", ModalityType.APPLICATION_MODAL);
		setLayout(new BorderLayout(10, 10));
		JLabel label = new JLabel(message);
		JCheckBox applyAll = new JCheckBox("对后续同类冲突执行相同操作");
		applyAll.addActionListener(e -> applyToAll = applyAll.isSelected());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton replace = new JButton("替换");
		replace.addActionListener(e -> { resolution = Resolution.REPLACE; dispose(); });
		JButton skip = new JButton("跳过");
		skip.addActionListener(e -> { resolution = Resolution.SKIP; dispose(); });
		JButton cancel = new JButton("取消");
		cancel.addActionListener(e -> { resolution = Resolution.CANCEL; dispose(); });
		buttons.add(replace);
		buttons.add(skip);
		buttons.add(cancel);
		add(label, BorderLayout.NORTH);
		add(applyAll, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(owner);
	}

	public Resolution getResolution() { return resolution; }
	public boolean isApplyToAll() { return applyToAll; }
} 
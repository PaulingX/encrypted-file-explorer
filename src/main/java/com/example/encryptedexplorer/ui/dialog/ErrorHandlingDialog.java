package com.example.encryptedexplorer.ui.dialog;

import com.example.encryptedexplorer.model.ErrorDecision;

import javax.swing.*;
import java.awt.*;

/**
 * 错误处理对话框：重试/跳过/取消 + 应用于后续。
 */
public class ErrorHandlingDialog extends JDialog {
	private ErrorDecision decision = ErrorDecision.CANCEL;
	private boolean applyToAll = false;

	public ErrorHandlingDialog(Window owner, String message) {
		super(owner, "错误", ModalityType.APPLICATION_MODAL);
		setLayout(new BorderLayout(10, 10));
		JTextArea area = new JTextArea(message);
		area.setEditable(false);
		area.setLineWrap(true);
		JCheckBox applyAll = new JCheckBox("对后续错误执行相同操作");
		applyAll.addActionListener(e -> applyToAll = applyAll.isSelected());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton retry = new JButton("重试");
		retry.addActionListener(e -> { decision = ErrorDecision.RETRY; dispose(); });
		JButton skip = new JButton("跳过");
		skip.addActionListener(e -> { decision = ErrorDecision.SKIP; dispose(); });
		JButton cancel = new JButton("取消");
		cancel.addActionListener(e -> { decision = ErrorDecision.CANCEL; dispose(); });
		buttons.add(retry);
		buttons.add(skip);
		buttons.add(cancel);
		add(new JScrollPane(area), BorderLayout.NORTH);
		add(applyAll, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		setSize(420, 220);
		setLocationRelativeTo(owner);
	}

	public ErrorDecision getDecision() { return decision; }
	public boolean isApplyToAll() { return applyToAll; }
} 
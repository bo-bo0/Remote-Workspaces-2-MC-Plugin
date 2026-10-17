/*
 * MCreator VCS plugin
 * Copyright (C) 2026, bo bo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.vcs.util;

import net.mcreator.ui.component.util.ThreadUtil;
import net.mcreator.ui.init.L10N;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SshCredentialsProvider extends CredentialsProvider {

	private final Window parent;

	public SshCredentialsProvider(Window parent) {
		this.parent = parent;
	}

	@Override public boolean isInteractive() {
		return true;
	}

	@Override public boolean supports(CredentialItem... items) {
		for (CredentialItem item : items)
			if (!(item instanceof CredentialItem.InformationalMessage || item instanceof CredentialItem.YesNoType
					|| item instanceof CredentialItem.StringType || item instanceof CredentialItem.CharArrayType))
				return false;
		return true;
	}

	@Override public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
		if (!supports(items))
			throw new UnsupportedCredentialItem(uri, "Unsupported SSH credential request");
		AtomicBoolean accepted = new AtomicBoolean();
		ThreadUtil.runOnSwingThreadAndWait(() -> accepted.set(prompt(uri, items)));
		return accepted.get();
	}

	private boolean prompt(URIish uri, CredentialItem[] items) {
		JPanel panel = new JPanel(new GridLayout(0, 1, 5, 5));
		panel.add(new JLabel(uri.getHost()));
		Map<CredentialItem, JComponent> fields = new LinkedHashMap<>();
		for (CredentialItem item : items) {
			JTextArea message = new JTextArea(item.getPromptText());
			message.setEditable(false);
			message.setOpaque(false);
			message.setLineWrap(true);
			message.setWrapStyleWord(true);
			message.setColumns(60);
			if (!(item instanceof CredentialItem.YesNoType))
				panel.add(message);
			if (item instanceof CredentialItem.InformationalMessage)
				continue;
			JComponent field = item instanceof CredentialItem.YesNoType ? new JCheckBox(item.getPromptText())
					: item.isValueSecure() || item instanceof CredentialItem.CharArrayType ? new JPasswordField(30)
					: new JTextField(30);
			fields.put(item, field);
			panel.add(field);
		}
		boolean accepted = JOptionPane.showConfirmDialog(parent, panel,
				L10N.t("dialog.vcs.info_account_authentication"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
		for (Map.Entry<CredentialItem, JComponent> entry : fields.entrySet()) {
			CredentialItem item = entry.getKey();
			JComponent field = entry.getValue();
			if (accepted) {
				if (item instanceof CredentialItem.YesNoType yesNo)
					yesNo.setValue(((JCheckBox) field).isSelected());
				else if (item instanceof CredentialItem.CharArrayType secret)
					secret.setValue(((JPasswordField) field).getPassword());
				else if (item instanceof CredentialItem.StringType string)
					string.setValue(field instanceof JPasswordField secret ? new String(secret.getPassword())
							: ((JTextField) field).getText());
			} else {
				item.clear();
			}
			if (field instanceof JPasswordField secret)
				secret.setText("");
		}
		return accepted;
	}
}

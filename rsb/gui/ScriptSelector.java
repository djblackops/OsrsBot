package net.runelite.client.rsb.gui;

import net.runelite.client.rsb.botLauncher.RuneLite;
import net.runelite.client.rsb.internal.ScriptHandler;
import net.runelite.client.rsb.internal.listener.ScriptListener;
import net.runelite.client.rsb.script.Script;
import net.runelite.client.rsb.service.FileScriptSource;
import net.runelite.client.rsb.service.ScriptDefinition;
import net.runelite.client.rsb.service.ScriptSource;
import net.runelite.client.rsb.service.ServiceException;
import net.runelite.client.rsb.util.GlobalConfiguration;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class ScriptSelector extends JDialog implements ScriptListener {

	public static void main(String[] args) {
		new ScriptSelector(null, null).setVisible(true);
	}

	private static final long serialVersionUID = 5475451138208522511L;

	private static final String[] COLUMN_NAMES = new String[]{"", "Name", "Version", "Author", "Description"};
	private static final ScriptSource SRC_SOURCES;
	private static final ScriptSource SRC_PRECOMPILED;
	private static final ScriptSource SRC_BUNDLED;
	private static final ScriptSource SRC_TEST;
	private static final String TEST_PATH;
	private static final String JAVA_EXT = ".java";
	private static final String CLASS_EXT = ".class";
	private static final String NO_EXT = "";
	private static final String TMP_REGEX = "^tmp[0-9]+";
	private final RuneLite bot;
	private JTable table;
	private JTextField search;
	private JComboBox accounts;
	private final ScriptTableModel model;
	private final List<ScriptDefinition> scripts;
	private final List<String> tmpFileNames;
	private JButton submit;
	private boolean connected = true;

	static {
		/**
		 * Gets the testsScripts path compiled path and replaces the path with the values needed to direct it to the non-compiled path with the .java file to edit
		 */
		StringBuilder temp = new StringBuilder(net.runelite.client.rsb.testsScript.Test.class.getResource("Test.class").getPath().replace("Test.class", ""));
		temp = temp.replace(temp.indexOf("target"), temp.indexOf("target") + "target".length(), "main");
		temp = temp.insert(temp.indexOf("main") - 1, temp.charAt(temp.indexOf("main") - 1) + "src");
		temp = temp.replace(temp.indexOf("classes"), temp.indexOf("classes") + "classes".length(), "java");
		TEST_PATH = temp.toString();

		SRC_TEST = new FileScriptSource(new File(TEST_PATH));
		SRC_SOURCES = new FileScriptSource(new File(GlobalConfiguration.Paths.getScriptsSourcesDirectory()));
		SRC_PRECOMPILED = new FileScriptSource(new File(GlobalConfiguration.Paths.getScriptsPrecompiledDirectory()));
		if (GlobalConfiguration.RUNNING_FROM_JAR) {
			SRC_BUNDLED = new FileScriptSource(new File(GlobalConfiguration.Paths.getScriptsExtractedCache()));
		} else {
			SRC_BUNDLED = new FileScriptSource(new File("." + File.separator + GlobalConfiguration.Paths.SCRIPTS_NAME_SRC));
		}
	}

	public ScriptSelector(Frame frame, RuneLite bot) {
		super(frame, "Script Selector", true);
		this.bot = bot;
		this.scripts = new ArrayList<ScriptDefinition>();
		this.tmpFileNames = new ArrayList<String>();
		this.model = new ScriptTableModel(this.scripts);
	}

	public static ScriptSelector getInstance(RuneLite bot) {
		return new ScriptSelector(Frame.getFrames()[0], bot);
	}

	/**
	 * @author GigiaJ
	 * Description: It takes the testsScripts path in the IDE *unsure if it does it works compiled. Unlikely*
	 * This is necessary simply due to the fact that ScriptClassLoader loads class files WITHOUT package declarations
	 * (This even occurs if you tried to take the compiled file and placed it in the folder)
	 * In order to allow our testsScript package to be used it must be worked around so we reconstruct our file in a temporary
	 * file which will be cleaned up on the next start up.
	 *
	 * So by copying the data of our files to temporary files they can be passed through a buffered reader and scanned for
	 * removing the package declaration and replacing the class name in the file so it can be compiled
	 * Then using jaxax.Compiler it is compiled and is created as a temporary file in memory (could cause memory issues)
	 *
	 */
	private void generateTestScripts() {
			File testScriptDir = new File(TEST_PATH.toString());
			try {
				if (testScriptDir.isDirectory()) {
					for (File file : testScriptDir.listFiles()) {
						if (!file.getName().replace(JAVA_EXT, NO_EXT).matches(TMP_REGEX)) {
							File tmp = File.createTempFile("tmp", JAVA_EXT, testScriptDir);
							tmpFileNames.add(tmp.getName().replace(JAVA_EXT, NO_EXT));
							String s = "";

							BufferedReader br = new BufferedReader(new FileReader(file));
							String st;
							while ((st = br.readLine()) != null) {
								if (!st.contains("package")) {
									if (!st.contains("public class " + file.getName().replace(JAVA_EXT, NO_EXT))) {
										s += st + "\n";
									} else {
										s += st.replace("public class " + file.getName().replace(JAVA_EXT, NO_EXT), "public class " + tmp.getName().replace(JAVA_EXT, NO_EXT)) + "\n";
									}
								}
							}
							Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
							JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
							compiler.run(null, null, null, tmp.getPath());
							new File(tmp.getPath().replace(JAVA_EXT, CLASS_EXT)).deleteOnExit();
							tmp.deleteOnExit();
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
	}

	/**
	 * Deletes the temporary files made in the testScriptDirectory
	 */
	private synchronized void deleteTemporaryFiles() {
		File testScriptDir = new File(TEST_PATH.toString());
		if (testScriptDir.isDirectory()) {
			for (int x = 0; x < testScriptDir.listFiles().length; x++) {
				File file = testScriptDir.listFiles()[x];
				if (file.getName().replace(JAVA_EXT, NO_EXT).matches(TMP_REGEX) || file.getName().replace(CLASS_EXT, NO_EXT).matches(TMP_REGEX)) {
					file.delete();
				}
			}
		}
	}


	public void showGUI() {
		init();
		update();
		load();
		setVisible(true);
	}

	public void update() {
		boolean available = bot.getScriptHandler().getRunningScripts().size() == 0;
		submit.setEnabled(available && table.getSelectedRow() != -1);
		table.setEnabled(available);
		search.setEnabled(available);
		accounts.setEnabled(available);
		table.clearSelection();
	}

	private void load() {
		scripts.clear();
		deleteTemporaryFiles();
		scripts.addAll(SRC_BUNDLED.list());
		scripts.addAll(SRC_PRECOMPILED.list());
		scripts.addAll(SRC_SOURCES.list());
		generateTestScripts();
		scripts.addAll(SRC_TEST.list());
		model.search(search.getText());
		deleteTemporaryFiles();
	}

	private void init() {
		setLayout(new BorderLayout());
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		bot.getScriptHandler().addScriptListener(ScriptSelector.this);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				bot.getScriptHandler().removeScriptListener(ScriptSelector.this);
				dispose();
			}
		});
		table = new JTable(model);
		table.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
				if ((e.getModifiers() & InputEvent.BUTTON3_MASK) == InputEvent.BUTTON3_MASK) {
					final int row = table.rowAtPoint(e.getPoint());
					table.getSelectionModel().setSelectionInterval(row, row);
					showMenu(e);
				}
			}

			private void showMenu(MouseEvent e) {
				final int row = table.rowAtPoint(e.getPoint());
				final ScriptDefinition def = model.getDefinition(row);

				JPopupMenu contextMenu = new JPopupMenu();
				JMenuItem visit = new JMenuItem();
				visit.setText("Visit Site");
				visit.setIcon(new ImageIcon(GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_WEBLINK)));
				visit.addMouseListener(new MouseAdapter() {
					public void mousePressed(MouseEvent e) {
						BotGUI.openURL(def.website);
					}
				});
				contextMenu.add(visit);

				if (def.website == null || def.website.isEmpty()) {
					visit.setEnabled(false);
				}

				contextMenu.show(table, e.getX(), e.getY());
			}
		});
		table.setRowHeight(20);
		table.setIntercellSpacing(new Dimension(1, 1));
		table.setShowGrid(false);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getSelectionModel().addListSelectionListener(new TableSelectionListener());
		setColumnWidths(table, 30, 175, 50, 100);
		JToolBar toolBar = new JToolBar();
		toolBar.setMargin(new Insets(1, 1, 1, 1));
		toolBar.setFloatable(false);
		search = new JTextField();
		search.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				table.clearSelection();
			}
		});
		search.addKeyListener(new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent e) {
				model.search(search.getText());
				table.revalidate();
			}
		});

		submit = new JButton("Start Script", new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_START)));
		final JButton connect = new JButton(new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_CONNECT)));
		submit.setEnabled(false);
		submit.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent evt) {
				ScriptDefinition def = model.getDefinition(table.getSelectedRow());
				try {
					bot.setAccount((String) accounts.getSelectedItem());
					bot.getScriptHandler().runScript(def.source.load(def));
					bot.getScriptHandler().removeScriptListener(ScriptSelector.this);
					dispose();
				} catch (ServiceException e) {
					e.printStackTrace();
				}
			}
		});
		accounts = new JComboBox(AccountManager.getAccountNames());
		accounts.setMinimumSize(new Dimension(200, 20));
		accounts.setPreferredSize(new Dimension(200, 20));
		toolBar.add(search);
		toolBar.add(Box.createHorizontalStrut(5));
		toolBar.add(accounts);
		toolBar.add(Box.createHorizontalStrut(5));
		toolBar.add(connect);
		toolBar.add(Box.createHorizontalStrut(5));
		toolBar.add(submit);
		JPanel center = new JPanel();
		center.setLayout(new BorderLayout());
		JScrollPane pane = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		center.add(pane, BorderLayout.CENTER);
		add(center, BorderLayout.CENTER);
		add(toolBar, BorderLayout.SOUTH);
		setSize(750, 400);
		setMinimumSize(getSize());
		setLocationRelativeTo(getParent());
		search.requestFocus();
	}

	private void setColumnWidths(JTable table, int... widths) {
		for (int i = 0; i < widths.length; ++i) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
			table.getColumnModel().getColumn(i).setMinWidth(widths[i]);
			table.getColumnModel().getColumn(i).setMaxWidth(widths[i]);
		}
	}

	public void scriptStarted(ScriptHandler handler, Script script) {
		update();
	}

	public void scriptStopped(ScriptHandler handler, Script script) {
		update();
	}

	public void scriptResumed(ScriptHandler handler, Script script) {
	}

	public void scriptPaused(ScriptHandler handler, Script script) {
	}

	public void inputChanged(RuneLite bot, int mask) {
	}

	private class TableSelectionListener implements ListSelectionListener {
		public void valueChanged(ListSelectionEvent evt) {
			if (!evt.getValueIsAdjusting()) {
				submit.setEnabled(table.getSelectedRow() != -1);
			}
		}
	}

	private static class ScriptTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		public static final ImageIcon ICON_SCRIPT_SRC = new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_SCRIPT_SRC));
		public static final ImageIcon ICON_SCRIPT_PRE = new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_SCRIPT_PRE));
		public static final ImageIcon ICON_SCRIPT_DRM = new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_SCRIPT_DRM));
		public static final ImageIcon ICON_SCRIPT_BDL = new ImageIcon(
				GlobalConfiguration.getImage(GlobalConfiguration.Paths.Resources.ICON_SCRIPT_BDL));
		private final List<ScriptDefinition> scripts;
		private final List<ScriptDefinition> matches;

		public ScriptTableModel(List<ScriptDefinition> scripts) {
			this.scripts = scripts;
			this.matches = new ArrayList<ScriptDefinition>();
		}

		public void search(String substr) {
			matches.clear();
			if (substr.length() == 0) {
				matches.addAll(scripts);
			} else {
				substr = substr.toLowerCase();
				for (ScriptDefinition def : scripts) {
					if (def.name.toLowerCase().contains(substr)) {
						matches.add(def);
					} else {
						for (String keyword : def.keywords) {
							if (keyword.toLowerCase().contains(substr)) {
								matches.add(def);
								break;
							}
						}
					}
				}
			}
			fireTableDataChanged();
		}

		public ScriptDefinition getDefinition(int rowIndex) {
			return matches.get(rowIndex);
		}

		public int getRowCount() {
			return matches.size();
		}

		public int getColumnCount() {
			return COLUMN_NAMES.length;
		}

		public Object getValueAt(int rowIndex, int columnIndex) {
			if (rowIndex >= 0 && rowIndex < matches.size()) {
				ScriptDefinition def = matches.get(rowIndex);
				if (columnIndex == 0) {
					if (def.source == SRC_SOURCES) {
						return ICON_SCRIPT_SRC;
					}
					if (def.source == SRC_PRECOMPILED) {
						return ICON_SCRIPT_PRE;
					}
					if (def.source == SRC_BUNDLED) {
						return ICON_SCRIPT_BDL;
					}
					return ICON_SCRIPT_DRM;
				}
				if (columnIndex == 1) {
					return def.name;
				}
				if (columnIndex == 2) {
					return def.version;
				}
				if (columnIndex == 3) {
					StringBuilder b = new StringBuilder();
					for (String author : def.authors) {
						b.append(author).append(", ");
					}
					return b.replace(b.length() - 2, b.length(), "");
				}
				if (columnIndex == 4) {
					return def.description;
				}
			}
			return null;
		}

		@Override
		public Class<?> getColumnClass(int col) {
			if (col == 0) {
				return ImageIcon.class;
			}
			return String.class;
		}

		@Override
		public String getColumnName(int col) {
			return COLUMN_NAMES[col];
		}
	}
}
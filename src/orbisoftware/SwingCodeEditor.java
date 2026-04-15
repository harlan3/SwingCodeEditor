package orbisoftware;

import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rtextarea.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SwingCodeEditor extends JFrame {

	private boolean softRenderMenuBar = false; // Set to true for use in 3D Desktop
	
	private int appWidth = 1250;
	private int appHeight = 820;
	
    private JSplitPane mainSplitPane;
    private JTabbedPane leftTabbedPane;
    private JTabbedPane rightTabbedPane;

    private JTabbedPane activeTabbedPane;   // Tracks which side has the cursor
    private boolean wordWrapEnabled = false;

    private Map<Component, File> tabToFile = new HashMap<>();
    private Map<Component, String> tabToSyntax = new HashMap<>();
    private Map<Component, UndoManager> tabToUndoManager = new HashMap<>();
    private Map<Component, String> tabToBaseTitle = new HashMap<>();
    private Map<Component, Boolean> tabToDirty = new HashMap<>();

    private ButtonGroup themeButtonGroup = new ButtonGroup();
    private ArrayList<String> themeNames = new ArrayList<>();

    private JMenuItem undoMenuItem;
    private JMenuItem redoMenuItem;
    private JMenuItem cutMenuItem;
    private JMenuItem copyMenuItem;
    private JMenuItem pasteMenuItem;
    private JMenuItem selectAllMenuItem;

    private JLabel caretStatusLabel;
    private File lastDirectory;

    public SwingCodeEditor() {
        super("Swing Code Editor");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(appWidth, appHeight);
        setLocationRelativeTo(null);

        leftTabbedPane = createTabbedPane();
        rightTabbedPane = createTabbedPane();

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabbedPane, rightTabbedPane);
        mainSplitPane.setResizeWeight(0.5);
        mainSplitPane.setOneTouchExpandable(true);

        activeTabbedPane = leftTabbedPane;
        lastDirectory = new File(System.getProperty("user.home"));

        setupCursorTracking();

        caretStatusLabel = new JLabel("Line 1, Column 1");
        setJMenuBar(createMenuBar());
        setContentPane(createRenderablePanel());
        
        // Initial tabs
        addNewTab(leftTabbedPane, "Untitled-Left", "", SyntaxConstants.SYNTAX_STYLE_JAVA);
        addNewTab(rightTabbedPane, "Untitled-Right", "", SyntaxConstants.SYNTAX_STYLE_JAVA);

        SwingUtilities.invokeLater(() -> {
            updateEditMenuState();
            updateCaretStatus();
        });
    }
    
    public JPanel createRenderablePanel() {
    	JPanel renderPanel = new JPanel(new BorderLayout());

        if (softRenderMenuBar) {
            JMenuBar menuBar = getJMenuBar();
            if (menuBar != null) {
                renderPanel.add(menuBar, BorderLayout.NORTH);
            }
        }

        renderPanel.add(mainSplitPane, BorderLayout.CENTER);
        renderPanel.add(createStatusBar(), BorderLayout.SOUTH);

        renderPanel.setPreferredSize(new Dimension(appWidth, appHeight));
        renderPanel.setSize(appWidth, appHeight);
        renderPanel.validate();
        renderPanel.doLayout();

        return renderPanel;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        caretStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        caretStatusLabel.setPreferredSize(new Dimension(130, 20));
        leftPanel.add(caretStatusLabel);

        statusBar.add(leftPanel, BorderLayout.WEST);
        return statusBar;
    }

    private void updateCaretStatus() {
        if (caretStatusLabel == null) {
            return;
        }

        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea == null) {
            caretStatusLabel.setText("Line -, Column -");
            return;
        }

        int caretPosition = textArea.getCaretPosition();
        try {
            int line = textArea.getLineOfOffset(caretPosition) + 1;
            int column = caretPosition - textArea.getLineStartOffset(line - 1) + 1;
            caretStatusLabel.setText("Line " + line + ", Column " + column);
        } catch (Exception ex) {
            caretStatusLabel.setText("Line -, Column -");
        }
    }

    /**
     * Changes the theme.
     */
    private class ThemeAction extends AbstractAction {

        private String xml;

        ThemeAction(String name, String xml) {
            putValue(NAME, name);
            this.xml = xml;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            InputStream in = getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + xml);
            try {
                // Keep the text area's font since it has our e.g. ligature hints
                Theme theme = Theme.load(in, getCurrentTextArea().getFont());
                theme.apply(getCurrentTextArea());
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }

    }

    private JTabbedPane createTabbedPane() {
        JTabbedPane pane = new JTabbedPane();
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        return pane;
    }

    private void setupCursorTracking() {
        FocusListener cursorTracker = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                Component src = e.getComponent();
                if (SwingUtilities.isDescendingFrom(src, leftTabbedPane)) {
                    activeTabbedPane = leftTabbedPane;
                } else if (SwingUtilities.isDescendingFrom(src, rightTabbedPane)) {
                    activeTabbedPane = rightTabbedPane;
                }
                updateEditMenuState();
                updateCaretStatus();
            }
        };

        // Track focus on tabbed panes and editors
        leftTabbedPane.addFocusListener(cursorTracker);
        rightTabbedPane.addFocusListener(cursorTracker);

        // Track tab switching
        leftTabbedPane.addChangeListener(e -> {
            activeTabbedPane = leftTabbedPane;
            updateEditMenuState();
            updateCaretStatus();
        });
        rightTabbedPane.addChangeListener(e -> {
            activeTabbedPane = rightTabbedPane;
            updateEditMenuState();
            updateCaretStatus();
        });
    }

    private void addNewTab(JTabbedPane tabbedPane, String title, String content, String syntaxStyle) {
        RSyntaxTextArea textArea = createTextArea(syntaxStyle);
        applyWordWrap(textArea, wordWrapEnabled);
        textArea.setText(content);
        textArea.setCaretPosition(0);
        installUndoRedo(textArea);

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(true);
        scrollPane.setLineNumbersEnabled(true);

        tabbedPane.addTab(title, scrollPane);
        tabbedPane.setSelectedComponent(scrollPane);

        tabToFile.put(scrollPane, null);
        tabToSyntax.put(scrollPane, syntaxStyle);
        tabToUndoManager.put(scrollPane, getUndoManager(textArea));
        tabToBaseTitle.put(scrollPane, title);
        tabToDirty.put(scrollPane, false);

        installDirtyTracking(scrollPane, textArea, tabbedPane);

        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1,
                new TabHeader(title, scrollPane, tabbedPane, this));

        try {
            if (themeButtonGroup.getSelection() != null) {
                String value = themeButtonGroup.getSelection().getActionCommand();
                Theme theme = Theme.load(getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + value));
                if (theme != null) {
                    theme.apply(textArea);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // New/opened tabs start clean.
        markTabDirty(scrollPane, false);

        // Make new tab active
        SwingUtilities.invokeLater(() -> {
            textArea.requestFocusInWindow();
            activeTabbedPane = tabbedPane;
            updateEditMenuState();
            updateCaretStatus();
        });
    }

    private RSyntaxTextArea createTextArea(String syntaxStyle) {
        RSyntaxTextArea textArea = new RSyntaxTextArea(30, 100);
        textArea.setSyntaxEditingStyle(syntaxStyle);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        textArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        textArea.setTabsEmulated(true);
        textArea.setTabSize(4);
        applyWordWrap(textArea, wordWrapEnabled);
        return textArea;
    }

    private void installUndoRedo(RSyntaxTextArea textArea) {
        UndoManager undoManager = new UndoManager();
        textArea.putClientProperty("undoManager", undoManager);

        textArea.getDocument().addUndoableEditListener(new UndoableEditListener() {
            @Override
            public void undoableEditHappened(UndoableEditEvent e) {
                undoManager.addEdit(e.getEdit());
                updateEditMenuState();
            }
        });

        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (SwingUtilities.isDescendingFrom(textArea, leftTabbedPane)) {
                    activeTabbedPane = leftTabbedPane;
                } else if (SwingUtilities.isDescendingFrom(textArea, rightTabbedPane)) {
                    activeTabbedPane = rightTabbedPane;
                }
                updateEditMenuState();
                updateCaretStatus();
            }
        });

        textArea.addCaretListener(e -> {
            updateEditMenuState();
            updateCaretStatus();
        });
    }

    private void installDirtyTracking(Component tabComponent, RSyntaxTextArea textArea, JTabbedPane tabbedPane) {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markTabDirty(tabComponent, true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markTabDirty(tabComponent, true);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markTabDirty(tabComponent, true);
            }
        });
    }

    private UndoManager getUndoManager(RSyntaxTextArea textArea) {
        Object value = textArea.getClientProperty("undoManager");
        return value instanceof UndoManager ? (UndoManager) value : null;
    }

    private UndoManager getCurrentUndoManager() {
        Component tab = getCurrentTabComponent();
        if (tab != null) {
            UndoManager manager = tabToUndoManager.get(tab);
            if (manager != null) {
                return manager;
            }
        }

        RSyntaxTextArea textArea = getCurrentTextArea();
        return textArea != null ? getUndoManager(textArea) : null;
    }

    private void undoCurrentEdit() {
        UndoManager undoManager = getCurrentUndoManager();
        if (undoManager != null && undoManager.canUndo()) {
            try {
                undoManager.undo();
            } catch (CannotUndoException ignored) {
            }
        }
        updateEditMenuState();
    }

    private void redoCurrentEdit() {
        UndoManager undoManager = getCurrentUndoManager();
        if (undoManager != null && undoManager.canRedo()) {
            try {
                undoManager.redo();
            } catch (CannotRedoException ignored) {
            }
        }
        updateEditMenuState();
    }

    private void updateUndoRedoMenuState() {
        if (undoMenuItem == null || redoMenuItem == null) {
            return;
        }

        UndoManager undoManager = getCurrentUndoManager();
        undoMenuItem.setEnabled(undoManager != null && undoManager.canUndo());
        redoMenuItem.setEnabled(undoManager != null && undoManager.canRedo());
    }

    private void updateEditMenuState() {
        updateUndoRedoMenuState();

        RSyntaxTextArea textArea = getCurrentTextArea();
        boolean hasTextArea = textArea != null;
        boolean hasSelection = hasTextArea && textArea.getSelectionStart() != textArea.getSelectionEnd();
        boolean editable = hasTextArea && textArea.isEditable();
        boolean hasDocumentText = hasTextArea && textArea.getDocument().getLength() > 0;

        if (cutMenuItem != null) {
            cutMenuItem.setEnabled(editable && hasSelection);
        }
        if (copyMenuItem != null) {
            copyMenuItem.setEnabled(hasSelection);
        }
        if (pasteMenuItem != null) {
            pasteMenuItem.setEnabled(editable);
        }
        if (selectAllMenuItem != null) {
            selectAllMenuItem.setEnabled(hasDocumentText);
        }
    }

    private void cutCurrentSelection() {
        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea != null) {
            textArea.cut();
            textArea.requestFocusInWindow();
        }
        updateEditMenuState();
    }

    private void copyCurrentSelection() {
        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea != null) {
            textArea.copy();
            textArea.requestFocusInWindow();
        }
        updateEditMenuState();
    }

    private void pasteAtCaret() {
        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea != null) {
            textArea.paste();
            textArea.requestFocusInWindow();
        }
        updateEditMenuState();
    }

    private void selectAllInCurrentEditor() {
        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea != null) {
            textArea.selectAll();
            textArea.requestFocusInWindow();
        }
        updateEditMenuState();
    }

    private void markTabDirty(Component tab, boolean dirty) {
        if (tab == null) {
            return;
        }

        Boolean currentDirty = tabToDirty.get(tab);
        if (currentDirty != null && currentDirty == dirty) {
            return;
        }

        tabToDirty.put(tab, dirty);
        updateTabTitle(tab);
    }

    private void updateTabTitle(Component tab) {
        JTabbedPane pane = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, tab);
        if (pane == null) {
            return;
        }

        int index = pane.indexOfComponent(tab);
        if (index < 0) {
            return;
        }

        String baseTitle = tabToBaseTitle.get(tab);
        if (baseTitle == null) {
            baseTitle = pane.getTitleAt(index);
        }

        boolean dirty = Boolean.TRUE.equals(tabToDirty.get(tab));
        String displayTitle = dirty ? "* " + baseTitle : baseTitle;
        pane.setTitleAt(index, displayTitle);

        Component header = pane.getTabComponentAt(index);
        if (header instanceof TabHeader) {
            ((TabHeader) header).setTitle(displayTitle);
        }
    }

    private void setTabBaseTitle(Component tab, String baseTitle) {
        tabToBaseTitle.put(tab, baseTitle);
        updateTabTitle(tab);
    }

    private void addThemeItem(String name, int index, String themeXml, ButtonGroup bg, JMenu menu) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(new ThemeAction(name, themeXml));
        item.setActionCommand(themeXml);
        bg.add(item);
        menu.add(item);

        themeNames.add(themeXml);

        if (index == 5) {
            item.setSelected(true);
        }
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem("New Tab (Left)", e -> addNewTab(leftTabbedPane, "Untitled", "", SyntaxConstants.SYNTAX_STYLE_JAVA)));
        fileMenu.add(createMenuItem("New Tab (Right)", e -> addNewTab(rightTabbedPane, "Untitled", "", SyntaxConstants.SYNTAX_STYLE_JAVA)));
        fileMenu.add(createMenuItem("Open...", e -> openFile()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Save", e -> saveCurrentTab()));
        fileMenu.add(createMenuItem("Save As...", e -> saveCurrentTabAs()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Close Current Tab", e -> closeCurrentTab()));
        fileMenu.add(createMenuItem("Exit", e -> System.exit(0)));

        JMenu editMenu = new JMenu("Edit");
        undoMenuItem = createMenuItem("Undo", e -> undoCurrentEdit());
        undoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(undoMenuItem);

        redoMenuItem = createMenuItem("Redo", e -> redoCurrentEdit());
        redoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(redoMenuItem);

        editMenu.addSeparator();

        cutMenuItem = createMenuItem("Cut", e -> cutCurrentSelection());
        cutMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(cutMenuItem);

        copyMenuItem = createMenuItem("Copy", e -> copyCurrentSelection());
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(copyMenuItem);

        pasteMenuItem = createMenuItem("Paste", e -> pasteAtCaret());
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(pasteMenuItem);

        selectAllMenuItem = createMenuItem("Select All", e -> selectAllInCurrentEditor());
        selectAllMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(selectAllMenuItem);

        editMenu.addSeparator();
        editMenu.add(createMenuItem("Find / Replace...", e -> showFindReplaceDialog()));

        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem splitItem = new JCheckBoxMenuItem("Enable Split View", true);
        splitItem.addActionListener(e -> toggleSplitView(splitItem.isSelected()));
        viewMenu.add(splitItem);

        JCheckBoxMenuItem wordWrapItem = new JCheckBoxMenuItem("Enable Word Wrap", wordWrapEnabled);
        wordWrapItem.addActionListener(e -> {
            wordWrapEnabled = wordWrapItem.isSelected();
            applyWordWrapToAllTabs();
        });
        viewMenu.add(wordWrapItem);

        JMenu themeMenu = new JMenu("Themes");
        int buttonIndex = 0;
        addThemeItem("Default", buttonIndex++, "default.xml", themeButtonGroup, themeMenu);
        addThemeItem("Default (System Selection)", buttonIndex++, "default-alt.xml", themeButtonGroup, themeMenu);
        addThemeItem("Dark", buttonIndex++, "dark.xml", themeButtonGroup, themeMenu);
        addThemeItem("Druid", buttonIndex++, "druid.xml", themeButtonGroup, themeMenu);
        addThemeItem("Monokai", buttonIndex++, "monokai.xml", themeButtonGroup, themeMenu);
        addThemeItem("Eclipse", buttonIndex++, "eclipse.xml", themeButtonGroup, themeMenu);
        addThemeItem("IDEA", buttonIndex++, "idea.xml", themeButtonGroup, themeMenu);
        addThemeItem("Visual Studio", buttonIndex++, "vs.xml", themeButtonGroup, themeMenu);

        JMenu langMenu = new JMenu("Language");
        addLanguageItem(langMenu, "Java", SyntaxConstants.SYNTAX_STYLE_JAVA);
        addLanguageItem(langMenu, "Python", SyntaxConstants.SYNTAX_STYLE_PYTHON);
        addLanguageItem(langMenu, "JavaScript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        addLanguageItem(langMenu, "C++", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS);
        addLanguageItem(langMenu, "HTML", SyntaxConstants.SYNTAX_STYLE_HTML);
        addLanguageItem(langMenu, "XML", SyntaxConstants.SYNTAX_STYLE_XML);
        addLanguageItem(langMenu, "SQL", SyntaxConstants.SYNTAX_STYLE_SQL);
        addLanguageItem(langMenu, "Markdown", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        menuBar.add(themeMenu);
        menuBar.add(langMenu);
        return menuBar;
    }

    private JMenuItem createMenuItem(String text, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }

    private JFileChooser createFileChooser() {
        JFileChooser chooser = (lastDirectory != null && lastDirectory.isDirectory())
                ? new JFileChooser(lastDirectory)
                : new JFileChooser();
        return chooser;
    }

    private void rememberDirectory(File file) {
        if (file == null) {
            return;
        }
        File directory = file.isDirectory() ? file : file.getParentFile();
        if (directory != null && directory.isDirectory()) {
            lastDirectory = directory;
        }
    }

    private void toggleSplitView(boolean enabled) {
        if (enabled) {
            mainSplitPane.setRightComponent(rightTabbedPane);
        } else {
            mainSplitPane.setRightComponent(null);
        }
        mainSplitPane.revalidate();
    }

    private void applyWordWrap(RSyntaxTextArea textArea, boolean enabled) {
        if (textArea == null) {
            return;
        }
        textArea.setLineWrap(enabled);
        textArea.setWrapStyleWord(enabled);
    }

    private void applyWordWrapToAllTabs() {
        applyWordWrapToPane(leftTabbedPane);
        applyWordWrapToPane(rightTabbedPane);
        revalidate();
        repaint();
    }

    private void applyWordWrapToPane(JTabbedPane pane) {
        for (int i = 0; i < pane.getTabCount(); i++) {
            Component comp = pane.getComponentAt(i);
            if (comp instanceof RTextScrollPane) {
                RSyntaxTextArea textArea = (RSyntaxTextArea) ((RTextScrollPane) comp).getTextArea();
                applyWordWrap(textArea, wordWrapEnabled);
            }
        }
    }

    private void moveTabToOppositeSide(JTabbedPane sourcePane, Component tab) {
        if (sourcePane == null || tab == null) {
            return;
        }

        int sourceIndex = sourcePane.indexOfComponent(tab);
        if (sourceIndex < 0) {
            return;
        }

        JTabbedPane targetPane = sourcePane == leftTabbedPane ? rightTabbedPane : leftTabbedPane;
        String title = sourcePane.getTitleAt(sourceIndex);

        sourcePane.remove(tab);
        targetPane.addTab(title, tab);

        int targetIndex = targetPane.indexOfComponent(tab);
        targetPane.setTabComponentAt(targetIndex, new TabHeader(title, tab, targetPane, this));
        targetPane.setSelectedComponent(tab);

        activeTabbedPane = targetPane;
        updateTabTitle(tab);

        SwingUtilities.invokeLater(() -> {
            RSyntaxTextArea textArea = tab instanceof RTextScrollPane
                    ? (RSyntaxTextArea) ((RTextScrollPane) tab).getTextArea()
                    : null;
            if (textArea != null) {
                textArea.requestFocusInWindow();
            }
            updateEditMenuState();
        });
    }

    private void addLanguageItem(JMenu menu, String name, String style) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(e -> {
            RSyntaxTextArea ta = getCurrentTextArea();
            if (ta != null) {
                ta.setSyntaxEditingStyle(style);
                Component tab = getCurrentTabComponent();
                if (tab != null) {
                    tabToSyntax.put(tab, style);
                }
            }
        });
        menu.add(item);
    }

    // === Cursor-aware helpers ===
    private JTabbedPane getActiveTabbedPane() {
        return activeTabbedPane;
    }

    private Component getCurrentTabComponent() {
        JTabbedPane pane = getActiveTabbedPane();
        return pane != null ? pane.getSelectedComponent() : null;
    }

    private RSyntaxTextArea getCurrentTextArea() {
        Component comp = getCurrentTabComponent();
        if (comp instanceof RTextScrollPane) {
            return (RSyntaxTextArea) ((RTextScrollPane) comp).getTextArea();
        }
        return null;
    }

    private void showFindReplaceDialog() {
        RSyntaxTextArea textArea = getCurrentTextArea();
        if (textArea == null) return;

        JDialog dialog = new JDialog(this, "Replace", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField findField = new JTextField(25);
        JTextField replaceField = new JTextField(25);
        JCheckBox matchCase = new JCheckBox("Match case");
        JCheckBox wholeWord = new JCheckBox("Whole word");
        JRadioButton forward = new JRadioButton("Forward", true);
        JRadioButton backward = new JRadioButton("Backward");
        ButtonGroup group = new ButtonGroup();
        group.add(forward);
        group.add(backward);

        JButton findNextBtn = new JButton("Find Next");
        JButton replaceBtn = new JButton("Replace");
        JButton replaceAllBtn = new JButton("Replace All");
        JButton closeBtn = new JButton("Close");

        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.NONE;
        dialog.add(new JLabel("Find:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        dialog.add(findField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        dialog.add(new JLabel("Replace with:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        dialog.add(replaceField, gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        dialog.add(matchCase, gbc);
        gbc.gridx = 2;
        dialog.add(wholeWord, gbc);

        gbc.gridx = 1; gbc.gridy = 3;
        dialog.add(forward, gbc);
        gbc.gridx = 2;
        dialog.add(backward, gbc);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(findNextBtn);
        buttonPanel.add(replaceBtn);
        buttonPanel.add(replaceAllBtn);
        buttonPanel.add(closeBtn);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3; gbc.anchor = GridBagConstraints.CENTER;
        dialog.add(buttonPanel, gbc);

        Runnable focusBackToEditor = () -> {
            textArea.requestFocusInWindow();
            updateEditMenuState();
        };

        findNextBtn.addActionListener(e -> {
            String searchText = findField.getText().trim();
            if (searchText.isEmpty()) return;

            SearchContext context = new SearchContext();
            context.setSearchFor(searchText);
            context.setMatchCase(matchCase.isSelected());
            context.setWholeWord(wholeWord.isSelected());
            context.setSearchForward(forward.isSelected());
            context.setMarkAll(true);

            SearchResult result = SearchEngine.find(textArea, context);
            if (!result.wasFound()) {
                JOptionPane.showMessageDialog(dialog, "Text not found.", "Replace", JOptionPane.INFORMATION_MESSAGE);
            }
            focusBackToEditor.run();
        });

        replaceBtn.addActionListener(e -> {
            String searchText = findField.getText().trim();
            if (searchText.isEmpty()) return;

            SearchContext context = new SearchContext();
            context.setSearchFor(searchText);
            context.setReplaceWith(replaceField.getText());
            context.setMatchCase(matchCase.isSelected());
            context.setWholeWord(wholeWord.isSelected());
            context.setSearchForward(forward.isSelected());
            context.setMarkAll(true);

            SearchResult result = SearchEngine.replace(textArea, context);
            if (!result.wasFound()) {
                JOptionPane.showMessageDialog(dialog, "Text not found.", "Replace", JOptionPane.INFORMATION_MESSAGE);
            }
            focusBackToEditor.run();
        });

        replaceAllBtn.addActionListener(e -> {
            String searchText = findField.getText().trim();
            if (searchText.isEmpty()) return;

            SearchContext context = new SearchContext();
            context.setSearchFor(searchText);
            context.setReplaceWith(replaceField.getText());
            context.setMatchCase(matchCase.isSelected());
            context.setWholeWord(wholeWord.isSelected());
            context.setSearchForward(true);
            context.setMarkAll(true);

            SearchResult result = SearchEngine.replaceAll(textArea, context);
            JOptionPane.showMessageDialog(dialog,
                    result.getCount() + " occurrence(s) replaced.",
                    "Replace All",
                    JOptionPane.INFORMATION_MESSAGE);
            focusBackToEditor.run();
        });

        closeBtn.addActionListener(e -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void openFile() {
        JFileChooser chooser = createFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            rememberDirectory(file);
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    content.append(line).append("\n");
                }

                JTabbedPane targetPane = getActiveTabbedPane();   // Uses current cursor side
                String title = file.getName();
                addNewTab(targetPane, title, content.toString(), guessSyntaxFromFile(file));

                Component newTab = targetPane.getSelectedComponent();
                tabToFile.put(newTab, file);
                setTabBaseTitle(newTab, title);
                markTabDirty(newTab, false);

                String value = themeButtonGroup.getSelection().getActionCommand();

                Theme theme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/" + value));
                if (theme != null) {
                    RSyntaxTextArea textArea = (RSyntaxTextArea) ((RTextScrollPane) newTab).getTextArea();
                    theme.apply(textArea);
                }
                updateEditMenuState();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error opening file:\n" + ex.getMessage());
            }
        }
    }

    private String guessSyntaxFromFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (name.endsWith(".py")) return SyntaxConstants.SYNTAX_STYLE_PYTHON;
        if (name.endsWith(".js") || name.endsWith(".jsx")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".cc")) return SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
        if (name.endsWith(".html") || name.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (name.endsWith(".xml")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (name.endsWith(".sql")) return SyntaxConstants.SYNTAX_STYLE_SQL;
        if (name.endsWith(".md")) return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private void saveCurrentTab() {
        Component tab = getCurrentTabComponent();
        File file = tabToFile.get(tab);
        if (file == null) {
            saveCurrentTabAs();
        } else {
            rememberDirectory(file);
            saveToFile(tab, file);
        }
    }

    private void saveCurrentTabAs() {
        Component tab = getCurrentTabComponent();
        JFileChooser chooser = createFileChooser();
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            rememberDirectory(file);
            if (saveToFile(tab, file)) {
                tabToFile.put(tab, file);
                setTabBaseTitle(tab, file.getName());
            }
        }
    }

    private boolean saveToFile(Component tabComponent, File file) {
        if (!(tabComponent instanceof RTextScrollPane)) return false;
        RSyntaxTextArea textArea = (RSyntaxTextArea) ((RTextScrollPane) tabComponent).getTextArea();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            textArea.write(bw);
            markTabDirty(tabComponent, false);
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error saving file:\n" + ex.getMessage());
            return false;
        }
    }

    private void closeCurrentTab() {
        JTabbedPane pane = getActiveTabbedPane();
        int index = pane.getSelectedIndex();
        if (index >= 0) {
            Component comp = pane.getComponentAt(index);
            removeTab(pane, comp);
        }
    }

    private void removeTab(JTabbedPane pane, Component tab) {
        tabToFile.remove(tab);
        tabToSyntax.remove(tab);
        tabToUndoManager.remove(tab);
        tabToBaseTitle.remove(tab);
        tabToDirty.remove(tab);
        pane.remove(tab);
        updateEditMenuState();
        updateCaretStatus();
    }

    private static class TabHeader extends JPanel {
        private final JLabel titleLabel;

        public TabHeader(String title, Component tab, JTabbedPane pane, SwingCodeEditor editorFrame) {
            setOpaque(false);
            setLayout(new FlowLayout(FlowLayout.CENTER, 5, 0));

            titleLabel = new JLabel(title);
            add(titleLabel);

            JButton closeBtn = new JButton("×");
            closeBtn.setMargin(new Insets(0, 2, 0, 2));
            closeBtn.setFocusable(false);
            closeBtn.addActionListener(e -> editorFrame.removeTab(pane, tab));
            add(closeBtn);

            MouseAdapter popupHandler = new MouseAdapter() {
                private void maybeShowPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        return;
                    }

                    int index = pane.indexOfComponent(tab);
                    if (index < 0) {
                        return;
                    }

                    pane.setSelectedIndex(index);
                    editorFrame.activeTabbedPane = pane;
                    editorFrame.updateEditMenuState();

                    String targetSide = pane == editorFrame.leftTabbedPane ? "Right" : "Left";
                    JPopupMenu popupMenu = new JPopupMenu();
                    JMenuItem moveItem = new JMenuItem("Move to " + targetSide);
                    moveItem.addActionListener(ev -> editorFrame.moveTabToOppositeSide(pane, tab));
                    popupMenu.add(moveItem);
                    popupMenu.show(e.getComponent(), e.getX(), e.getY());
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }
            };

            addMouseListener(popupHandler);
            titleLabel.addMouseListener(popupHandler);
        }

        public void setTitle(String title) {
            titleLabel.setText(title);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SwingCodeEditor().setVisible(true));
    }
}

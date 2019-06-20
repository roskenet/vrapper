package net.sourceforge.vrapper.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.services.IDisposable;

import net.sourceforge.vrapper.log.VrapperLog;
import net.sourceforge.vrapper.platform.CommandLineUI;
import net.sourceforge.vrapper.utils.CaretType;
import net.sourceforge.vrapper.utils.ContentType;
import net.sourceforge.vrapper.utils.VimUtils;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.register.Register;
import net.sourceforge.vrapper.vim.register.RegisterContent;
import net.sourceforge.vrapper.vim.register.RegisterManager;
import net.sourceforge.vrapper.vim.register.StringRegisterContent;

public class EclipseCommandLineUI implements CommandLineUI, IDisposable, CaretListener, SelectionListener {

    private StyledText commandLineText;
    private Register clipboard;
    private String prompt;
    private int contentsOffset;
    private Caret defaultCaret;
    private Caret endCharCaret;
    private Menu contextMenu;
    private MenuItem cutItem;
    private MenuItem copyItem;
    private MenuItem pasteItem;
    private MenuItem selectAllItem;
    
    /**
     * Signals that a "register mode marker" was inserted at the start of the selection if set.
     */
    private Point registerModeSelection;
    /** Read-only mode active. This disables destructive context menu actions. */
    private boolean readOnly;
    private int maxHeight;
    private int width;
    private int bottom;

    public EclipseCommandLineUI(final StyledText commandLineText, final EditorAdaptor editorAdaptor) {
        clipboard = editorAdaptor.getRegisterManager()
                .getRegister(RegisterManager.REGISTER_NAME_CLIPBOARD);
        this.commandLineText = commandLineText;
        commandLineText.addCaretListener(this);
        commandLineText.addSelectionListener(this);
        
        this.defaultCaret = commandLineText.getCaret();
        this.endCharCaret = CaretUtils.createCaret(CaretType.RECTANGULAR, commandLineText);
        commandLineText.setCaret(endCharCaret);
        
        contextMenu = new Menu(commandLineText);
        cutItem = new MenuItem(contextMenu, SWT.DEFAULT);
        cutItem.setText("Cut");
        cutItem.setEnabled(false);
        copyItem = new MenuItem(contextMenu, SWT.DEFAULT);
        copyItem.setText("Copy\tCtrl-Y  ");
        copyItem.setEnabled(false);
        pasteItem = new MenuItem(contextMenu, SWT.DEFAULT);
        pasteItem.setText("Paste\tCtrl-R +");
        selectAllItem = new MenuItem(contextMenu, SWT.DEFAULT);
        selectAllItem.setText("Select All");
        
        commandLineText.setMenu(contextMenu);
        
        cutItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                copySelectionToClipboard();
                erase();
            }
        });
        copyItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                copySelectionToClipboard();
            }
        });
        pasteItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                RegisterContent content = clipboard.getContent();
                if (content.getPayloadType() == ContentType.TEXT
                        || content.getPayloadType() == ContentType.LINES) {
                    String text = content.getText();
                    text = VimUtils.replaceNewLines(text, " ");
                    type(text);
                }
            }
        });
        selectAllItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                commandLineText.setSelection(contentsOffset, commandLineText.getCharCount());
                //Manually trigger selection listener because for some reason the method call above doesn't.
                Event e2 = new Event();
                e2.widget = commandLineText;
                e2.x = contentsOffset;
                e2.y = commandLineText.getCharCount();
                EclipseCommandLineUI.this.widgetSelected(new SelectionEvent(e2));
            }
        });
    }

    public StyledText getWidget() {
        return commandLineText;
    }

    public boolean isOpen() {
        return ! commandLineText.isDisposed() && commandLineText.isVisible();
    }

    @Override
    public void setPrompt(String newPrompt) {
        prompt = newPrompt;
        commandLineText.replaceTextRange(0, contentsOffset, newPrompt);
        contentsOffset = newPrompt.length();
        commandLineText.setCaretOffset(commandLineText.getCharCount());
        updateUISize();
    }

    @Override
    public void resetContents(String newContents) {
        commandLineText.replaceTextRange(contentsOffset,
                commandLineText.getCharCount() - contentsOffset, newContents);
        //Clear selection, set caret position to end of widget.
        commandLineText.setCaret(endCharCaret);
        commandLineText.setSelection(commandLineText.getCharCount());
    }

    @Override
    public String getContents() {
        int charCount = commandLineText.getCharCount();
        if (charCount == contentsOffset) {
            //SWT doesn't like text ranges where start == end, handle empty contents explicitly
            return "";
        } else {
            return commandLineText.getText(contentsOffset, charCount - 1);
        }
    }

    @Override
    public String getContents(int start, int end) {
        int endPosition = getEndPosition();
        if ((start > endPosition || start < 0)
                && (end > endPosition || end < 0)) {
            throw new IllegalArgumentException("Cannot get command line contents, start offset "
                    + start + " and end offset " + end + " are out of commandline bounds");
        } else if (start > endPosition || start < 0) {
            throw new IllegalArgumentException("Cannot get command line contents, start offset "
                    + start + " is out of commandline bounds");
        } else if (end > endPosition || end < 0) {
            throw new IllegalArgumentException("Cannot get command line contents, end offset "
                    + end + " is out of commandline bounds");
        }
        int offset = Math.min(start, end) + contentsOffset;
        int length = Math.abs(end - start);
        return commandLineText.getTextRange(offset, length);
    }

    @Override
    public String getFullContents() {
        return commandLineText.getText();
    }

    @Override
    public void type(String characters) {
        clipSelection();
        setMode(CommandLineMode.DEFAULT);
        int start = commandLineText.getCaretOffset();
        //Check caret position after replacing selection - caret might have been at end of selection
        if (commandLineText.getSelectionCount() > 0) {
            Point selection = commandLineText.getSelection();
            start = Math.min(selection.x, selection.y);
        }
        commandLineText.insert(characters);
        commandLineText.setCaretOffset(start + characters.length());
        //Mouse selection might cause caret to be at the same position as before, update manually
        updateCaret();
        updateUISize();
    }
    
    public void copySelectionToClipboard() {
        clipSelection();
        String selection = commandLineText.getSelectionText();
        if (selection.isEmpty()) {
            selection = getContents();
        }
        clipboard.setContent(new StringRegisterContent(ContentType.TEXT, selection));
    }

    public void open() {
        final Composite parent = commandLineText.getParent();
        commandLineText.setForeground(parent.getForeground());
        commandLineText.setBackground(parent.getBackground());
        commandLineText.setVisible(true);
        commandLineText.moveAbove(parent);
        parent.redraw();
        //The expected size of the command line is only known when the parent is drawn, paint async
        Display.getCurrent().asyncExec(new Runnable() {
            @Override
            public void run() {
                // Force parent to be drawn now (although this might still be a no-op on GTK)
                parent.update();
                updateUISize();
                commandLineText.setFocus();
            }
        });
    }

    public int getPosition() {
        return commandLineText.getCaretOffset() - contentsOffset;
    }

    public void setPosition(int offset) {
        if (offset < 0) {
            offset = contentsOffset;
        } else if (offset + contentsOffset > commandLineText.getCharCount()) {
            offset = commandLineText.getCharCount();
        } else {
            offset += contentsOffset;
        }
        commandLineText.setCaretOffset(offset);
        //If the caret didn't move, no CaretEvent gets sent. Update manually.
        updateCaret();
    }

    @Override
    public int getSelectionLength() {
        return commandLineText.getSelectionCount();
    }

    @Override
    public int getSelectionStart() {
        Point selection = commandLineText.getSelection();
        int caretOffset = commandLineText.getCaretOffset();
        if (caretOffset == selection.x) {
            return selection.y - contentsOffset;
        } else {
            return selection.x - contentsOffset;
        }
    }

    @Override
    public int getSelectionEnd() {
        return getPosition();
    }

    @Override
    public void setSelection(int start, int end) {
        int startOffset = start + contentsOffset;
        int endOffset = end + contentsOffset;
        if ((startOffset > commandLineText.getCharCount() || start < 0)
                && (endOffset > commandLineText.getCharCount() || end < 0)) {
            throw new IllegalArgumentException("Cannot set command line selection, start offset "
                    + start + " and end offset " + end + " are out of commandline bounds");
        } else if (startOffset > commandLineText.getCharCount() || start < 0) {
            throw new IllegalArgumentException("Cannot set command line selection, start offset "
                    + start + " is out of commandline bounds");
        } else if (endOffset > commandLineText.getCharCount() || end < 0) {
            throw new IllegalArgumentException("Cannot set command line selection, end offset "
                    + end + " is out of commandline bounds");
        }
        commandLineText.setSelection(startOffset, endOffset);
    }

    @Override
    public void close() {
        commandLineText.setVisible(false);
        commandLineText.setEditable(true);
        registerModeSelection = null;
        prompt = "";
        contentsOffset = 0;
        resetContents("");
        commandLineText.setWordWrap(true);
        
        readOnly = false;
        
        copyItem.setEnabled(false);
        cutItem.setEnabled(false);
        pasteItem.setEnabled(true);
    }

    @Override
    public void setMode(CommandLineMode mode) {
        if (mode == CommandLineMode.DEFAULT) {
            commandLineText.setEditable(true);
            if (registerModeSelection != null) {
                commandLineText.replaceTextRange(registerModeSelection.x, 1, "");
                commandLineText.setSelection(registerModeSelection.x);
                registerModeSelection = null;
            } else if (readOnly) {
                //Reset any selection made in a readonly mode, otherwise we need to check if
                // cut/copy needs to be enabled.
                commandLineText.setSelection(commandLineText.getCaretOffset());
            }
            readOnly = false;
        } else if (mode == CommandLineMode.REGISTER) {
            if (registerModeSelection == null) {
                Point sel = commandLineText.getSelection();
                int leftOffset = Math.min(sel.x, sel.y);
                commandLineText.replaceTextRange(leftOffset, 0, "\"");
                registerModeSelection = sel;
                readOnly = true;
            }
        } else if (mode == CommandLineMode.MESSAGE || mode == CommandLineMode.MESSAGE_CLIPPED) {
            commandLineText.setEditable(false);
            setPosition(0);
            commandLineText.setTopIndex(0);
            readOnly = true;
            pasteItem.setEnabled(false);
            commandLineText.setWordWrap(mode == CommandLineMode.MESSAGE);
        }
    }

    @Override
    public String getPrompt() {
        return prompt;
    }

    @Override
    public void addOffsetToPosition(int offset) {
        if (offset == 0) {
            return;
        }
        int newOffset = commandLineText.getCaretOffset() + offset;
        int endOffset = commandLineText.getCharCount();
        if (newOffset > endOffset) {
            newOffset = endOffset;
        } else if (newOffset < contentsOffset) {
            newOffset = contentsOffset;
        }
        commandLineText.setCaretOffset(newOffset);
    }

    @Override
    public void erase() {
        clipSelection();
        if (commandLineText.getSelectionCount() > 0) {
            commandLineText.insert("");
        } else {
            int startOffset = commandLineText.getCaretOffset();
            if (startOffset > contentsOffset) {
                commandLineText.replaceTextRange(startOffset - 1, 1, "");
                //Caret listener is called before content is updated, update manually
                updateCaret();
            }
        }
        updateUISize();
    }

    @Override
    public void delete() {
        clipSelection();
        if (commandLineText.getSelectionCount() > 0) {
            commandLineText.insert("");
        } else {
            int startOffset = commandLineText.getCaretOffset();
            if (startOffset < commandLineText.getCharCount()) {
                commandLineText.replaceTextRange(startOffset, 1, "");
            }
        }
        //caret doesn't move if character after caret is deleted and won't trigger caretMoved
        //update manually
        updateCaret();
    }

    @Override
    public int getEndPosition() {
        return commandLineText.getCharCount() - contentsOffset;
    }

    @Override
    public void replace(int start, int end, String text) {
        int startOffset = start + contentsOffset;
        int endOffset = end + contentsOffset;
        commandLineText.replaceTextRange(startOffset, endOffset - startOffset, text);
        updateUISize();
    }

    @Override
    public void dispose() {
        endCharCaret.dispose();
        cutItem.dispose();
        copyItem.dispose();
        pasteItem.dispose();
        selectAllItem.dispose();
        contextMenu.dispose();
    }

    @Override
    public void caretMoved(CaretEvent event) {
        updateCaret();
    }

    protected void updateCaret() {
        if (commandLineText.getCaretOffset() == commandLineText.getCharCount()) {
            commandLineText.setCaret(endCharCaret);
        } else {
            commandLineText.setCaret(defaultCaret);
        }
    }

    /** Makes sure that the caret or selection never move into the prompt characters. */
    public void clipSelection() {
        if (commandLineText.getSelectionCount() > 0) {
            Point sel = commandLineText.getSelection();
            boolean isReversed = commandLineText.getCaretOffset() == sel.x;
            int leftOffset = sel.x;
            int rightOffset = sel.y;
            if (leftOffset < contentsOffset) {
                leftOffset = contentsOffset;
            }
            if (rightOffset < contentsOffset) {
                rightOffset = contentsOffset;
            }
            Point newSel;
            if (isReversed) {
                newSel = new Point(rightOffset, leftOffset);
            } else {
                newSel = new Point(leftOffset, rightOffset);
            }
            commandLineText.setSelection(newSel);
        } else {
            int offset = commandLineText.getCaretOffset();
            if (offset < contentsOffset) {
                commandLineText.setCaretOffset(contentsOffset);
            }
        }
    }

    @Override
    public void widgetSelected(SelectionEvent e) {
        clipSelection();
        int selectionLen = Math.abs(e.y - e.x);
        cutItem.setEnabled( ! readOnly && selectionLen > 0);
        copyItem.setEnabled(selectionLen > 0);
        pasteItem.setEnabled( ! readOnly);
    }

    @Override
    public void widgetDefaultSelected(SelectionEvent e) {
    }

    public void setMaxHeight(int height) {
        this.maxHeight = height;
    }
    
    public void setWidth(int width) {
        Point size = commandLineText.getSize();
        commandLineText.setSize(width, size.y);
        this.width = width;
    }
    
    public void setBottom(int bottom) {
        this.bottom = bottom;
    }

    protected void updateUISize() {
        // This function can be run asynchronously, check that UI is still usable.
        if (commandLineText == null || commandLineText.isDisposed()) {
            VrapperLog.error("Command line UI is already disposed or nulled.");
            return;
        }
        Composite parent = commandLineText.getParent();
        int width = this.width;
        int maxHeight = this.maxHeight;
        // GTK weirdness: rarely the paint listener on the parent just won't run. Estimate bounds
        if (width == 0) {
            width = parent.getBounds().width;
        }
        if (maxHeight == 0) {
            maxHeight = parent.getBounds().height / 2;
        }
        Point preferredSize = commandLineText.computeSize(width, SWT.DEFAULT, true);
        int selHeight = Math.min(preferredSize.y, maxHeight);
        commandLineText.setSize(width, selHeight);
        if (bottom > 0) {
            // Move the command line higher up to show all of it.
            commandLineText.setLocation(0, bottom - selHeight);
        }
        commandLineText.redraw();
    }

    public boolean isLastLineShown() {
        int bottomLine = commandLineText.getLineIndex(commandLineText.getBounds().y);
        // -1: getLineIndex will always return 0 to getLineCount() - 1.
        return bottomLine >= commandLineText.getLineCount() - 1;
    }

    @Override
    public void scrollDown(boolean wholeScreen) {
        int topLine = commandLineText.getTopIndex();
        if (wholeScreen) {
            int bottomLine = commandLineText.getLineIndex(commandLineText.getBounds().y - 1);
            int nLines = bottomLine - topLine;
            commandLineText.setTopIndex(topLine + nLines);
        } else {
            // For some reason, getTopIndex() seems to return the second fully shown line... No +1
            commandLineText.setTopIndex(topLine);
        }
    }
}

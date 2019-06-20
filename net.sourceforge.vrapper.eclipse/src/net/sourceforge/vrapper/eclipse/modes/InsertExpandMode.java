package net.sourceforge.vrapper.eclipse.modes;

import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.ctrlKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import net.sourceforge.vrapper.eclipse.activator.VrapperPlugin;
import net.sourceforge.vrapper.eclipse.interceptor.InputInterceptor;
import net.sourceforge.vrapper.eclipse.interceptor.UnknownEditorException;
import net.sourceforge.vrapper.keymap.KeyStroke;
import net.sourceforge.vrapper.platform.TextContent;
import net.sourceforge.vrapper.platform.VrapperPlatformException;
import net.sourceforge.vrapper.utils.LineInformation;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.commands.motions.StickyColumnPolicy;
import net.sourceforge.vrapper.vim.modes.InsertMode;

/**
 * See :help ins-completion 
 * For now, we only support the ^L command within InsertExpandMode.
 * Anything else will return to InsertMode.
 */
public class InsertExpandMode extends InsertMode {

    public static final String NAME = InsertExpandMode.class.getName();
	public static final String DISPLAY_NAME = "^X mode (^L^N^P)";
    private static final Pattern indentPattern = Pattern.compile("(^\\s*)\\S*");
    private ITextEditor textEditor;
    private String lastIndent = "";
    private String lastPrefix = "";
    private String lastSuffix = "";
    private List<String> lastMatches = new ArrayList<String>();
    private int lastIndex = 0;
    private int lastLineNo = 0;

    public InsertExpandMode(EditorAdaptor editorAdaptor) {
        super(editorAdaptor);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean handleKey(KeyStroke stroke) {
        if(stroke.equals(ctrlKey('l'))) {
			doLineCompletion(1, true);
			return true;
		} else if (stroke.equals(ctrlKey('n'))) {
			doLineCompletion(1, false);
			return true;
		} else if (stroke.equals(ctrlKey('p'))) {
			doLineCompletion(-1, false);
            return true;
        }
        else {
            //return to insert mode if any other key pressed
			clearLineMatches();
            editorAdaptor.changeModeSafely(InsertMode.NAME, InsertMode.RESUME_ON_MODE_ENTER);
            return super.handleKey(stroke);
        }
    }
    
    // ^X^L
	private void doLineCompletion(int step, boolean nextLine) {
		TextContent model = editorAdaptor.getModelContent();
        Position cursorPos = editorAdaptor.getCursorService().getPosition();
        int cursorOffset = cursorPos.getModelOffset();
        LineInformation lineInfo = model.getLineInformationOfOffset(cursorOffset);
        String line = model.getText(lineInfo.getBeginOffset(), lineInfo.getLength());
		if (lastLineNo != lineInfo.getNumber() || (!line.equals(lastIndent + lastPrefix) && lastIndex == 0)) {
			rebuildLineMatches(cursorOffset - lineInfo.getBeginOffset(), line, lineInfo.getNumber());
        }

        if(lastMatches.size() == 1) {
            editorAdaptor.getUserInterfaceService().setErrorMessage("Pattern not found");
        }
        else {
			lastIndex = (lastIndex + step) % lastMatches.size();
            String newString = lastIndent + lastMatches.get(lastIndex) + lastSuffix;
            model.replace(lineInfo.getBeginOffset(), lineInfo.getLength(), newString);
            int newCursor = lineInfo.getBeginOffset() + lastIndent.length() + lastMatches.get(lastIndex).length();
            editorAdaptor.getCursorService().setPosition(cursorPos.setModelOffset(newCursor), StickyColumnPolicy.ON_CHANGE);
            if(lastIndex == 0) {
                editorAdaptor.getUserInterfaceService().setInfoMessage("Back at original");
            }
            else {
                editorAdaptor.getUserInterfaceService().setInfoMessage("match " + lastIndex + " of " + (lastMatches.size() -1));
            }
        }
    }
    
    /**
     * Go through the entire file and build a list of all lines
     * that start with the same prefix as this line (at the cursor position).
     * 
     * @param cursorPos - index into 'line' where cursor is
     * @param line - line of text in the file
     * @param lineNo - line number of 'line'
     */
    private void rebuildLineMatches(int cursorPos, String line, int lineNo) {
        List<IDocument> hippieDocuments = computeDocuments(getCurrentTextEditor());

    	Matcher matcher = indentPattern.matcher(line);
    	if(matcher.find()) {
    	    lastIndent = matcher.group(1);
    	}
    	else {
    	    lastIndent = "";
    	}

		lastPrefix = line.substring(lastIndent.length(), cursorPos);
		lastSuffix = line.substring(cursorPos);
		lastLineNo = lineNo;
		// will be incremented to 1 on first iteration
		lastIndex = 0;
		lastMatches.clear();
		// index 0 will be original state
		lastMatches.add(lastPrefix);

		// rebuild list of all lines that start with lastPrefix
		IRegion matchLine;
		String matchText;
		Set<String> alreadySeen = new HashSet<String>();
		alreadySeen.add(lastPrefix); // ignore current text
		alreadySeen.add(""); // ignore empty lines
		try {
			for (IDocument model : hippieDocuments) {
				for (int i = 0; i < model.getNumberOfLines(); i++) {
					matchLine = model.getLineInformation(i);
					matchText = model.get(matchLine.getOffset(), matchLine.getLength());
					matchText = matchText.replaceFirst("^\\s+", "");
					if (matchText.startsWith(lastPrefix) && !alreadySeen.contains(matchText)) {
						lastMatches.add(matchText);
						alreadySeen.add(matchText);
					}
				}
			}
		} catch (BadLocationException e) {
			throw new VrapperPlatformException("Failed to find line completions", e);
		}
	}

	/*
	 * Based on org.eclipse.ui.internal.texteditor.HippieCompletionEngine
	 */
	private List<IDocument> computeDocuments(ITextEditor currentTextEditor) {
		List<IDocument> documentsForSearch = new ArrayList<IDocument>();
		if (currentTextEditor == null) {
			return documentsForSearch;
		}

		IDocumentProvider provider = currentTextEditor.getDocumentProvider();
		if (provider == null) {
			return documentsForSearch;
		}

		IDocument currentDocument = provider.getDocument(currentTextEditor.getEditorInput());
		if (currentDocument == null) {
			return documentsForSearch;
		}

		List<IDocument> computedDocuments = new ArrayList<IDocument>();
		IWorkbenchWindow window = currentTextEditor.getSite().getWorkbenchWindow();
		IEditorReference editorsArray[] = window.getActivePage().getEditorReferences();

		for (int i = 0; i < editorsArray.length; i++) {
			IEditorPart realEditor = editorsArray[i].getEditor(false);
			if (realEditor instanceof ITextEditor && !realEditor.equals(currentTextEditor)) {
				ITextEditor textEditor = (ITextEditor) realEditor;
				provider = textEditor.getDocumentProvider();
				if (provider == null) {
					continue;
				}
				IDocument doc = provider.getDocument(textEditor.getEditorInput());
				if (doc == null) {
					continue;
				}
				computedDocuments.add(doc);
			}
		}

		// The first is always the one related to the passed currentTextEditor.
		computedDocuments.add(0, currentDocument);
		return computedDocuments;
	}

	private void clearLineMatches() {
		// will be incremented to 1 on first iteration
		lastIndex = 0;
		lastMatches.clear();
		lastLineNo = 0;
		lastIndent = "";
		lastPrefix = "";
		lastSuffix = "";
	}

    private ITextEditor getCurrentTextEditor() {
        if (textEditor == null) {
            InputInterceptor interceptor;
            try {
                interceptor = VrapperPlugin.getDefault().findActiveInterceptor();
            } catch (VrapperPlatformException e) {
                VrapperPlatformException e2 =  new VrapperPlatformException(
                        "Failed to get current text editor for InsertExpandMode.");
                e2.initCause(e);
                throw e2;
            } catch (UnknownEditorException e) {
                VrapperPlatformException e2 = new VrapperPlatformException(
                        "Failed to get current text editor for InsertExpandMode.");
                e2.initCause(e);
                throw e2;
            }
            // Check that the currently active interceptor is the one which owns this mode instance
            if ( ! editorAdaptor.equals(interceptor.getEditorAdaptor())) {
                throw new VrapperPlatformException("Encountered invalid EditorAdaptor");
            }
            textEditor = interceptor.getPlatform().getUnderlyingEditor();
        }
        return textEditor;
    }
}

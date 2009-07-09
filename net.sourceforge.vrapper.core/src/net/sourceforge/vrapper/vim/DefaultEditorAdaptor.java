package net.sourceforge.vrapper.vim;

import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import net.sourceforge.vrapper.keymap.KeyMap;
import net.sourceforge.vrapper.keymap.KeyStroke;
import net.sourceforge.vrapper.log.VrapperLog;
import net.sourceforge.vrapper.platform.Configuration;
import net.sourceforge.vrapper.platform.CursorService;
import net.sourceforge.vrapper.platform.FileService;
import net.sourceforge.vrapper.platform.HistoryService;
import net.sourceforge.vrapper.platform.KeyMapProvider;
import net.sourceforge.vrapper.platform.Platform;
import net.sourceforge.vrapper.platform.SelectionService;
import net.sourceforge.vrapper.platform.ServiceProvider;
import net.sourceforge.vrapper.platform.TextContent;
import net.sourceforge.vrapper.platform.UnderlyingEditorSettings;
import net.sourceforge.vrapper.platform.UserInterfaceService;
import net.sourceforge.vrapper.platform.ViewportService;
import net.sourceforge.vrapper.utils.LineInformation;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.vim.commands.Selection;
import net.sourceforge.vrapper.vim.modes.CommandLineMode;
import net.sourceforge.vrapper.vim.modes.EditorMode;
import net.sourceforge.vrapper.vim.modes.InsertMode;
import net.sourceforge.vrapper.vim.modes.LinewiseVisualMode;
import net.sourceforge.vrapper.vim.modes.NormalMode;
import net.sourceforge.vrapper.vim.modes.ReplaceMode;
import net.sourceforge.vrapper.vim.modes.SearchMode;
import net.sourceforge.vrapper.vim.modes.VisualMode;
import net.sourceforge.vrapper.vim.modes.commandline.CommandLineParser;
import net.sourceforge.vrapper.vim.register.DefaultRegisterManager;
import net.sourceforge.vrapper.vim.register.RegisterManager;

public class DefaultEditorAdaptor implements EditorAdaptor {

    private static final String CONFIG_FILE_NAME = ".vrapperrc";
    private EditorMode currentMode;
    private final Map<String, EditorMode> modeMap = new HashMap<String, EditorMode>();
    private final TextContent modelContent;
    private final TextContent viewContent;
    private final CursorService cursorService;
    private final SelectionService selectionService;
    private final FileService fileService;
    private RegisterManager registerManager;
    private final RegisterManager globalRegisterManager;
    private final ViewportService viewportService;
    private final HistoryService historyService;
    private final UserInterfaceService userInterfaceService;
    private final ServiceProvider serviceProvider;
    private final KeyStrokeTranslator keyStrokeTranslator;
    private final KeyMapProvider keyMapProvider;
    private final UnderlyingEditorSettings editorSettings;
    private final Configuration configuration;

    public DefaultEditorAdaptor(Platform editor, RegisterManager registerManager) {
        this.modelContent = editor.getModelContent();
        this.viewContent = editor.getViewContent();
        this.cursorService = editor.getCursorService();
        this.selectionService = editor.getSelectionService();
        this.historyService = editor.getHistoryService();
        this.registerManager = registerManager;
        this.globalRegisterManager = registerManager;
        this.serviceProvider = editor.getServiceProvider();
        this.editorSettings = editor.getUnderlyingEditorSettings();
        this.configuration = editor.getConfiguration();
        viewportService = editor.getViewportService();
        userInterfaceService = editor.getUserInterfaceService();
        keyStrokeTranslator = new KeyStrokeTranslator();
        keyMapProvider = editor.getKeyMapProvider();

        fileService = editor.getFileService();
        EditorMode[] modes = {
                new NormalMode(this),
                new VisualMode(this),
                new LinewiseVisualMode(this),
                new InsertMode(this),
                new ReplaceMode(this),
                new CommandLineMode(this),
                new SearchMode(this)};
        for (EditorMode mode: modes) {
            modeMap.put(mode.getName(), mode);
        }
        readConfiguration();
        setNewLineFromFirstLine();
        changeMode(NormalMode.NAME);
    }

    private void setNewLineFromFirstLine() {
        if (modelContent.getNumberOfLines() > 1) {
            LineInformation first = modelContent.getLineInformation(0);
            LineInformation second = modelContent.getLineInformation(1);
            int start = first.getEndOffset();
            int end = second.getBeginOffset();
            String newLine = modelContent.getText(start, end-start);
            configuration.setNewLine(newLine);
        }
    }

    private void readConfiguration() {
        File homeDir = new File(System.getProperty("user.home"));
        File config = new File(homeDir, CONFIG_FILE_NAME);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(config));
            String line;
            CommandLineParser parser = new CommandLineParser(this);
            while((line = reader.readLine()) != null) {
                parser.parseAndExecute(null, line.trim());
            }
        } catch (FileNotFoundException e) {
            // ignore
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    public void changeMode(String modeName, Object... args) {
        EditorMode newMode = modeMap.get(modeName);
        if (newMode == null) {
            VrapperLog.error(format("There is no mode named '%s'",  modeName));
            return;
        }
        if (currentMode != newMode) {
            if (currentMode != null) {
                currentMode.leaveMode();
            }
            currentMode = newMode;
            newMode.enterMode(args);
        }
        userInterfaceService.setEditorMode(newMode.getName());
    }

    public boolean handleKey(KeyStroke key) {
        if (currentMode != null) {
            KeyMap map = currentMode.resolveKeyMap(keyMapProvider);
            if (map != null) {
                boolean inMapping = keyStrokeTranslator.processKeyStroke(map, key);
                if (inMapping) {
                    Queue<RemappedKeyStroke> resultingKeyStrokes =
                        keyStrokeTranslator.resultingKeyStrokes();
                    while (!resultingKeyStrokes.isEmpty()) {
                        RemappedKeyStroke next = resultingKeyStrokes.poll();
                        if (next.isRecursive()) {
                            handleKey(next);
                        } else {
                            currentMode.handleKey(next);
                        }
                    }
                    return true;
                }
            }
            return currentMode.handleKey(key);
        }
        return false;
    }

    public TextContent getModelContent() {
        return modelContent;
    }

    public TextContent getViewContent() {
        return viewContent;
    }

    public Position getPosition() {
        return cursorService.getPosition();
    }

    public void setPosition(Position destination, boolean updateStickyColumn) {
        cursorService.setPosition(destination, updateStickyColumn);
    }

    public Selection getSelection() {
        return selectionService.getSelection();
    }

    public void setSelection(Selection selection) {
        selectionService.setSelection(selection);
    }

    public CursorService getCursorService() {
        return cursorService;
    }

    public FileService getFileService() {
        return fileService;
    }

    public ViewportService getViewportService() {
        return viewportService;
    }

    public UserInterfaceService getUserInterfaceService() {
        return userInterfaceService;
    }

    public RegisterManager getRegisterManager() {
        return registerManager;
    }

    public HistoryService getHistory() {
        return historyService;
    }

    public <T> T getService(Class<T> serviceClass) {
        return serviceProvider.getService(serviceClass);
    }

    public EditorMode getMode(String name) {
        return modeMap.get(name);
    }

    public KeyMapProvider getKeyMapProvider() {
        return keyMapProvider;
    }

    public UnderlyingEditorSettings getEditorSettings() {
        return editorSettings;
    }

    public void useGlobalRegisters() {
        registerManager = globalRegisterManager;
    }

    public void useLocalRegisters() {
        registerManager = new DefaultRegisterManager();
    }

    public Configuration getConfiguration() {
        return configuration;
    }

}


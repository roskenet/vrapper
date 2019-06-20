package net.sourceforge.vrapper.vim.commands;

import net.sourceforge.vrapper.platform.Configuration;
import net.sourceforge.vrapper.utils.ContentType;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.utils.StartEndTextRange;
import net.sourceforge.vrapper.utils.TextRange;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.commands.motions.Motion;

public class MotionPairTextObject extends AbstractTextObject {

    private final Motion toBeginning;
    private final Motion toEnd;
    
    public MotionPairTextObject(Motion toBeginning, Motion toEnd) {
        this.toBeginning = toBeginning;
        this.toEnd = toEnd;
    }

    public TextRange getRegion(EditorAdaptor editorMode, int count) throws CommandExecutionException {
        Position currentPosition = editorMode.getPosition();
        Motion leftMotion = toBeginning;
        Motion rightMotion = toEnd.withCount(count);
        Position from = leftMotion.destination(editorMode, currentPosition);
        Position to = rightMotion.destination(editorMode, currentPosition);
        if (toEnd.borderPolicy() == BorderPolicy.INCLUSIVE) {
            to = to.addModelOffset(1);
        }
        return new StartEndTextRange(from, to);
    }

    public ContentType getContentType(Configuration configuration) {
        return ContentType.TEXT;
    }

}

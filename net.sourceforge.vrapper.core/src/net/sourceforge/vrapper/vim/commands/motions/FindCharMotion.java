package net.sourceforge.vrapper.vim.commands.motions;

import net.sourceforge.vrapper.keymap.KeyStroke;
import net.sourceforge.vrapper.platform.TextContent;
import net.sourceforge.vrapper.utils.Function;
import net.sourceforge.vrapper.utils.LineInformation;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.commands.BorderPolicy;
import net.sourceforge.vrapper.vim.commands.CommandExecutionException;

/** Motion responsible for 't', 'T', 'f' and 'F' motions.
 * Finds next occurrence of a character in current line.
 * 
 * @author Krzysiek Goj
 */
public class FindCharMotion extends FindBalancedMotion implements NavigatingMotion {

    public static Function<Motion, KeyStroke> keyConverter(final boolean upToTarget, final boolean reversed) {
        return new Function<Motion, KeyStroke>() {
            public Motion call(KeyStroke keyStroke) {
                return new FindCharMotion(keyStroke.getCharacter(), upToTarget, reversed).registrator;
            }
        };

    }

    protected boolean isRepetition;

    public FindCharMotion(char target, boolean upToTarget, boolean reversed) {
        super(target, '\0', upToTarget, reversed, true);
    }

    @Override
    public FindCharMotion repetition() {
        FindCharMotion repetition = new FindCharMotion(target, upToTarget, backwards);
        repetition.isRepetition = true;
        return repetition;
    }

    @Override
    public FindCharMotion reverse() {
        return new FindCharMotion(target, upToTarget, !backwards);
    }

    @Override
    public boolean isBackward() {
        return backwards;
    }

    @Override
    protected int getEndSearchOffset(TextContent content, int offset) {
        LineInformation line = content.getLineInformationOfOffset(offset);
        int end = backwards ? line.getBeginOffset() : line.getEndOffset() - 1;
        return end;
    }

    @Override
    public Position destination(EditorAdaptor editorAdaptor, int count, Position fromPosition)
            throws CommandExecutionException {

        Position dest = super.destination(editorAdaptor, count, fromPosition);

        // If repeating 't' / 'T' and the cursor is before the last match, destination() will think
        // this position is the next match and not move the cursor. If this happens, move the cursor
        // forward or back so it's on top of the last match and run destination() again.
        if (isRepetition && ! upToTarget && fromPosition.getModelOffset() == dest.getModelOffset()) {

            Position tweakPos = dest.addModelOffset(backwards ? -1 : 1);
            dest = super.destination(editorAdaptor, count, tweakPos);
        }
        return dest;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> type) {
        if (NavigatingMotion.class.equals(type)) {
            return (T) this;
        } else if (FindCharMotion.class.equals(type)) {
            return (T) this;
        }
        return super.getAdapter(type);
    }

    private Motion registrator = new CountAwareMotion() {
        @Override
        public Position destination(EditorAdaptor editorAdaptor, int count, Position fromPosition)
                throws CommandExecutionException {
            editorAdaptor.getRegisterManager().setLastFindCharMotion(FindCharMotion.this.repetition());
            return FindCharMotion.this.destination(editorAdaptor, count, fromPosition);
        }

        public BorderPolicy borderPolicy() {
            return FindCharMotion.this.borderPolicy();
        }

        public StickyColumnPolicy stickyColumnPolicy() {
            return FindCharMotion.this.stickyColumnPolicy();
        }

        @Override
        public <T> T getAdapter(Class<T> type) {
            return FindCharMotion.this.getAdapter(type);
        }
    };

}

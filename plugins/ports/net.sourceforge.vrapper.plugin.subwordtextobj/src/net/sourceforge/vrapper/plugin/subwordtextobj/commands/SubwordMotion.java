package net.sourceforge.vrapper.plugin.subwordtextobj.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sourceforge.vrapper.platform.Configuration;
import net.sourceforge.vrapper.platform.CursorService;
import net.sourceforge.vrapper.platform.TextContent;
import net.sourceforge.vrapper.utils.ContentType;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.utils.StartEndTextRange;
import net.sourceforge.vrapper.utils.TextRange;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.Options;
import net.sourceforge.vrapper.vim.commands.AbstractTextObject;
import net.sourceforge.vrapper.vim.commands.BorderPolicy;
import net.sourceforge.vrapper.vim.commands.CommandExecutionException;
import net.sourceforge.vrapper.vim.commands.TextObject;
import net.sourceforge.vrapper.vim.commands.Utils;
import net.sourceforge.vrapper.vim.commands.motions.CountAwareMotion;
import net.sourceforge.vrapper.vim.commands.motions.Motion;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordEndRight;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordLeft;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordRight;
import net.sourceforge.vrapper.vim.commands.motions.StickyColumnPolicy;

/**
 * Define Motions (and TextObjects) for navigating camelCase and snake_case words.
 * '\b' '\e' '\w' moves between words of a camelCase or snake_case name.
 * Text objects 'i\' and 'a\' determine whether the '_' after the word will be included.
 * 
 * All these Motions are based on the moveWordLeft/Right Motion so the behavior is the
 * same as 'b' 'e' 'w' if no camelCase or snake_case boundaries are found.
 */
public class SubwordMotion extends CountAwareMotion {

    private static enum Limit { BACK, END, WORD };

    public static final Motion SUB_BACK = new SubwordMotion(Limit.BACK);
    public static final Motion SUB_END = new SubwordMotion(Limit.END);
    public static final Motion SUB_WORD = new SubwordMotion(Limit.WORD);
    
    private final Pattern camelPattern = Pattern.compile("(?<=[0-9a-z])([A-Z]+)([0-9a-z]|$)");
    private final Pattern camelPatternEnd = Pattern.compile("([0-9a-z])[A-Z]+([0-9a-z]|$)");
    private final Pattern snakePattern = Pattern.compile("[_]+([0-9a-zA-Z])");
    private final Pattern snakePatternEnd = Pattern.compile("([0-9a-zA-Z])[_]");

    private final Limit limit;
    
    private SubwordMotion(Limit limit) {
        this.limit = limit;
    }

    @Override
    public Position destination(EditorAdaptor editorAdaptor, int count, Position fromPosition) throws CommandExecutionException {
        if(count == NO_COUNT_GIVEN)
            count = 1;

        int positionOrig = fromPosition.getModelOffset();
        int toOffset = positionOrig;

        for (int i = 0; i < count; i++) {
            positionOrig = toOffset;
        	toOffset = doIt(editorAdaptor, fromPosition, toOffset);

        	if(limit == Limit.END && toOffset == positionOrig && toOffset < editorAdaptor.getModelContent().getTextLength()) {
        	    //if we matched on our self, increase and try again
        	    toOffset = doIt(editorAdaptor, fromPosition, ++toOffset);
        	}
        }

        return editorAdaptor.getCursorService().newPositionForModelOffset(toOffset);
    }
    
    private int doIt(EditorAdaptor editorAdaptor, Position fromPosition, int toOffset) throws CommandExecutionException {
        TextContent model = editorAdaptor.getModelContent();
        Position cursor = editorAdaptor.getCursorService().newPositionForModelOffset(toOffset);

        Motion wordMotion;
        switch(limit) {
        case BACK:
            wordMotion = MoveWordLeft.INSTANCE;
            break;
        case END:
            wordMotion = MoveWordEndRight.INSTANCE;
            break;
        case WORD:
        default: //<-- won't happen but it makes the compiler happy
            wordMotion = MoveWordRight.INSTANCE;
            break;
        }
        //find where the regular word motion would take us
        Position dest = wordMotion.destination(editorAdaptor, fromPosition);
        
        //StartEndTextRange can handle cursor < dest
        //I just use getLeftBound() when returning so I don't have to check which is bigger
        TextRange wordRange = new StartEndTextRange(dest, cursor);
        String word = model.getText(wordRange);
        
        List<Integer> matches = new ArrayList<Integer>();
        boolean matchBoth = false;
        Matcher matcher;

        //if snake_case
        if(word.contains("_")) {
            matcher = limit == Limit.END ? snakePatternEnd.matcher(word) : snakePattern.matcher(word);
            while(matcher.find()) { matches.add(matcher.start(1)); }
        }

        //if a mix of lower-case and upper-case, camelCase (ignore ALL_CAPS and all_lower)
        if( !word.equals(word.toLowerCase()) && !word.equals(word.toUpperCase())) {
            if(matches.size() > 0) {
                //handle the corner case of both snake_case and camelCase in the same word
                //but really, who does that??
                matchBoth = true;
            }
            matcher = limit == Limit.END ? camelPatternEnd.matcher(word) : camelPattern.matcher(word);
            while(matcher.find()) { matches.add(matcher.start(1)); }
        }

        if(matchBoth) {
            //re-order if this word has both camelCase and snake_case
            //(this will ensure we get the first index of whichever format came first)
            Collections.sort(matches);
        }

        int offset = 0;
        if(matches.size() > 0) {
            //if moving backwards get last match, if moving forwards get first match
            offset = limit == Limit.BACK ? matches.get( matches.size() -1) : matches.get(0);
        }
        else { //no sub-words found, match on word boundary (beginning/end of the string)
            offset = limit == Limit.BACK ? 0 : word.length();
        }
        
        return wordRange.getLeftBound().getModelOffset() + offset;
    }

    @Override
    public BorderPolicy borderPolicy() {
        return limit == Limit.END ? BorderPolicy.INCLUSIVE : BorderPolicy.EXCLUSIVE;
    }

    @Override
    public StickyColumnPolicy stickyColumnPolicy() {
        return StickyColumnPolicy.ON_CHANGE;
    }

    public static class SubwordTextObject extends AbstractTextObject {

        public static final TextObject INSTANCE = new SubwordTextObject(false);
        public static final TextObject INSTANCE_OUTER = new SubwordTextObject(true);

        private final boolean outer;

        private SubwordTextObject(boolean outer) {
            this.outer = outer;
        }

        @Override
        public TextRange getRegion(EditorAdaptor editorAdaptor, int count) throws CommandExecutionException {
            if(count == NO_COUNT_GIVEN)
                count = 1;

        	TextContent model = editorAdaptor.getModelContent();
        	CursorService cursorService = editorAdaptor.getCursorService();

            Motion startMotion = SubwordMotion.SUB_BACK;
            Motion endMotion = SubwordMotion.SUB_WORD;
            Motion lastEndMotion = outer ? SubwordMotion.SUB_WORD : SubwordMotion.SUB_END;

            String wordRegex = editorAdaptor.getConfiguration().get(Options.KEYWORDS);
            Position fromPosition = editorAdaptor.getPosition();
            int cursorOffset = fromPosition.getModelOffset();

            // VIM-camelcasemotion plugin is able to move [count]subwords backwards.
            // This feature is not yet supported by this implementation.
            int startOffset;
            String startChars = cursorOffset > 0 ? model.getText(cursorOffset -1, 2) : "";
            if (startChars.length() == 2
                    && Utils.characterType(startChars.charAt(0), wordRegex) != Utils.WORD
                    && Utils.characterType(startChars.charAt(1), wordRegex) == Utils.WORD) {
                //If the cursor is on the beginning of a word, the 'b'ack motion will jump to the previous word.
                //This is the correct behavior for motion, but not what we want for text object.
                startOffset = cursorOffset;
            }
            else {
                //offset+1 to include the character under the cursor
                //(this operation will search backwards)
                startOffset = ((SubwordMotion)startMotion).doIt(editorAdaptor, fromPosition, cursorOffset + 1);
            }
            Position start = cursorService.newPositionForModelOffset(startOffset);
			int endOffset = cursorOffset;

			//find where the regular end-word motion would take us
			MoveWordEndRight wordMotion = MoveWordEndRight.INSTANCE;
			Position endWord = wordMotion.destination(editorAdaptor, fromPosition);
			int endWordOffset = endWord.getModelOffset();

			boolean hitEndOfTheWord = false;
			for (int i = count; i != 0; i--) {
			    //using doIt rather than destination so we don't try "fixing" when we match on self
			    if (i > 1) {
			        endOffset = ((SubwordMotion)endMotion).doIt(editorAdaptor, fromPosition, endOffset);
			    } else {
			        endOffset = ((SubwordMotion)lastEndMotion).doIt(editorAdaptor, fromPosition, endOffset);
			    }
			    if (endOffset > endWordOffset) {
			        endOffset = endWordOffset;
			        hitEndOfTheWord = true;
			        break;
			    }
			}

			Position end = cursorService.newPositionForModelOffset(endOffset);
			
			if(! outer || hitEndOfTheWord) {
			    //inclusive vs. exclusive in motion vs. text object
			    //motion is exclusive, inner text object is inclusive
			    end = end.addModelOffset(1);
			}

            return new StartEndTextRange(start, end);
        }

        public ContentType getContentType(Configuration configuration) {
            return ContentType.TEXT;
        }

    }

}

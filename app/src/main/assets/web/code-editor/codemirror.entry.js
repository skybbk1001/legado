import { EditorState, Compartment } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter } from "@codemirror/view";
import { history, undo, redo, insertNewlineAndIndent, indentMore, indentLess } from "@codemirror/commands";
import { indentOnInput, bracketMatching, syntaxHighlighting, defaultHighlightStyle, indentUnit, foldGutter } from "@codemirror/language";
import { highlightSelectionMatches, openSearchPanel } from "@codemirror/search";
import { autocompletion, closeBrackets, closeBracketsKeymap, startCompletion, completeAnyWord } from "@codemirror/autocomplete";
import { javascript } from "@codemirror/lang-javascript";

export {
  EditorState,
  Compartment,
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLineGutter,
  history,
  undo,
  redo,
  insertNewlineAndIndent,
  indentMore,
  indentLess,
  indentOnInput,
  bracketMatching,
  syntaxHighlighting,
  defaultHighlightStyle,
  indentUnit,
  foldGutter,
  highlightSelectionMatches,
  openSearchPanel,
  autocompletion,
  startCompletion,
  completeAnyWord,
  closeBrackets,
  closeBracketsKeymap,
  javascript
};

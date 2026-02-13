import { EditorState, Compartment, Transaction } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter } from "@codemirror/view";
import { history, undo, redo, insertNewlineAndIndent, indentMore, indentLess } from "@codemirror/commands";
import { indentOnInput, bracketMatching, syntaxHighlighting, defaultHighlightStyle, indentUnit, foldGutter } from "@codemirror/language";
import { highlightSelectionMatches, openSearchPanel } from "@codemirror/search";
import { autocompletion, closeCompletion, closeBrackets, closeBracketsKeymap, startCompletion, completeAnyWord } from "@codemirror/autocomplete";
import { javascript } from "@codemirror/lang-javascript";

export {
  EditorState,
  Compartment,
  Transaction,
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
  closeCompletion,
  startCompletion,
  completeAnyWord,
  closeBrackets,
  closeBracketsKeymap,
  javascript
};

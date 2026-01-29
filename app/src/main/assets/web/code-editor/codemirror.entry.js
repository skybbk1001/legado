import { EditorState, Compartment } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter } from "@codemirror/view";
import { history, undo, redo } from "@codemirror/commands";
import { indentOnInput, bracketMatching, syntaxHighlighting, defaultHighlightStyle, indentUnit } from "@codemirror/language";
import { foldGutter } from "@codemirror/fold";
import { highlightSelectionMatches, openSearchPanel } from "@codemirror/search";
import { autocompletion } from "@codemirror/autocomplete";
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
  indentOnInput,
  bracketMatching,
  syntaxHighlighting,
  defaultHighlightStyle,
  indentUnit,
  foldGutter,
  highlightSelectionMatches,
  openSearchPanel,
  autocompletion,
  javascript
};

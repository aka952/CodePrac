import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor/editor/editor.api'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import 'monaco-editor/languages/definitions/java/register'
import 'monaco-editor/languages/definitions/python/register'

self.MonacoEnvironment = { getWorker: () => new EditorWorker() }

/** Editor colours modelled on IntelliJ IDEA's classic dark scheme. */
export const EDITOR_THEME = 'idea-dark'
monaco.editor.defineTheme(EDITOR_THEME, {
  base: 'vs-dark',
  inherit: true,
  rules: [
    { token: '', foreground: 'A9B7C6' },
    { token: 'keyword', foreground: 'CC7832' },
    { token: 'string', foreground: '6A8759' },
    { token: 'string.escape', foreground: 'CC7832' },
    { token: 'number', foreground: '6897BB' },
    { token: 'comment', foreground: '808080' },
    { token: 'comment.doc', foreground: '629755', fontStyle: 'italic' },
    { token: 'annotation', foreground: 'BBB529' },
    { token: 'tag', foreground: 'BBB529' },
    { token: 'type', foreground: 'A9B7C6' },
    { token: 'identifier', foreground: 'A9B7C6' },
    { token: 'delimiter', foreground: 'A9B7C6' },
    { token: 'operator', foreground: 'A9B7C6' },
    { token: 'constant', foreground: '9876AA' },
  ],
  colors: {
    'editor.background': '#2B2B2B',
    'editor.foreground': '#A9B7C6',
    'editorGutter.background': '#313335',
    'editorLineNumber.foreground': '#606366',
    'editorLineNumber.activeForeground': '#A4A3A3',
    'editor.lineHighlightBackground': '#323232',
    'editor.lineHighlightBorder': '#00000000',
    'editor.selectionBackground': '#214283',
    'editor.inactiveSelectionBackground': '#21428399',
    'editorCursor.foreground': '#BBBBBB',
    'editorIndentGuide.background1': '#373737',
    'editorIndentGuide.activeBackground1': '#505050',
    'editorBracketMatch.background': '#3B514D',
    'editorBracketMatch.border': '#00000000',
    'editorBracketHighlight.foreground1': '#A9B7C6',
    'editorBracketHighlight.foreground2': '#A9B7C6',
    'editorBracketHighlight.foreground3': '#A9B7C6',
    'editorBracketHighlight.foreground4': '#A9B7C6',
    'editorBracketHighlight.foreground5': '#A9B7C6',
    'editorBracketHighlight.foreground6': '#A9B7C6',
    'editorWidget.background': '#3C3F41',
    'editorWidget.border': '#555555',
    'scrollbarSlider.background': '#A6A6A633',
    'scrollbarSlider.hoverBackground': '#A6A6A655',
  },
})
loader.config({ monaco: monaco as unknown as typeof import('monaco-editor') })

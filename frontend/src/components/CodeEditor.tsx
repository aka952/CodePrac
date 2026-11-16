import Editor, { type OnMount } from '@monaco-editor/react'
import { useEffect, useRef } from 'react'
import '../monaco'

type MonacoEditor = Parameters<OnMount>[0]
type Monaco = Parameters<OnMount>[1]

interface Props {
  path?: string
  language: string
  value: string
  readOnly: boolean
  onChange?: (value: string) => void
  selectableLines?: boolean
  selectedLine?: number | null
  answerLine?: number | null
  onSelectLine?: (line: number) => void
  onRun?: () => void
}

export default function CodeEditor({ path, language, value, readOnly, onChange, selectableLines, selectedLine, answerLine, onSelectLine, onRun }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null)
  const monacoRef = useRef<Monaco | null>(null)
  const decorations = useRef<ReturnType<MonacoEditor['createDecorationsCollection']> | null>(null)
  const latest = useRef({ selectableLines, onSelectLine, onRun })
  latest.current = { selectableLines, onSelectLine, onRun }

  const handleMount: OnMount = (editor, monaco) => {
    editorRef.current = editor
    monacoRef.current = monaco
    decorations.current = editor.createDecorationsCollection()
    editor.onMouseDown(e => {
      const line = e.target.position?.lineNumber
      if (line && latest.current.selectableLines) latest.current.onSelectLine?.(line)
    })
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => latest.current.onRun?.())
    paint()
  }

  const paint = () => {
    const monaco = monacoRef.current
    if (!monaco || !decorations.current) return
    const line = (n: number, className: string) => ({
      range: new monaco.Range(n, 1, n, 1),
      options: { isWholeLine: true, className, linesDecorationsClassName: `${className}-gutter` },
    })
    decorations.current.set([
      ...(selectedLine && selectedLine !== answerLine ? [line(selectedLine, 'line-selected')] : []),
      ...(answerLine ? [line(answerLine, 'line-answer')] : []),
    ])
    if (answerLine) editorRef.current?.revealLineInCenterIfOutsideViewport(answerLine)
  }

  useEffect(paint, [selectedLine, answerLine, path])

  const lines = value.split('\n').length
  const height = Math.min(Math.max(lines * 21 + 24, 160), 640)

  return (
    <div className={`editor-wrap${selectableLines ? ' selectable' : ''}${readOnly ? ' readonly' : ''}`}>
      <Editor
        height={height}
        path={path}
        language={language}
        value={value}
        theme="vs-dark"
        onMount={handleMount}
        onChange={v => onChange?.(v ?? '')}
        options={{
          readOnly,
          domReadOnly: readOnly,
          minimap: { enabled: false },
          fontSize: 14,
          lineHeight: 21,
          fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, SFMono-Regular, Menlo, monospace",
          scrollBeyondLastLine: false,
          renderLineHighlight: readOnly ? 'none' : 'line',
          contextmenu: !readOnly,
          padding: { top: 12, bottom: 12 },
          tabSize: 4,
          automaticLayout: true,
        }}
      />
    </div>
  )
}

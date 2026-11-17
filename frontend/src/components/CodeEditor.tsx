import Editor, { type OnMount } from '@monaco-editor/react'
import { useEffect, useRef, useState } from 'react'
import { EDITOR_THEME } from '../monaco'

type MonacoEditor = Parameters<OnMount>[0]
type Monaco = Parameters<OnMount>[1]
type Selection = NonNullable<ReturnType<MonacoEditor['getSelection']>>

export interface AiEdit { replacement: string; note: string | null }

const AI_BOX_WIDTH = 400

interface Props {
  language: string
  value: string
  readOnly: boolean
  onChange?: (value: string) => void
  selectableLines?: boolean
  selectedLine?: number | null
  answerLine?: number | null
  onSelectLine?: (line: number) => void
  onRun?: () => void
  /** Enables the inline AI edit box on selections (only while the editor is editable). Resolves to the rewritten selection. */
  onAiEdit?: (selection: string, instruction: string) => Promise<AiEdit>
}

export default function CodeEditor({ language, value, readOnly, onChange, selectableLines, selectedLine, answerLine, onSelectLine, onRun, onAiEdit }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null)
  const monacoRef = useRef<Monaco | null>(null)
  const decorations = useRef<ReturnType<MonacoEditor['createDecorationsCollection']> | null>(null)
  const latest = useRef({ selectableLines, onSelectLine, onRun, onAiEdit, readOnly })
  latest.current = { selectableLines, onSelectLine, onRun, onAiEdit, readOnly }

  // inline AI edit: a small box under the current selection
  const [box, setBox] = useState<{ top: number; left: number } | null>(null)
  const [instruction, setInstruction] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const target = useRef<Selection | null>(null)
  const busyRef = useRef(false)

  const closeBox = () => {
    setBox(null)
    setInstruction('')
    setMessage(null)
  }

  const placeBox = () => {
    const editor = editorRef.current
    if (!editor || busyRef.current) return
    const selection = editor.getSelection()
    if (!latest.current.onAiEdit || latest.current.readOnly || !selection || selection.isEmpty()) return closeBox()
    const end = editor.getScrolledVisiblePosition(selection.getEndPosition())
    if (!end) return
    target.current = selection
    const maxLeft = Math.max(8, editor.getLayoutInfo().width - AI_BOX_WIDTH - 8)
    setBox({ top: end.top + end.height + 6, left: Math.min(Math.max(8, end.left), maxLeft) })
  }

  const submitEdit = async () => {
    const editor = editorRef.current
    const model = editor?.getModel()
    const range = target.current
    const ask = latest.current.onAiEdit
    if (!editor || !model || !range || !ask || busy || !instruction.trim()) return
    const selected = model.getValueInRange(range)
    setBusy(true)
    busyRef.current = true
    setMessage(null)
    try {
      const edit = await ask(selected, instruction.trim())
      if (model.getValueInRange(range) !== selected) {
        setMessage('The code changed while the edit was being written. Select it again.')
      } else {
        if (edit.replacement !== selected) {
          editor.pushUndoStop()
          editor.executeEdits('ai-edit', [{ range, text: edit.replacement, forceMoveMarkers: true }])
          editor.pushUndoStop()
        }
        if (edit.note) {
          setMessage(edit.note)
        } else {
          closeBox()
          editor.focus()
        }
      }
    } catch (e) {
      setMessage((e as Error).message)
    } finally {
      setBusy(false)
      busyRef.current = false
    }
  }

  const handleMount: OnMount = (editor, monaco) => {
    editorRef.current = editor
    monacoRef.current = monaco
    decorations.current = editor.createDecorationsCollection()
    editor.onMouseDown(e => {
      const line = e.target.position?.lineNumber
      if (line && latest.current.selectableLines) latest.current.onSelectLine?.(line)
    })
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => latest.current.onRun?.())
    editor.onDidChangeCursorSelection(placeBox)
    editor.onDidScrollChange(placeBox)
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

  useEffect(paint, [selectedLine, answerLine])

  const lines = value.split('\n').length
  const height = Math.min(Math.max(lines * 21 + 24, 160), 640)

  return (
    <div className="editor-host">
    <div className={`editor-wrap${selectableLines ? ' selectable' : ''}${readOnly ? ' readonly' : ''}`}>
      <Editor
        height={height}
        language={language}
        value={value}
        theme={EDITOR_THEME}
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
          bracketPairColorization: { enabled: false },
          contextmenu: !readOnly,
          padding: { top: 12, bottom: 12 },
          tabSize: 4,
          automaticLayout: true,
        }}
      />
    </div>
    {box && (
      <form className="ai-edit" style={{ top: box.top, left: box.left, width: AI_BOX_WIDTH }}
            onSubmit={e => { e.preventDefault(); submitEdit() }}>
        <div className="ai-edit-row">
          <input value={instruction} onChange={e => setInstruction(e.target.value)} maxLength={1000} disabled={busy}
                 placeholder="Describe the change to make to the selection…" aria-label="Change to make to the selected code"
                 onKeyDown={e => { if (e.key === 'Escape') { closeBox(); editorRef.current?.focus() } }} />
          <button className="primary" type="submit" disabled={busy || !instruction.trim()}>{busy ? 'Writing…' : 'Edit'}</button>
        </div>
        {message && <p className="small">{message}</p>}
      </form>
    )}
    </div>
  )
}

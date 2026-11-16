import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor/editor/editor.api'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import 'monaco-editor/languages/definitions/java/register'
import 'monaco-editor/languages/definitions/python/register'

self.MonacoEnvironment = { getWorker: () => new EditorWorker() }
loader.config({ monaco: monaco as unknown as typeof import('monaco-editor') })

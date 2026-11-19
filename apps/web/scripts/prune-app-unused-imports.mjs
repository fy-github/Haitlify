// Removes import specifiers from apps/web/src/App.vue that nothing references any
// more. Whole import declaration is dropped when every binding is unused.
// Usage: node scripts/prune-app-unused-imports.mjs [--apply]
import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

const apply = process.argv.includes('--apply')
const file = path.resolve('src/App.vue')
const raw = fs.readFileSync(file, 'utf8')
const scriptMatch = raw.match(/<script\b[^>]*>([\s\S]*?)<\/script>/)
if (!scriptMatch) throw new Error('no <script> block in App.vue')
const scriptStart = raw.indexOf(scriptMatch[0]) + scriptMatch[0].indexOf(scriptMatch[1])
const script = scriptMatch[1]
const template = raw.slice(raw.indexOf('<template>'))

const sf = ts.createSourceFile('App.vue.ts', script, ts.ScriptTarget.Latest, true)
const escape = (s) => s.replace(/[$]/g, '\\$&')
const uses = (name, text) => new RegExp(`(^|[^\\w$])${escape(name)}(?![\\w$])`).test(text)

const removals = [] // {start,end}
const droppedBindings = []
for (const stmt of sf.statements) {
  if (!ts.isImportDeclaration(stmt)) continue
  const clause = stmt.importClause
  if (!clause || stmt.moduleSpecifier.text.endsWith('.css')) continue
  const restOfScript = script.slice(0, stmt.getStart(sf)) + script.slice(stmt.getEnd())
  const bindings = []
  if (clause.name) bindings.push({ node: clause.name, name: clause.name.text, kind: 'default' })
  if (clause.namedBindings) {
    if (ts.isNamespaceImport(clause.namedBindings)) bindings.push({ node: clause.namedBindings.name, name: clause.namedBindings.name.text, kind: 'namespace' })
    else for (const el of clause.namedBindings.elements) bindings.push({ node: el, name: el.name.text, kind: 'named', isTypeOnly: el.isTypeOnly || clause.isTypeOnly })
  }
  const unused = bindings.filter((b) => !uses(b.name, restOfScript) && !uses(b.name, template))
  if (!unused.length) continue
  droppedBindings.push({ module: stmt.moduleSpecifier.text, names: unused.map((b) => b.name) })
  if (unused.length === bindings.length) {
    removals.push({ start: stmt.getFullStart(), end: stmt.getEnd() })
    continue
  }
  for (const b of unused) {
    // Remove the specifier plus a following or preceding comma.
    const specStart = b.node.getFullStart()
    const specEnd = b.node.getEnd()
    let start = specStart
    let end = specEnd
    const after = script.slice(end)
    const before = script.slice(0, start)
    if (/^\s*,/.test(after)) end += after.match(/^\s*,/)[0].length
    else if (/,\s*$/.test(before)) start = before.replace(/,\s*$/, '').length
    removals.push({ start, end })
  }
}

const merged = []
for (const span of removals.sort((a, b) => a.start - b.start)) {
  const last = merged[merged.length - 1]
  if (last && span.start <= last.end) last.end = Math.max(last.end, span.end)
  else merged.push({ ...span })
}
let nextScript = script
for (const span of [...merged].reverse()) nextScript = nextScript.slice(0, span.start) + nextScript.slice(span.end)
nextScript = nextScript.replace(/\n{4,}/g, '\n\n\n')

console.log(JSON.stringify({ droppedBindings: droppedBindings.length, removedStatements: merged.length }, null, 2))
for (const d of droppedBindings) console.log(`${d.module}: ${d.names.join(', ')}`)

if (apply) {
  fs.writeFileSync(file, raw.slice(0, scriptStart) + nextScript + raw.slice(scriptStart + script.length))
  console.log('APPLIED', file)
}

// Statement-level reachability for apps/web/src/App.vue (<script setup> block).
// Each top-level statement is a node. A node is a root when it declares nothing
// (imports, side-effect calls) or declares a name the template uses. Remaining
// nodes are dead when no reachable statement references a name they declare.
// Usage: node scripts/app-statement-closure.mjs [--json] [--apply]
import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

const apply = process.argv.includes('--apply')
const asJson = process.argv.includes('--json')
const file = path.resolve('src/App.vue')
const raw = fs.readFileSync(file, 'utf8')
const scriptMatch = raw.match(/<script\b[^>]*>([\s\S]*?)<\/script>/)
if (!scriptMatch) throw new Error('no <script> block in App.vue')
const scriptStart = raw.indexOf(scriptMatch[0]) + scriptMatch[0].indexOf(scriptMatch[1])
const script = scriptMatch[1]
const template = raw.slice(raw.indexOf('<template>'))

const sf = ts.createSourceFile('App.vue.ts', script, ts.ScriptTarget.Latest, true)
const escape = (s) => s.replace(/[$]/g, '\\$&')
// Conservative: a bare `name` after a dot (`obj.name`, `...name`) still counts as a
// reference, so property names can only keep extra code alive, never delete live code.
const re = (name) => new RegExp(`(^|[^\\w$])${escape(name)}(?![\\w$])`, 'g')

const boundNames = (name) => {
  if (ts.isIdentifier(name)) return [name.text]
  const out = []
  const walk = (node) => {
    if (ts.isIdentifier(node)) out.push(node.text)
    else ts.forEachChild(node, walk)
  }
  walk(name)
  return out
}

const nodes = []
sf.statements.forEach((stmt, index) => {
  const declares = []
  if (ts.isVariableStatement(stmt)) {
    for (const d of stmt.declarationList.declarations) declares.push(...boundNames(d.name))
  } else if (ts.isFunctionDeclaration(stmt) && stmt.name) {
    declares.push(stmt.name.text)
  } else if (ts.isClassDeclaration(stmt) && stmt.name) {
    declares.push(stmt.name.text)
  }
  nodes.push({
    index,
    start: stmt.getFullStart(),
    end: stmt.getEnd(),
    pos: stmt.getStart(sf),
    kind: ts.SyntaxKind[stmt.kind],
    declares: [...new Set(declares)],
    text: script.slice(stmt.getStart(sf), stmt.getEnd())
  })
})

const declaredBy = new Map()
for (const node of nodes) for (const name of node.declares) declaredBy.set(name, node)

const edges = new Map()
for (const node of nodes) {
  const out = new Set()
  const isModuleStmt = node.kind === 'ImportDeclaration' || node.kind === 'ExportDeclaration'
  if (!isModuleStmt) {
    for (const name of declaredBy.keys()) {
      if (node.declares.includes(name)) continue
      if (re(name).test(node.text)) out.add(declaredBy.get(name).index)
    }
  }
  edges.set(node.index, out)
}

const roots = new Set()
for (const node of nodes) if (node.declares.length === 0) roots.add(node.index)
for (const name of declaredBy.keys()) {
  if (re(name).test(template)) roots.add(declaredBy.get(name).index)
}

const reachable = new Set()
const queue = [...roots]
while (queue.length) {
  const index = queue.pop()
  if (reachable.has(index)) continue
  reachable.add(index)
  for (const next of edges.get(index) ?? []) if (!reachable.has(next)) queue.push(next)
}

const deadNodes = nodes.filter((node) => !reachable.has(node.index))
const deadNames = deadNodes.flatMap((node) => node.declares)

const spans = deadNodes.map((n) => ({ start: n.start, end: n.end })).sort((a, b) => a.start - b.start)
const merged = []
for (const span of spans) {
  const last = merged[merged.length - 1]
  if (last && span.start <= last.end) last.end = Math.max(last.end, span.end)
  else merged.push({ ...span })
}

let nextScript = script
for (const span of [...merged].reverse()) nextScript = nextScript.slice(0, span.start) + nextScript.slice(span.end)
nextScript = nextScript.replace(/\n{4,}/g, '\n\n\n')

const report = {
  statements: nodes.length,
  roots: roots.size,
  reachable: reachable.size,
  deadStatements: deadNodes.length,
  deadNames,
  removedBytes: script.length - nextScript.length
}

if (asJson) console.log(JSON.stringify(report, null, 2))
else {
  console.log({ statements: report.statements, roots: report.roots, reachable: report.reachable, deadStatements: report.deadStatements, removedBytes: report.removedBytes })
  for (const node of deadNodes.sort((a, b) => b.text.length - a.text.length)) {
    console.log(`DEAD ${String(node.text.length).padStart(6)} ${node.declares.join(',') || `#stmt${node.index}`}`)
  }
}

if (apply) {
  fs.writeFileSync(file, raw.slice(0, scriptStart) + nextScript + raw.slice(scriptStart + script.length))
  console.log('APPLIED', file)
}

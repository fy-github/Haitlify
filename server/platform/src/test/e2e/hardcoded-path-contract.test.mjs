import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync, existsSync, mkdirSync, writeFileSync, chmodSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..');
const runners = ['scripts/e2e/real-draft-e2e.sh', 'scripts/e2e/hermes-model-e2e.sh', ...['run-dual-user.sh', 'run-negative-control.sh', 'run-same-project.sh'].map(n => `server/platform/src/test/e2e/${n}`)];
const names = ['DraftLease', 'DualUserUnitPool', 'SameProjectDualActorUnitPool'];
const runnerSpecs = [
  { path: 'server/platform/src/test/e2e/run-dual-user.sh', args: [], vars: ['HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE', 'HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR', 'HAITLIFY_REAL_DUAL_USER_E2E_PYTHON', 'HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR'] },
  { path: 'server/platform/src/test/e2e/run-negative-control.sh', args: [], vars: ['HAITLIFY_REAL_DUAL_USER_E2E_BASE_PROFILE', 'HAITLIFY_REAL_DUAL_USER_E2E_EVIDENCE_DIR', 'HAITLIFY_REAL_DUAL_USER_E2E_PYTHON', 'HAITLIFY_REAL_DUAL_USER_E2E_AGENT_DIR'] },
  { path: 'server/platform/src/test/e2e/run-same-project.sh', args: ['h2'], vars: ['HAITLIFY_REAL_SAME_PROJECT_E2E_BASE_PROFILE', 'HAITLIFY_REAL_SAME_PROJECT_E2E_EVIDENCE_DIR', 'HAITLIFY_REAL_SAME_PROJECT_E2E_PYTHON', 'HAITLIFY_REAL_SAME_PROJECT_E2E_AGENT_DIR'] },
  { path: 'scripts/e2e/real-draft-e2e.sh', args: [], vars: ['HAITLIFY_REAL_DRAFT_E2E_PROFILE', 'HAITLIFY_REAL_DRAFT_E2E_SCRATCH', 'HAITLIFY_REAL_DRAFT_E2E_EVIDENCE_DIR', 'HAITLIFY_REAL_DRAFT_E2E_AGENT_DIR', 'HAITLIFY_REAL_DRAFT_E2E_PYTHON'] },
  { path: 'scripts/e2e/hermes-model-e2e.sh', args: [], vars: ['HAITLIFY_HERMES_E2E_SCRATCH'] }
];
function fixture(scratch, prefix) {
  const profile = resolve(scratch, `${prefix}-profile`);
  const agent = resolve(scratch, `${prefix}-agent`);
  const python = resolve(scratch, `${prefix}-python`);
  mkdirSync(profile, { recursive: true });
  mkdirSync(agent, { recursive: true });
  writeFileSync(resolve(profile, 'config.yaml'), 'fixture: true\n');
  writeFileSync(resolve(profile, '.env'), 'fixture=true\n');
  writeFileSync(python, '#!/bin/sh\nexit 0\n');
  chmodSync(python, 0o755);
  return { profile, agent, python };
}
function envFor(spec, values, scratch, path = '/usr/bin:/bin') {
  const env = { PATH: path, HOME: resolve(scratch, 'home'), TMPDIR: scratch };
  for (const [key, value] of Object.entries(values)) env[key] = value;
  return env;
}
function run(spec, values, scratch, path) {
  return spawnSync('/bin/bash', [resolve(root, spec.path), ...spec.args], {
    env: envFor(spec, values, scratch, path), cwd: root, encoding: 'utf8', timeout: 3000
  });
}
test('real gates have no operator path or host-global log fallback', () => {
  for (const path of runners) {
    const source = readFileSync(resolve(root, path), 'utf8');
    assert.doesNotMatch(source, /\/Users\/|\$HOME\/|~\/|\/opt\/homebrew\/Cellar/);
    assert.match(source, /required|需要显式|isolated-inputs/);
  }
  for (const name of names) {
    const source = readFileSync(resolve(root, `server/platform/src/test/java/com/haitlify/aitest/platform/assistant/conversation/RealModel${name}EndToEndTest.java`), 'utf8');
    assert.doesNotMatch(source, /\/Users\/|~\/|getProperty\("user.home"\)|host-global:/);
    assert.match(source, /evidenceDir = Path.of\(env\("HAITLIFY_REAL_\w+_EVIDENCE_DIR"\)\)/);
  }
});
test('missing explicit inputs fail before external commands or writes', () => {
  // Only execute scripts after the guard contract is installed: baseline failure is safe.
  for (const path of runners) assert.match(readFileSync(resolve(root, path), 'utf8'), /isolated-inputs/);
  const scratch = mkdtempSync(resolve(tmpdir(), 'e2e-path-contract-'));
  try {
    for (const path of runners) {
      const result = spawnSync('/bin/bash', [resolve(root, path)], { env: { PATH: '/usr/bin:/bin', HOME: resolve(scratch, 'home'), TMPDIR: scratch }, cwd: root, encoding: 'utf8', timeout: 3000 });
      assert.equal(result.status, 2, `${path}: ${result.stderr}`);
      assert.match(result.stderr, /required|需要显式|root|canonicalize/);
      assert.equal(existsSync(resolve(scratch, 'home')), false);
      const source = readFileSync(resolve(root, path), 'utf8');
      const required = [...source.matchAll(/(?:for name in |require_path )(HAITLIFY_[A-Z0-9_ ]+)/g)]
        .flatMap(match => match[1].trim().split(/\s+/));
      for (const invalid of required) {
        for (const value of [undefined, 'relative/path', '/']) {
          const env = { PATH: '/usr/bin:/bin', HOME: resolve(scratch, 'home'), TMPDIR: scratch };
          for (const name of required) env[name] = resolve(scratch, name);
          if (value === undefined) delete env[invalid]; else env[invalid] = value;
          const rejected = spawnSync('/bin/bash', [resolve(root, path)], { env, cwd: root, encoding: 'utf8', timeout: 3000 });
          assert.equal(rejected.status, 2, `${path} ${invalid}=${value}: ${rejected.stderr}`);
          assert.match(rejected.stderr, /required|需要显式|root|canonicalize/);
          for (const name of required) assert.equal(existsSync(resolve(scratch, name)), false, `${path} wrote ${name}`);
        }
      }
    }
  } finally { rmSync(scratch, { recursive: true, force: true }); }
});
function scriptCopy(spec, scratch) {
  const repo = resolve(scratch, 'fake-repo');
  const target = resolve(repo, spec.path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, readFileSync(resolve(root, spec.path)));
  chmodSync(target, 0o755);
  mkdirSync(resolve(repo, 'server/platform'), { recursive: true });
  writeFileSync(resolve(repo, 'server/platform/mvnw'), `#!/bin/sh
mkdir -p target/surefire-reports
printf '%s\\n' '<testsuite tests="0" failures="0" errors="0" skipped="0"/>' > target/surefire-reports/TEST-fake.xml
printf '%s\\n' 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' > target/surefire-reports/com.haitlify.aitest.platform.assistant.conversation.RealModelDraftLeaseEndToEndTest.txt
exit 0
`);
  chmodSync(resolve(repo, 'server/platform/mvnw'), 0o755);
  return { repo, target };
}
test('destructive runners reject canonical root aliases, traversal, symlink aliases and overlap before writes', () => {
  const scratch = mkdtempSync(resolve(tmpdir(), 'e2e-path-negative-'));
  try {
    for (const spec of runnerSpecs.filter(({ vars }) => vars.some(name => name.endsWith('_EVIDENCE_DIR')))) {
      const f = fixture(scratch, spec.path.replace(/[^a-z0-9]+/gi, '-'));
      const profileName = spec.vars.find(name => name.endsWith('_BASE_PROFILE') || name.endsWith('_PROFILE'));
      const evidenceName = spec.vars.find(name => name.endsWith('_EVIDENCE_DIR'));
      const base = Object.fromEntries(spec.vars.map(name => [name, f.profile]));
      base[spec.vars.find(name => name.endsWith('_PYTHON'))] = f.python;
      base[spec.vars.find(name => name.endsWith('_AGENT_DIR'))] = f.agent;
      const cases = [
        ['root', '/'], ['double-root', '//'], ['dot-root', '/.'], ['dot-double-root', '/.//'],
        ['parent-traversal', `${scratch}/fixture/../outside`],
        ['output-inside-profile', `${f.profile}/output`],
        ['source-inside-output', `${scratch}/output`]
      ];
      for (const [label, value] of cases) {
        const values = { ...base, [evidenceName]: value };
        if (label === 'source-inside-output') {
          values[profileName] = `${scratch}/output/profile`;
          mkdirSync(values[profileName], { recursive: true });
          writeFileSync(resolve(values[profileName], 'config.yaml'), 'fixture: true\n');
          writeFileSync(resolve(values[profileName], '.env'), 'fixture=true\n');
        }
        const rejected = run(spec, values, scratch);
        assert.equal(rejected.status, 2, `${spec.path} ${label}: ${rejected.stderr}`);
        assert.match(rejected.stderr, /required|overlap|root|canonicalize|owned/);
        if (label === 'source-inside-output') {
          assert.equal(existsSync(resolve(value, 'profile', 'config.yaml')), true, `${spec.path} touched protected profile`);
        } else if (!['root', 'double-root', 'dot-root', 'dot-double-root'].includes(label)) {
          assert.equal(existsSync(value), false, `${spec.path} ${label} created ${value}`);
        }
      }
      const alias = resolve(scratch, `${spec.path.replace(/[^a-z0-9]+/gi, '-')}-profile-alias`);
      symlinkSync(f.profile, alias);
      const aliasValues = { ...base, [profileName]: alias, [evidenceName]: `${f.profile}/alias-output` };
      const aliasRejected = run(spec, aliasValues, scratch);
      assert.equal(aliasRejected.status, 2, `${spec.path} symlink alias: ${aliasRejected.stderr}`);
      assert.match(aliasRejected.stderr, /overlap|owned|root|canonicalize/);
      assert.equal(existsSync(resolve(f.profile, 'alias-output')), false);
    }
    const hermes = runnerSpecs.find(({ path }) => path.includes('hermes-model'));
    const hermesScratch = resolve(scratch, 'hermes-scratch');
    mkdirSync(hermesScratch);
    const hermesAlias = resolve(scratch, 'hermes-scratch-alias');
    symlinkSync(hermesScratch, hermesAlias);
    const badHermes = run(hermes, { HAITLIFY_HERMES_E2E_SCRATCH: hermesAlias }, scratch);
    assert.equal(badHermes.status, 2, badHermes.stderr);
    assert.match(badHermes.stderr, /owned|root|canonicalize/);
  } finally { rmSync(scratch, { recursive: true, force: true }); }
});
test('valid temporary shapes stop at fake external commands without model, database or Maven work', () => {
  const scratch = mkdtempSync(resolve(tmpdir(), 'e2e-path-valid-'));
  const fakeBin = resolve(scratch, 'fake-bin');
  mkdirSync(fakeBin);
  writeFileSync(resolve(fakeBin, 'lsof'), '#!/bin/sh\nexit 1\n');
  writeFileSync(resolve(fakeBin, 'docker'), '#!/bin/sh\nexit 1\n');
  chmodSync(resolve(fakeBin, 'lsof'), 0o755);
  chmodSync(resolve(fakeBin, 'docker'), 0o755);
  try {
    for (const spec of runnerSpecs) {
      const f = fixture(scratch, `valid-${spec.path.replace(/[^a-z0-9]+/gi, '-')}`);
      const values = Object.fromEntries(spec.vars.map(name => [name, f.profile]));
      const profileName = spec.vars.find(name => name.endsWith('_BASE_PROFILE') || name.endsWith('_PROFILE'));
      const pythonName = spec.vars.find(name => name.endsWith('_PYTHON'));
      const agentName = spec.vars.find(name => name.endsWith('_AGENT_DIR'));
      const evidenceName = spec.vars.find(name => name.endsWith('_EVIDENCE_DIR'));
      const scratchName = spec.vars.find(name => name.endsWith('_SCRATCH'));
      if (pythonName) values[pythonName] = f.python;
      if (agentName) values[agentName] = f.agent;
      if (evidenceName) values[evidenceName] = resolve(scratch, `valid-${spec.path.replace(/[^a-z0-9]+/gi, '-')}-evidence`);
      if (scratchName) {
        values[scratchName] = resolve(scratch, `valid-${spec.path.replace(/[^a-z0-9]+/gi, '-')}-scratch`);
        mkdirSync(values[scratchName]);
        const marker = spec.path.includes('hermes-model') ? '.haitlify-hermes-model-e2e-owned' : '.haitlify-real-draft-e2e-owned';
        writeFileSync(resolve(values[scratchName], marker), spec.path.includes('hermes-model') ? 'haitlify-hermes-model-e2e-v1\n' : 'haitlify-real-draft-e2e-v1\n');
      }
      const copy = scriptCopy(spec, scratch);
      const result = spawnSync('/bin/bash', [copy.target, ...spec.args], {
        env: envFor(spec, values, scratch, `${fakeBin}:/usr/bin:/bin`), cwd: copy.repo, encoding: 'utf8', timeout: 5000
      });
      assert.ok([0, 1, 4].includes(result.status), `${spec.path}: status=${result.status} stdout=${result.stdout} stderr=${result.stderr}`);
      if (evidenceName) assert.equal(existsSync(values[evidenceName]), true);
    }
  } finally { rmSync(scratch, { recursive: true, force: true }); }
});

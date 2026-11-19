#!/usr/bin/env python3
"""No-model SDK gate: independent source copy, H2, one bounded JVM, offline Maven.
Never starts Docker, reads operator credentials, or modifies the deployment DB.
Requires a preinstalled Python 3.14 environment matching the pinned MCP extras.
"""
import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PIN = 'd923a5eb487a17c0a68801bba9ef0f56c81040a8'
TESTS = 'McpPythonSdkTest,McpHttpTest,McpSocketTest,GenerationDraftToolsTest'
EXPECTED_SUITES = {
    'com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftToolsTest': 55,
    'com.haitlify.aitest.platform.assistant.mcp.McpHttpTest': 12,
    'com.haitlify.aitest.platform.assistant.mcp.McpPythonSdkTest': 2,
    'com.haitlify.aitest.platform.assistant.mcp.McpSocketTest': 13,
    'com.haitlify.aitest.platform.assistant.mcp.McpPythonProcessCleanupTest': 4,
}
TESTS = ','.join(name.rsplit('.', 1)[-1] for name in EXPECTED_SUITES)
EXPECTED_TOTAL_TESTS = sum(EXPECTED_SUITES.values())


def _ps_snapshot():
    """Observe processes from a separate session so the observer cannot join the target group."""
    data = subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat='],
                                   text=True, start_new_session=True)
    rows = []
    for line in data.splitlines():
        fields = line.split()
        if len(fields) == 4:
            rows.append({'pid': int(fields[0]), 'ppid': int(fields[1]),
                         'pgid': int(fields[2]), 'stat': fields[3]})
    return rows


def _ps_entry(pid):
    for row in _ps_snapshot():
        if row['pid'] == pid:
            return row
    return None


def _parse_lsof_listening(stdout):
    """Parse lsof -Fpcn records; malformed successful output is UNKNOWN."""
    owners = {}
    current = None
    for line in stdout.splitlines():
        if not line or len(line) < 2:
            return None
        field, value = line[0], line[1:]
        if field == 'p':
            if not re.fullmatch(r'[1-9][0-9]*', value):
                return None
            pid = int(value)
            current = owners.setdefault(pid, {'pid': pid, 'command': '', 'endpoints': []})
        elif field == 'c' and current is not None:
            current['command'] = value
        elif field == 'n' and current is not None:
            current['endpoints'].append(value)
        elif field == 'f' and current is not None:
            # File descriptor metadata is not ownership identity, but is valid lsof output.
            if not re.fullmatch(r'[0-9]+', value):
                return None
            continue
        else:
            return None
    if any(not owner['command'] or not owner['endpoints'] for owner in owners.values()):
        return None
    return list(owners.values())


def _lookup_listening_port(port):
    """Read LISTEN ownership from lsof; never bind or connect to the port."""
    lsof = shutil.which('lsof')
    if not lsof:
        return {'status': 'UNKNOWN', 'reason': 'lsof unavailable', 'owners': []}
    command = [lsof, '-nP', '-a', f'-iTCP:{int(port)}', '-sTCP:LISTEN', '-Fpcn']
    try:
        completed = subprocess.run(command, text=True, capture_output=True, check=False,
                                   start_new_session=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {'status': 'UNKNOWN', 'reason': f'lsof execution failed: {exc}', 'owners': []}
    owners = _parse_lsof_listening(completed.stdout)
    if owners and any(not re.fullmatch(r'(?:\*|[0-9.]+|\[[0-9A-Fa-f:.%]+\]):' + str(int(port)), endpoint)
                      for owner in owners for endpoint in owner['endpoints']):
        owners = None
    if owners is None:
        return {'status': 'UNKNOWN', 'reason': 'lsof output schema invalid',
                'exit': completed.returncode, 'stderr': completed.stderr[-500:], 'owners': []}
    if completed.stderr.strip() or (completed.returncode == 0 and not owners):
        return {'status': 'UNKNOWN', 'reason': 'lsof incomplete output',
                'exit': completed.returncode, 'stderr': completed.stderr[-500:], 'owners': owners}
    if completed.returncode == 0:
        for owner in owners:
            try:
                process = _ps_entry(owner['pid'])
            except (OSError, subprocess.CalledProcessError, ValueError) as exc:
                process = {'status': 'UNKNOWN', 'reason': f'process lookup failed: {exc}'}
            owner['process'] = process or {'status': 'UNKNOWN', 'reason': 'process exited after lsof'}
        return {'status': 'LISTENING', 'owners': owners,
                'exit': completed.returncode}
    if completed.returncode == 1 and not owners and not completed.stderr.strip():
        return {'status': 'ABSENT', 'owners': [], 'exit': completed.returncode}
    return {'status': 'UNKNOWN', 'reason': 'lsof failed', 'exit': completed.returncode,
            'stderr': completed.stderr[-500:], 'owners': owners}


def _observe_listening_port(port, *, samples=3, interval=0.05):
    """Take a bounded ownership observation; preserve every sample for evidence."""
    observations = []
    for index in range(samples):
        observed = dict(_lookup_listening_port(port))
        observed['sample'] = index + 1
        observed['monotonic'] = time.monotonic()
        observations.append(observed)
        if index + 1 < samples:
            time.sleep(interval)
    statuses = [item['status'] for item in observations]
    if 'UNKNOWN' in statuses:
        status = 'UNKNOWN'
    elif len(statuses) >= 2 and statuses[-2:] == ['ABSENT', 'ABSENT']:
        # A bounded quiet window is sufficient for this observation. Earlier listener
        # samples remain evidence; this does not promise the port cannot be reused.
        status = 'ABSENT'
    elif statuses[-1] == 'LISTENING':
        status = 'LISTENING'
    else:
        status = 'UNCERTAIN'
    return {'port': int(port), 'status': status, 'stable_absent': status == 'ABSENT',
            'saw_listener': any(item == 'LISTENING' for item in statuses),
            'samples': observations}


def _tcp_listening(port):
    """Compatibility predicate backed by ownership lookup, never by bind/connect."""
    return _lookup_listening_port(port)['status'] == 'LISTENING'


def _owned_group_anchor(anchor_pid, snapshot=None):
    """True only while anchor_pid is our live child that still leads its own group."""
    if not isinstance(anchor_pid, int) or anchor_pid <= 1 or anchor_pid == os.getpid():
        return False
    row = next((r for r in (snapshot or _ps_snapshot()) if r['pid'] == anchor_pid), None)
    if row is None or row['ppid'] != os.getpid() or row['pgid'] != anchor_pid:
        return False
    try:
        return os.getpgid(anchor_pid) == anchor_pid and os.getsid(anchor_pid) == anchor_pid
    except (ProcessLookupError, PermissionError):
        return False


def _signal_owned_anchor(anchor_pid, sig, *, snapshot=None, kill=None):
    """Signal the owned process group only while its live anchor proves ownership."""
    if not _owned_group_anchor(anchor_pid, snapshot):
        return False
    (kill or os.killpg)(anchor_pid, sig)
    return True


def _group_members(group):
    """Live (non-zombie) members of the group; a zombie is already gone and cannot hold ports."""
    return [row for row in _ps_snapshot()
            if row['pgid'] == group and row['pid'] != group and not row['stat'].startswith('Z')]


def _keeper(config, root, read_fd, write_fd):
    """Group leader that survives TERM cleanup and reports the reaped command's status."""
    os.setsid()
    group = os.getpid()
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    os.close(write_fd)
    status_path = Path(root) / 'leader.json'

    def publish(exit_code, timed_out):
        temporary = status_path.with_suffix('.tmp')
        temporary.write_text(json.dumps({'group': group, 'leader': leader,
                                         'exit': exit_code, 'timed_out': timed_out}),
                             encoding='utf-8')
        os.replace(temporary, status_path)

    leader = os.fork()
    if leader == 0:
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        os.close(read_fd)
        os.chdir(config['cwd'])
        descriptor = os.open(str(Path(root) / config['log']), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.dup2(descriptor, 1)
        os.dup2(descriptor, 2)
        os.close(descriptor)
        os.execve(config['command'][0], config['command'], config['env'])
    publish(None, True)
    deadline = time.monotonic() + config['timeout']
    status = None
    while time.monotonic() < deadline:
        ended, value = os.waitpid(leader, os.WNOHANG)
        if ended:
            status = value
            break
        time.sleep(0.05)
    if status is not None:
        publish(os.waitstatus_to_exitcode(status), False)
    # Remain the live group anchor until the supervisor closes the pipe, reaping a late exit.
    while True:
        readable, _, _ = select.select([read_fd], [], [], 0.05)
        if readable and not os.read(read_fd, 4096):
            # The supervisor has finished cleanup: account for a leader terminated by it.
            stop = time.monotonic() + 2
            while status is None and time.monotonic() < stop:
                ended, value = os.waitpid(leader, os.WNOHANG)
                if ended:
                    status = value
                    publish(os.waitstatus_to_exitcode(status), True)
                else:
                    time.sleep(0.02)
            break
        if status is None:
            ended, value = os.waitpid(leader, os.WNOHANG)
            if ended:
                status = value
                publish(os.waitstatus_to_exitcode(status), True)
    os._exit(0)


def _anchor(config_path, state_path):
    """Supervisor child: owns a live group leader elsewhere, signals only that anchored group."""
    config = json.loads(Path(config_path).read_text(encoding='utf-8'))
    root = Path(config_path).parent
    state = {'anchor': os.getpid(), 'group': None, 'leader': None, 'leader_exit': None,
             'timed_out': False, 'observed': [], 'signals': [], 'remaining': [],
             'clean': False}

    def save():
        temporary = Path(state_path).with_suffix('.tmp')
        temporary.write_text(json.dumps(state, indent=2), encoding='utf-8')
        os.replace(temporary, state_path)

    save()
    try:
        os.setsid()
    except OSError:
        state['remaining'] = _group_members(os.getpid())
        save()
        return 1
    if os.getpgid(0) != os.getpid():
        state['remaining'] = _group_members(os.getpid())
        save()
        return 1
    read_fd, write_fd = os.pipe()
    keeper = os.fork()
    if keeper == 0:
        try:
            _keeper(config, root, read_fd, write_fd)
        finally:
            os._exit(1)
    os.close(read_fd)
    state['leader'] = keeper
    state['group'] = keeper
    save()
    status_path = root / 'leader.json'
    deadline = time.monotonic() + config['timeout'] + 5
    leader_state = None
    while time.monotonic() < deadline:
        if status_path.exists():
            try:
                leader_state = json.loads(status_path.read_text(encoding='utf-8'))
            except json.JSONDecodeError:
                leader_state = None
            if leader_state and leader_state.get('exit') is not None:
                break
        time.sleep(0.05)
    if leader_state:
        state['leader_exit'] = leader_state.get('exit')
        state['timed_out'] = leader_state.get('timed_out', False)
    else:
        state['timed_out'] = True
    state['observed'] = [row for row in _group_members(keeper) if row['pid'] != keeper]
    save()

    remaining = state['observed']
    escalated = False
    if remaining and _signal_owned_anchor(keeper, signal.SIGTERM):
        state['signals'].append({'name': 'SIGTERM', 'target': 'owned_group'})
        save()
        grace = time.monotonic() + config['grace']
        while time.monotonic() < grace:
            remaining = [row for row in _group_members(keeper) if row['pid'] != keeper]
            if not remaining:
                break
            time.sleep(0.05)
        if remaining and _signal_owned_anchor(keeper, signal.SIGKILL):
            state['signals'].append({'name': 'SIGKILL', 'target': 'owned_group'})
            escalated = True
            save()
            grace = time.monotonic() + config['grace']
            while time.monotonic() < grace:
                if not _group_members(keeper):
                    break
                time.sleep(0.05)
    os.close(write_fd)
    # Reap the anchor; a TERM-ignoring anchor exits on pipe EOF, a SIGKILLed one exits here.
    stop = time.monotonic() + config['grace'] + 5
    while time.monotonic() < stop:
        ended, _ = os.waitpid(keeper, os.WNOHANG)
        if ended:
            break
        time.sleep(0.05)
    else:
        os.kill(keeper, signal.SIGKILL)
        os.waitpid(keeper, 0)
    if state['leader_exit'] is None and status_path.exists():
        try:
            late = json.loads(status_path.read_text(encoding='utf-8'))
        except json.JSONDecodeError:
            late = {}
        state['leader_exit'] = late.get('exit')
    # Readback only: no destructive signal is sent after the group anchor is reaped.
    state['remaining'] = _group_members(keeper)
    state['clean'] = not state['remaining'] and (state['leader_exit'] is not None or escalated)
    save()
    return 0 if state['clean'] else 1


def supervise_command(command, *, cwd, env, log_path, state_path, timeout, grace=5.0):
    """Run one bounded command under a live owned-group anchor and report exact cleanup."""
    root = Path(state_path).parent
    config_path = root / 'anchor-command.json'
    config_path.write_text(json.dumps({'command': command, 'cwd': str(cwd), 'env': env,
                                       'log': Path(log_path).name, 'timeout': timeout,
                                       'grace': grace}), encoding='utf-8')
    anchor_log = (root / 'anchor.log').open('w', encoding='utf-8')
    anchor = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--anchor',
                               str(config_path), str(state_path)],
                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                              stderr=anchor_log)
    bounded_wait = timeout + (grace * 3) + 30
    timed_out = False
    try:
        try:
            anchor.wait(timeout=bounded_wait)
        except subprocess.TimeoutExpired:
            timed_out = True
            anchor.terminate()
            try:
                anchor.wait(timeout=5)
            except subprocess.TimeoutExpired:
                anchor.kill()
                anchor.wait(timeout=5)
    finally:
        anchor_log.close()
    state = {}
    if Path(state_path).exists():
        state = json.loads(Path(state_path).read_text(encoding='utf-8'))
    leader_exit = state.get('leader_exit')
    signals = state.get('signals', [])
    remaining = state.get('remaining', [])
    status = supervision_status(state, anchor_exit=anchor.returncode, timed_out=timed_out)
    return {'status': status, 'anchor_exit': anchor.returncode, 'leader': state.get('leader'),
            'group': state.get('group'), 'leader_exit': leader_exit,
            'timed_out': state.get('timed_out', timed_out), 'aborted': timed_out,
            'signals': signals, 'observed': state.get('observed', []),
            'remaining': remaining}


def supervision_status(state, *, anchor_exit, timed_out=False):
    """Only a clean anchor exit with a verified-empty group yields CLEAN."""
    if timed_out or not state or anchor_exit != 0 or not state.get('clean'):
        return 'UNCERTAIN'
    return 'FAILED' if state.get('timed_out') else 'CLEAN'


def finalize_cleanup(root, source, temp, cleanup_status, listening_ports, preserve=False,
                     port_observations=None):
    """Delete copied evidence only when process and port cleanup are both verified."""
    ports = sorted({int(port) for port in listening_ports})
    if cleanup_status != 'CLEAN':
        return {'cleanup': 'RETAINED', 'reason': 'process cleanup not verified',
                'root': str(root)}
    if port_observations:
        not_verified = [item for item in port_observations
                        if item.get('status') != 'ABSENT' or not item.get('stable_absent')]
        if not_verified:
            return {'cleanup': 'RETAINED', 'reason': 'port absence not stably verified',
                    'ports': [item['port'] for item in not_verified],
                    'port_observations': not_verified, 'root': str(root)}
    if ports:
        return {'cleanup': 'RETAINED', 'reason': 'ports still listening',
                'ports': ports, 'root': str(root)}
    if preserve:
        return {'cleanup': 'RETAINED', 'reason': 'gate evidence incomplete',
                'root': str(root)}
    if source.exists():
        shutil.rmtree(source)
    if temp.exists():
        shutil.rmtree(temp)
    return {'cleanup': 'REMOVED'}


def maven_invocation(*, mvn, root, source, python, java_home, repo_local):
    """Build an offline Maven call with scratch HOME and explicit empty settings."""
    root = Path(root)
    home = root / 'home'
    home.mkdir(exist_ok=True)
    temp = root / 'tmp'
    temp.mkdir(exist_ok=True)
    settings = root / 'settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n',
                        encoding='utf-8')
    repo_local = Path(repo_local).resolve()
    command = [mvn, '-o', '-B', '-q', '-s', str(settings), '-gs', str(settings),
               '-Dmaven.repo.local=' + str(repo_local), 'test', '-Dtest=' + TESTS,
               '-DforkCount=1', '-DreuseForks=true', '-Djacoco.skip=true',
               '-Djunit.jupiter.execution.parallel.enabled=false',
               '-DargLine=-Xmx512m -Djava.io.tmpdir=' + str(temp) +
               ' -Duser.home=' + str(home),
               '-Dmcp.sdk.python=' + python, '-Dapp.jira.enabled=false',
               '-Dapp.web-automation.runner.enabled=false']
    env = {'PATH': os.defpath + ':/opt/homebrew/bin', 'HOME': str(home),
           'JAVA_HOME': java_home, 'MAVEN_OPTS': '-Xmx256m -Duser.home=' + str(home),
           'MAVEN_SKIP_RC': 'true', 'TMPDIR': str(temp)}
    return {'command': command, 'env': env, 'settings': str(settings),
            'home': str(home), 'temp': str(temp), 'source': str(source)}


def gate_failure(*, cleanup_status, cleanup_result, exit_code, receipt, suite_count):
    """Return the blocking reason; a green gate requires cleanup, exit and evidence."""
    if cleanup_status != 'CLEAN' or cleanup_result.get('cleanup') != 'REMOVED':
        return 'process/port cleanup not verified'
    if exit_code != 0:
        return 'maven exit ' + str(exit_code)
    suites = receipt.get('suites')
    if not isinstance(suites, list):
        return 'incomplete gate evidence'
    if any(not isinstance(suite, dict) for suite in suites):
        return 'incomplete gate evidence'
    by_name = {suite.get('name'): suite for suite in suites}
    expected_names = set(EXPECTED_SUITES)
    try:
        valid_suites = (set(by_name) == expected_names and len(by_name) == len(suites)
                        and all(int(by_name[name].get('tests', -1)) == expected
                                and int(by_name[name].get('failures', -1)) == 0
                                and int(by_name[name].get('errors', -1)) == 0
                                and int(by_name[name].get('skipped', -1)) == 0
                                for name, expected in EXPECTED_SUITES.items()))
        totals = (sum(int(s.get('tests', -1)) for s in suites),
                  sum(int(s.get('failures', -1)) for s in suites),
                  sum(int(s.get('errors', -1)) for s in suites),
                  sum(int(s.get('skipped', -1)) for s in suites))
    except (TypeError, ValueError):
        return 'incomplete gate evidence'
    if ('sdk' not in receipt or receipt.get('skipped') or receipt.get('failures')
            or receipt.get('errors') or suite_count != len(EXPECTED_SUITES)
            or not valid_suites or totals != (EXPECTED_TOTAL_TESTS, 0, 0, 0)
            or receipt.get('tests') != EXPECTED_TOTAL_TESTS):
        return 'incomplete gate evidence'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--python', required=True, help='Existing pinned Python 3.14 venv executable')
    parser.add_argument('--hermes-source', required=True, help='Source checkout containing the reviewed commit')
    parser.add_argument('--scratch', default=str(Path.home() / '.hermes/cache/scratch'))
    parser.add_argument('--java-home', required=True, help='JDK 21 home')
    parser.add_argument('--maven-repo-local', required=True,
                        help='Existing local Maven cache; used only by offline Maven')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    python = str(Path(args.python).absolute())
    manifest = subprocess.check_output(['git', '-C', args.hermes_source, 'show', PIN + ':pyproject.toml'], text=True)
    for dep in ('mcp==2.0.0', 'httpx2==2.12.0'):
        if '"' + dep + '"' not in manifest:
            raise SystemExit('BLOCKED: pinned SDK dependency changed')
    scratch = Path(args.scratch).resolve()
    if not scratch.is_dir():
        raise SystemExit('BLOCKED: explicit scratch directory must exist')
    repo_local = Path(args.maven_repo_local).resolve()
    if not repo_local.is_dir():
        raise SystemExit('BLOCKED: explicit local Maven cache must exist')
    root = Path(tempfile.mkdtemp(prefix='real-mcp-sdk-', dir=scratch))
    os.chmod(root, 0o700)
    source = root / 'server/platform'
    source.mkdir(parents=True)
    shutil.copytree(repo / 'server/platform/src', source / 'src')
    shutil.copy2(repo / 'server/platform/pom.xml', source / 'pom.xml')
    mvn = shutil.which('mvn')
    if not mvn:
        raise SystemExit('BLOCKED: Maven must be preinstalled')
    invocation = maven_invocation(mvn=mvn, root=root, source=source, python=python,
                                  java_home=args.java_home, repo_local=repo_local)
    temp = Path(invocation['temp'])
    print('EVIDENCE', root, flush=True)
    cleanup = supervise_command(invocation['command'], cwd=source, env=invocation['env'],
                                log_path=root / 'maven.log',
                                state_path=root / 'supervisor.json', timeout=240, grace=5)
    exit_code = cleanup['leader_exit']
    suites = []
    for report in sorted((source / 'target/surefire-reports').glob('TEST-*.xml')):
        node = ET.parse(report).getroot()
        suites.append({key: node.attrib[key] for key in ('name', 'tests', 'failures', 'errors', 'skipped')})
    receipt = {'exit': exit_code, 'hermes_pin': PIN, 'suites': suites,
               'tests': sum(int(s['tests']) for s in suites),
               'failures': sum(int(s['failures']) for s in suites),
               'errors': sum(int(s['errors']) for s in suites),
               'skipped': sum(int(s['skipped']) for s in suites),
               'supervisor': {key: cleanup[key] for key in
                              ('status', 'anchor_exit', 'leader', 'group', 'timed_out',
                               'aborted', 'signals', 'observed', 'remaining')}}
    ports = []
    for line in (root / 'maven.log').read_text(encoding='utf-8').splitlines():
        if line.startswith('REAL_MCP_SDK_RECEIPT '):
            receipt['sdk'] = json.loads(line.removeprefix('REAL_MCP_SDK_RECEIPT '))
        if 'Tomcat started on port ' in line:
            match = re.search(r'port (\d+)', line)
            if match:
                ports.append(int(match.group(1)))
    port_observations = [_observe_listening_port(port) for port in sorted(set(ports))]
    listening_now = sorted({item['port'] for item in port_observations
                            if item['status'] == 'LISTENING'})
    unknown_ports = sorted({item['port'] for item in port_observations
                            if item['status'] in ('UNKNOWN', 'UNCERTAIN')})
    receipt['ports'] = {'observed': sorted(set(ports)), 'listening': listening_now,
                        'unknown': unknown_ports, 'checks': port_observations}
    (root / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    complete = gate_failure(cleanup_status=cleanup['status'],
                            cleanup_result={'cleanup': 'REMOVED'}, exit_code=exit_code,
                            receipt=receipt, suite_count=len(suites)) is None
    receipt['cleanup'] = finalize_cleanup(root, source, temp, cleanup['status'],
                                          listening_now, preserve=not complete,
                                          port_observations=port_observations)
    (root / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, sort_keys=True))
    failure = gate_failure(cleanup_status=cleanup['status'],
                           cleanup_result=receipt['cleanup'],
                           exit_code=exit_code, receipt=receipt, suite_count=len(suites))
    if failure:
        raise SystemExit('BLOCKED: ' + failure + '; evidence retained at ' + str(root))
    return 0


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--anchor':
        raise SystemExit(_anchor(sys.argv[2], sys.argv[3]))
    raise SystemExit(main())

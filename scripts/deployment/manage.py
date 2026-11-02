#!/usr/bin/env python3
"""Linux process lifecycle and CSV sampling. Configuration is trusted Bash code."""
import argparse
import csv
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BOOT = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def proc(pid):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        if fields[0] == 'Z':
            return None
        return {'start': fields[19], 'ticks': int(fields[11]) + int(fields[12]),
                'rss': int(fields[21]) * os.sysconf('SC_PAGE_SIZE')}
    except (FileNotFoundError, ProcessLookupError):
        return None

def alive(row):
    info = proc(row['pid'])
    return row['boot'] == BOOT and info is not None and info['start'] == row['start']

def save(path, rows):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(rows, indent=2) + '\n')
    tmp.replace(path)

def config(path):
    raw = subprocess.check_output(['bash', '-c', 'set -ea; source "$1"; env -0', 'config', str(path)])
    return dict(item.decode().split('=', 1) for item in raw.split(b'\0') if b'=' in item)

def integer(c, key, low=1, high=65535):
    value = c.get(key, '')
    if not re.fullmatch(r'[0-9]+', value) or not low <= int(value) <= high:
        raise ValueError(f'{key}: expected integer {low}..{high}')
    return int(value)

def stop(rows, timeout):
    for row in reversed(rows):
        if alive(row):
            os.kill(row['pid'], signal.SIGTERM)
    deadline = time.monotonic() + timeout
    while any(alive(row) for row in rows) and time.monotonic() < deadline:
        time.sleep(.1)
    for row in reversed(rows):
        if alive(row):
            os.kill(row['pid'], signal.SIGKILL)
    # Wait for disappearance, including processes adopted by PID 1.
    deadline = time.monotonic() + 3
    while any(alive(row) for row in rows) and time.monotonic() < deadline:
        time.sleep(.1)
    if any(alive(row) for row in rows):
        raise RuntimeError('Some managed processes did not stop; registry retained')

def wait_ready(row, port, timeout, service=None, host='127.0.0.1'):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not alive(row):
            raise RuntimeError(f"{row['name']} exited; inspect {row['log']}")
        try:
            if service:
                with HTTP.open(f'http://{host}:{port}/status', timeout=1) as response:
                    body = json.load(response)
                if body.get('service') != service or body.get('status') != 'running':
                    raise ValueError('Unexpected status response')
            else:
                with socket.create_connection((host, port), timeout=1):
                    pass
            return
        except (OSError, ValueError):
            time.sleep(.2)
    raise RuntimeError(f"Timeout waiting for {row['name']}; inspect {row['log']}")

def start(c, args, registry, rows):
    if any(alive(r) for r in rows):
        raise RuntimeError('Managed processes are already running; stop them first')
    if args.mode == 'full':
        # Refuse a false full-system success against the supplied skeletons.
        sources = [ROOT/'broker/src/main/java/es/ulpgc/piedata/broker/BrokerApp.java',
                   ROOT/'feeder/src/main/java/es/ulpgc/piedata/feeder/FeederApp.java',
                   ROOT/'ingestion-service/src/main/java/es/ulpgc/piedata/ingestion/IngestionApp.java']
        if any('Skeleton' in p.read_text() or 'not implemented yet' in p.read_text() for p in sources):
            raise RuntimeError('Full mode blocked: broker/feeder/ingestion are skeletons. Use --mode search. '
                               'Revalidate messaging integration when implementations arrive.')
    data = Path(args.data_dir or c['DATA_DIR']).expanduser().resolve()
    runtime = registry.parent
    integer(c, 'HTTP_THREADS_MIN'); integer(c, 'HTTP_THREADS_MAX')
    if int(c['HTTP_THREADS_MAX']) < int(c['HTTP_THREADS_MIN']):
        raise ValueError('HTTP_THREADS_MAX must be >= HTTP_THREADS_MIN')
    timeout = integer(c, 'STARTUP_TIMEOUT_SECONDS')
    stop_timeout = integer(c, 'STOP_TIMEOUT_SECONDS')
    for key in ('JAVA_HEAP_MIN', 'JAVA_HEAP_MAX'):
        if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]?', c[key]):
            raise ValueError(f'Invalid {key}')
    java = shutil.which(c['JAVA_BIN'])
    nginx = shutil.which(c['NGINX_BIN'])
    if not java or not nginx:
        raise ValueError('Java and nginx must be installed')
    step = integer(c, 'PORT_STEP')
    plan = []
    if args.mode == 'full':
        plan.append(('broker', 'broker', integer(c, 'BROKER_PORT'), None))
        for name in ('ingestion', 'indexing'):
            for i in range(integer(c, name.upper()+'_REPLICAS', high=100)):
                plan.append((f'{name}-{i+1}', name+'-service',
                             integer(c, name.upper()+'_BASE_PORT') + i*step, name))
    for i in range(integer(c, 'SEARCH_REPLICAS', high=100)):
        plan.append((f'search-{i+1}', 'search-service', integer(c, 'SEARCH_BASE_PORT') + i*step, 'search'))
    if args.mode == 'full':
        plan.append(('feeder', 'feeder', integer(c, 'FEEDER_PORT'), 'feeder'))
    gateway = integer(c, 'GATEWAY_PORT')
    ports = [p[2] for p in plan] + [gateway]
    if len(set(ports)) != len(ports) or any(p > 65535 for p in ports):
        raise ValueError('Port collision or port outside 1..65535')
    for port in ports:
        with socket.socket() as sock:
            sock.bind(('0.0.0.0', port))
    for _, module, _, _ in plan:
        if not (ROOT/module/'target'/f'{module}.jar').is_file():
            raise ValueError(f'Missing {module} JAR: run mvn verify')
    subprocess.run(['bash', str(ROOT/'scripts/generate-nginx.sh'), str(args.config)], check=True)
    logdir = runtime/'logs'/time.strftime('%Y%m%d-%H%M%S')
    logdir.mkdir(parents=True, exist_ok=False)
    rows = []
    save(registry, rows)

    def launch(name, command):
        logfile = logdir/f'{name}.log'
        with logfile.open('wb') as log:
            child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=subprocess.STDOUT,
                                     start_new_session=True, close_fds=True)
        info = proc(child.pid)
        if info is None:
            raise RuntimeError(f'{name} exited immediately: {logfile}')
        row = dict(name=name, pid=child.pid, start=info['start'], boot=BOOT, log=str(logfile))
        rows.append(row)
        save(registry, rows)
        return row

    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    old = signal.signal(signal.SIGTERM, interrupted)
    try:
        for name, module, port, service in plan:
            opts = {'data.dir': str(data), 'server.port': port,
                    'server.threads.min': c['HTTP_THREADS_MIN'], 'server.threads.max': c['HTTP_THREADS_MAX'],
                    'broker.port': c['BROKER_PORT'], 'broker.data-dir': str(data/'broker'),
                    'broker.url': 'tcp://127.0.0.1:'+c['BROKER_PORT'],
                    'source.base-url': c['SOURCE_BASE_URL'],
                    'ingestion.url': 'http://127.0.0.1:'+c['INGESTION_BASE_PORT'],
                    'datamart.cache-mb': c['DATAMART_CACHE_MB']}
            if service in ('ingestion', 'indexing'):
                opts['consumer.threads'] = c[service.upper()+'_CONSUMER_THREADS']
            command = [java, '-Xms'+c['JAVA_HEAP_MIN'], '-Xmx'+c['JAVA_HEAP_MAX']]
            command += [f'-D{k}={v}' for k,v in opts.items()]
            command += ['-jar', str(ROOT/module/'target'/f'{module}.jar')]
            row = launch(name, command)
            wait_ready(row, port, timeout, service)
            print(f'Ready: {name} port={port} pid={row["pid"]}', flush=True)
        prefix = runtime/'nginx'
        row = launch('gateway', [nginx, '-p', str(prefix)+'/', '-c', 'nginx.conf', '-g', 'daemon off;'])
        host = c['GATEWAY_BIND'] if c['GATEWAY_BIND'] != '0.0.0.0' else '127.0.0.1'
        wait_ready(row, gateway, timeout, 'search', host)
        if not all(alive(r) for r in rows):
            raise RuntimeError('A process exited during startup')
        print(f'Running mode={args.mode}; data={data}; gateway=http://{host}:{gateway}; registry={registry}')
        if args.mode == 'full':
            print('Services started; no ingestion request submitted automatically. Validate feeder API separately.')
    except BaseException:
        stop(rows, stop_timeout)
        save(registry, [])
        raise
    finally:
        signal.signal(signal.SIGTERM, old)

def host_sample():
    cpu = list(map(int, Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
    mem = {line.split(':')[0]: int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines()}
    return sum(cpu), cpu[3]+cpu[4], mem['MemTotal']-mem['MemAvailable']

def monitor(c, args, registry):
    interval = integer(c, 'MONITOR_INTERVAL_SECONDS', high=3600)
    rows = json.loads(registry.read_text()) if registry.exists() else []
    if not any(alive(r) for r in rows):
        raise RuntimeError('No managed processes running')
    out = Path(args.output or str(registry.parent/'metrics'/time.strftime('%Y%m%d-%H%M%S'))).resolve()
    out.mkdir(parents=True, exist_ok=True)
    hz = os.sysconf('SC_CLK_TCK')
    previous = {r['pid']: proc(r['pid']) for r in rows if alive(r)}
    host_prev = host_sample()
    last = time.monotonic()
    deadline = last + args.duration if args.duration else float('inf')
    print(f'Monitoring Linux/WSL host and managed processes: {out}; Ctrl+C to finish', flush=True)
    # Exclusive creation prevents accidental replacement of earlier measurements.
    with (out/'processes.csv').open('x', newline='') as pf, (out/'host.csv').open('x', newline='') as hf:
        pw, hw = csv.writer(pf), csv.writer(hf)
        pw.writerow(['epoch_s','name','pid','alive','cpu_percent_one_core','rss_bytes'])
        hw.writerow(['epoch_s','cpu_percent_all_cores','memory_used_bytes'])
        try:
            while time.monotonic() < deadline:
                time.sleep(min(interval, max(0, deadline-time.monotonic())))
                now = time.monotonic(); elapsed = now-last; stamp = time.time()
                current = host_sample(); delta = current[0]-host_prev[0]
                hw.writerow([stamp, 100*(delta-(current[1]-host_prev[1]))/delta if delta else 0, current[2]])
                for row in rows:
                    info = proc(row['pid']) if alive(row) else None
                    before = previous.get(row['pid'])
                    cpu = 100*(info['ticks']-before['ticks'])/hz/elapsed if info and before else ''
                    pw.writerow([stamp,row['name'],row['pid'],int(info is not None),cpu,info['rss'] if info else ''])
                    previous[row['pid']] = info
                # nginx workers are separate processes: sample descendants of its master as well.
                masters = {r['pid'] for r in rows if r['name']=='gateway' and alive(r)}
                for entry in Path('/proc').iterdir():
                    if not entry.name.isdigit(): continue
                    try:
                        fields=(entry/'stat').read_text().rsplit(')',1)[1].split()
                        if int(fields[1]) not in masters: continue
                        pid=int(entry.name); info=proc(pid); before=previous.get(pid)
                        if not info: continue
                        cpu=100*(info['ticks']-before['ticks'])/hz/elapsed if before and before['start']==info['start'] else ''
                        pw.writerow([stamp,'gateway-worker',pid,1,cpu,info['rss']]); previous[pid]=info
                    except (FileNotFoundError, ProcessLookupError): pass
                pf.flush(); hf.flush(); last=now; host_prev=current
        except KeyboardInterrupt:
            pass

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['run','stop','status','monitor'])
    parser.add_argument('--config', type=Path, default=ROOT/'config/deployment.env')
    parser.add_argument('--mode', choices=['full','search'], default='full')
    parser.add_argument('--data-dir')
    parser.add_argument('--duration', type=int, default=0, help='monitor seconds; 0 until Ctrl+C')
    parser.add_argument('--output')
    args=parser.parse_args(); args.config=args.config.resolve()
    if args.duration < 0: parser.error('--duration must be nonnegative')
    c=config(args.config)
    runtime=Path(c['RUN_DIR'])
    if not runtime.is_absolute(): raise ValueError('RUN_DIR must be absolute')
    runtime.mkdir(parents=True, exist_ok=True)
    registry=runtime/'processes.json'
    if args.action=='monitor':
        monitor(c,args,registry); return
    with (runtime/'lifecycle.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        rows=json.loads(registry.read_text()) if registry.exists() else []
        if args.action=='run': start(c,args,registry,rows)
        elif args.action=='stop':
            stop(rows,integer(c,'STOP_TIMEOUT_SECONDS')); save(registry,[]); print('Managed processes stopped')
        else:
            for row in rows: print(row['name'],row['pid'],'running' if alive(row) else 'stopped')

if __name__=='__main__':
    try: main()
    except (Exception, KeyboardInterrupt) as exc:
        print(f'ERROR: {exc}', file=sys.stderr); sys.exit(1)

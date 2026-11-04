"""Lifecycle integration tests with test doubles; no Java/nginx runtime required."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('manage', HERE/'manage.py')
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class LifecycleTests(unittest.TestCase):
    def test_pid_identity_rejects_reuse(self):
        from unittest.mock import patch
        good=dict(pid=123,start='100',boot=m.BOOT)
        with patch.object(m,'proc',return_value=dict(start='100')):
            self.assertTrue(m.alive(good))
            self.assertFalse(m.alive(dict(good,start='0')))
            self.assertFalse(m.alive(dict(good,boot='old-boot')))

    def test_integer_validation(self):
        for value in ('-1','0','1;exit','65536'):
            with self.assertRaises(ValueError): m.integer({'port':value},'port')
        self.assertEqual(m.integer({'port':'8080'},'port'),8080)

    def test_process_stop_and_sampling(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(prefix='deployment test ') as folder:
            row=dict(name='test',pid=123,start='10',boot=m.BOOT,log='test')
            registry=Path(folder)/'processes.json'; m.save(registry,[row])
            args=SimpleNamespace(duration=1,output=str(Path(folder)/'metrics'))
            with patch.object(m,'alive',return_value=True), patch.object(m,'proc',return_value=dict(start='10',ticks=100,rss=4096)):
                m.monitor({'MONITOR_INTERVAL_SECONDS':'1'},args,registry)
            self.assertIn('test',(Path(args.output)/'processes.csv').read_text())
            self.assertEqual(len((Path(args.output)/'host.csv').read_text().splitlines()),2)
            with patch.object(m,'alive',side_effect=[True,False,False,False,False]), patch.object(m.os,'kill') as kill:
                m.stop([row],1)
                kill.assert_called_once_with(123,m.signal.SIGTERM)

    def test_stale_record_does_not_kill_process(self):
        from unittest.mock import patch
        with patch.object(m,'proc',return_value=dict(start='new')), patch.object(m.os,'kill') as kill:
            m.stop([dict(pid=123,start='old',boot=m.BOOT)],1)
            kill.assert_not_called()

    def test_invalid_batch_settings_fail_before_launch(self):
        from types import SimpleNamespace
        from unittest.mock import patch

        with tempfile.TemporaryDirectory() as folder:
            registry = Path(folder) / 'run' / 'processes.json'
            args = SimpleNamespace(mode='full', data_dir=None)
            base_config = {
                'DATA_DIR': str(Path(folder) / 'data'),
                'HTTP_THREADS_MIN': '8',
                'HTTP_THREADS_MAX': '64',
                'STARTUP_TIMEOUT_SECONDS': '60',
                'STOP_TIMEOUT_SECONDS': '30',
                'INDEXING_BATCH_SIZE': '20',
                'INDEXING_BATCH_WAIT_MS': '200',
            }

            for key in ('INDEXING_BATCH_SIZE', 'INDEXING_BATCH_WAIT_MS'):
                for value in ('0', '-1', 'invalid'):
                    with self.subTest(key=key, value=value):
                        config = dict(base_config)
                        config[key] = value
                        with patch.object(m.subprocess, 'Popen') as launch:
                            with self.assertRaisesRegex(ValueError, key):
                                m.start(config, args, registry, [])
                            launch.assert_not_called()
                        self.assertFalse(registry.exists())

    def test_port_check_ignores_time_wait_but_not_listeners(self):
        import http.server, socket, threading, urllib.request
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
            def log_message(self, *args): pass
        server=http.server.HTTPServer(('0.0.0.0',0),Handler); port=server.server_address[1]
        thread=threading.Thread(target=server.serve_forever); thread.start()
        urllib.request.urlopen(f'http://127.0.0.1:{port}/status').read()   # like a readiness check
        server.shutdown(); server.server_close(); thread.join()
        m.ensure_free(port)                    # only TIME_WAIT left: the port can be reused
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            listener.bind(('0.0.0.0',port)); listener.listen()
            with self.assertRaises(OSError): m.ensure_free(port)

if __name__=='__main__': unittest.main()

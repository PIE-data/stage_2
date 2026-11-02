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

    def test_full_mode_fails_before_starting_skeletons(self):
        with tempfile.TemporaryDirectory() as folder:
            config=Path(folder)/'deployment.env'
            config.write_text('RUN_DIR="'+folder+'/run"\n')
            result=subprocess.run(['python3',str(HERE/'manage.py'),'run','--config',str(config)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn('Full mode blocked',result.stderr)
            self.assertFalse((Path(folder)/'run/processes.json').exists())

if __name__=='__main__': unittest.main()

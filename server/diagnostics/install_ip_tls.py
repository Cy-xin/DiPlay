#!/usr/bin/env python3
"""Apply the owner-approved isolated 8443 plan. Does not change any existing 443 vhost."""
import datetime,os,shutil,subprocess
from pathlib import Path

def run(*args):subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
def main():
 if os.geteuid()!=0:raise SystemExit('Run as root only after owner approval for the isolated 8443 listener.')
 conf=Path('/etc/nginx/conf.d/diplay-diagnostics-ip.conf')
 if conf.exists() and 'listen 8443 ssl;' not in conf.read_text():raise RuntimeError('Unexpected preexisting TLS vhost; refusing overwrite')
 listeners=subprocess.check_output(['ss','-ltn'],text=True)
 if ':8443' in listeners and not conf.exists():raise RuntimeError('8443 already in use')
 existing=Path('/etc/nginx/conf.d/kayxinstudio.conf').read_bytes()
 backup=Path('/var/backups/diplay-diagnostics')/('tls-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
 backup.mkdir(parents=True,mode=0o700)
 if conf.exists():shutil.copy2(conf,backup/conf.name)
 tls=Path('/etc/diplay-diagnostics/tls');tls.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(tls,0o700)
 # acme.sh writes keys only on this server; no key material is transferred or emitted.
 run('/root/.acme.sh/acme.sh','--install-cert','-d','140.245.48.181','--ecc',
     '--key-file',str(tls/'privkey.pem'),'--fullchain-file',str(tls/'fullchain.pem'),
     '--reloadcmd','nginx -t && systemctl reload nginx')
 os.chmod(tls/'privkey.pem',0o600)
 conf.write_text(Path(__file__).with_name('diplay-diagnostics-ip.conf').read_text())
 try:
  run('nginx','-t');run('systemctl','reload','nginx')
  assert Path('/etc/nginx/conf.d/kayxinstudio.conf').read_bytes()==existing,'Existing named vhost unexpectedly changed'
 except Exception:
  old=backup/conf.name
  if old.exists():shutil.copy2(old,conf)
  else:conf.unlink(missing_ok=True)
  run('nginx','-t');run('systemctl','reload','nginx');raise
 Path('/etc/cron.d/diplay-diagnostics-certificate').write_text(
  '17 3 * * * root /root/.acme.sh/acme.sh --renew -d 140.245.48.181 --ecc > /var/log/diplay-diagnostics-cert-renew.log 2>&1\n')
 os.chmod('/etc/cron.d/diplay-diagnostics-certificate',0o644)
 print('Isolated 8443 listener configured. Existing 443 vhost bytes unchanged. Check firewall separately.')
if __name__=='__main__':main()

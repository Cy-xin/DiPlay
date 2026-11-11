#!/usr/bin/env python3
"""Run as root on myserver after copying server.py beside this file. Never emits credentials."""
import datetime,os,pwd,shutil,subprocess,urllib.request
from pathlib import Path
APP=Path('/opt/diplay-diagnostics');SITE=Path('/etc/nginx/conf.d/kayxinstudio.conf')
SNIPPET=Path('/etc/nginx/snippets/diplay-diagnostics.conf')
UNIT=Path('/etc/systemd/system/diplay-diagnostics.service')
def run(*args):subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
def blocks(s):
 import re
 for match in re.finditer(r'\bserver\s*\{',s):
  start=match.end()-1;depth=0;quote=None;comment=False;escaped=False
  for i in range(start,len(s)):
   c=s[i]
   if comment:
    if c=='\n':comment=False
    continue
   if escaped:escaped=False;continue
   if c=='\\':escaped=True;continue
   if quote:
    if c==quote:quote=None
    continue
   if c in ('"',"'"):quote=c;continue
   if c=='#':comment=True;continue
   if c=='{':depth+=1
   if c=='}':
    depth-=1
    if depth==0:yield start,i;slice_end=i;break

def main():
 if os.geteuid()!=0:raise SystemExit('root is required for service install')
 stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
 backup=Path('/var/backups/diplay-diagnostics')/stamp;backup.mkdir(parents=True,mode=0o700)
 original=SITE.read_text();shutil.copy2(SITE,backup/SITE.name)
 for path in (SNIPPET,UNIT):
  if path.exists():shutil.copy2(path,backup/path.name)
 try:uid=pwd.getpwnam('diplaydiag')
 except KeyError:
  run('useradd','--system','--home-dir','/var/lib/diplay-diagnostics','--shell','/usr/sbin/nologin','diplaydiag');uid=pwd.getpwnam('diplaydiag')
 APP.mkdir(exist_ok=True,mode=0o755)
 shutil.copy2(Path(__file__).with_name('server.py'),APP/'server.py');os.chmod(APP/'server.py',0o644)
 data=Path('/var/lib/diplay-diagnostics');data.mkdir(exist_ok=True,mode=0o700);os.chown(data,uid.pw_uid,uid.pw_gid)
 UNIT.write_text('''[Unit]
Description=Private DiPlay diagnostic upload intake
After=network.target
[Service]
User=diplaydiag
Group=diplaydiag
ExecStart=/usr/bin/python3 /opt/diplay-diagnostics/server.py serve
Environment=DIPLAY_DIAGNOSTICS_DATA=/var/lib/diplay-diagnostics
UMask=0077
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/diplay-diagnostics
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=384M
TasksMax=32
[Install]
WantedBy=multi-user.target
''')
 SNIPPET.parent.mkdir(exist_ok=True)
 SNIPPET.write_text('''# Only this dedicated path is routed to the private intake. No public report retrieval.
location ^~ /diplay-diagnostics/ {
    proxy_pass http://127.0.0.1:8796;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 30s;
    proxy_connect_timeout 5s;
    proxy_request_buffering on;
    client_max_body_size 8m;
    access_log off;
    add_header Cache-Control "no-store" always;
}
''')
 include='    include /etc/nginx/snippets/diplay-diagnostics.conf;\n'
 if include.strip() not in original:
  found=[(a,b) for a,b in blocks(original) if 'server_name kayxinstudio.ccwu.cc' in original[a:b] and 'listen 443' in original[a:b]]
  if len(found)!=1:raise RuntimeError('Expected one existing HTTPS vhost')
  _,end=found[0];SITE.write_text(original[:end]+include+original[end:])
 try:
  run('nginx','-t');run('systemctl','daemon-reload');run('systemctl','enable','--now','diplay-diagnostics');run('systemctl','restart','diplay-diagnostics')
  # Let systemd's ordered startup finish by probing bounded loopback requests.
  import time
  for i in range(20):
   try:
    with urllib.request.urlopen('http://127.0.0.1:8796/diplay-diagnostics/v1/health',timeout=1) as response:
     if response.status==200:break
   except OSError:time.sleep(.2)
  else:raise RuntimeError('Intake did not become healthy')
  run('systemctl','reload','nginx')
 except Exception:
  SITE.write_text(original)
  for path in (SNIPPET,UNIT):
   previous=backup/path.name
   if previous.exists():shutil.copy2(previous,path)
   elif path.exists():path.unlink()
  run('nginx','-t');run('systemctl','reload','nginx')
  raise
 print('Installed private intake. Existing site backed up at '+str(backup))
if __name__=='__main__':main()

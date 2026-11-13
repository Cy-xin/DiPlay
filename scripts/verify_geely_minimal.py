#!/usr/bin/env python3
"""Check the final local receiver APK without printing credential contents."""
import argparse, hashlib, json, re, subprocess, zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--auth',type=Path,required=True);p.add_argument('--mapping',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
aapt=a.sdk/'build-tools/36.0.0/aapt'
badging=subprocess.check_output([str(aapt),'dump','badging',str(a.apk)],text=True)
assert "package: name='com.shihab.diplay'" in badging
assert "application-label:'DiPlay Geely'" in badging
assert "sdkVersion:'30'" in badging
manifest=subprocess.check_output([str(aapt),'dump','xmltree',str(a.apk),'AndroidManifest.xml'],text=True)
for component in ('receiver','provider'):
 assert not re.search(r'\bE: '+component+r'\b',manifest),f'Unexpected {component}'
assert len(re.findall(r'\bE: activity\b',manifest))==1
assert len(re.findall(r'\bE: service\b',manifest))==1
service=manifest.split('E: service',1)[1].split('E: ',1)[0]
assert 'com.shilapi.xcertplay.geely.ReceiverSessionService' in service
assert re.search(r'android:exported[^\n]*0x0',service)
assert 'android:foregroundServiceType' in service
for perm in ('RECEIVE_BOOT_COMPLETED','SYSTEM_ALERT_WINDOW','WRITE_SETTINGS','READ_LOGS','REQUEST_INSTALL_PACKAGES','MANAGE_OWN_CALLS'):
 assert perm not in badging,perm
assert 'android.permission.RECORD_AUDIO' in badging
with zipfile.ZipFile(a.apk) as z:
 for name in ('identity.pk8','certificate.p7b'):
  assert z.read('assets/offline-mfi/'+name)==(a.auth/'offline-mfi'/name).read_bytes()
 native=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
 allowed={'libandroidx.graphics.path.so','libdatastore_shared_counter.so'}
 assert all(Path(n).name in allowed for n in native),'Unexpected native modules'
 # Compose path rendering and DataStore ship official native helpers; no codec or vehicle libraries.
 assert not any('codec' in n.lower() or 'mfi' in n.lower() for n in native)
mapping=a.mapping.read_text()
retained=[]
for line in mapping.splitlines():
 if line.startswith('com.shilapi.xcertplay.') and ' -> ' in line and line.endswith(':'):
  original,target=line[:-1].split(' -> ')
  if not target.startswith('R8$$REMOVED$$'):
   retained.append(original)
for marker in ('.hud.', '.vehicle.', '.glance.', '.media.Ambient', '.network.WifiP2pGroupManager', '.network.LocalOnlyHotspotManager', '.network.CarHotspotTethering', '.transport.IphoneUsbHost', '.transport.Ch341Usb', '.network.CarPlayVpnService'):
 assert not any(marker in name and not name.startswith("com.shilapi.xcertplay.vehicle.BluetoothAudioHandoff") for name in retained),f'Retained excluded implementation: {marker}'
result={'package':'com.shihab.diplay','label':'DiPlay Geely','min_sdk':30,'target_sdk':30,'bytes':a.apk.stat().st_size,'sha256':hashlib.sha256(a.apk.read_bytes()).hexdigest(),'components':{'activities':1,'services':1,'receivers':0,'providers':0},'authentication':'Selected local files match APK assets; no key contents emitted','excluded_implementations':'R8 mapping check passed','physical_carplay':'NOT TESTED: real iPhone/head unit required'}
a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False,indent=2))

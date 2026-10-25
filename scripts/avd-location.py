#!/usr/bin/env python3
"""Explicit test input for owned AVDs. Never supplies product default locations."""
import argparse, math, os, pathlib, subprocess, time
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('latitude',type=float); p.add_argument('longitude',type=float)
p.add_argument('--seconds',type=int,default=300)
p.add_argument('--serial',default='emulator-5554')
a=p.parse_args()
if not(math.isfinite(a.latitude) and -90<=a.latitude<=90 and math.isfinite(a.longitude) and -180<=a.longitude<=180 and 1<=a.seconds<=3600):
    p.error('Valid coordinates and --seconds between 1 and 3600 required')
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb=str(pathlib.Path(sdk)/'platform-tools/adb') if sdk else 'adb'
def run(*args):
    return subprocess.check_output([adb,'-s',a.serial,*args],text=True,stderr=subprocess.STDOUT,timeout=20)
name=run('emu','avd','name').splitlines()[0].strip()
if name not in ('TogetherPleos','TogetherReplay'):
    raise SystemExit('Only the task-owned TogetherPleos/TogetherReplay test AVD is allowed')
# Connect 2.1.0 hardware GNSS stopNmea can deadlock system_server. Test provider avoids this HAL.
run('shell','appops','set','2000','android:mock_location','allow')
if 'gps provider [mock]' not in run('shell','dumpsys','location'):
    run('shell','cmd','location','providers','add-test-provider','gps','--requiresSatellite','--supportsAltitude','--supportsSpeed','--supportsBearing','--powerRequirement','3')
run('shell','cmd','location','providers','set-test-provider-enabled','gps','true')
run('shell','cmd','location','set-location-enabled','true')
print(f'AVD TEST INPUT ONLY: {name}, {a.latitude}, {a.longitude}, {a.seconds}s',flush=True)
print('Stop with Ctrl+C. GPS becomes stale after input stops. APK creates no synthetic participants.',flush=True)
end=time.monotonic()+a.seconds
try:
    while time.monotonic()<end:
        run('shell','cmd','location','providers','set-test-provider-location','gps','--location',f'{a.latitude},{a.longitude}','--accuracy','5')
        time.sleep(min(5,max(0,end-time.monotonic())))
except KeyboardInterrupt:
    pass
print('Test input stopped. Restart the AVD to restore its original hardware GPS provider.')

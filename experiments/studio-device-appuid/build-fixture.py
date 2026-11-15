#!/usr/bin/env python3
"""Build an isolated, self-instrumented test APK; never installs it."""
import glob
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
if ROOT != Path('/home/dennis/.kontraprompter-studio-device-identity'):
    raise SystemExit('Unexpected fixture worktree')
if shutil.disk_usage('/home/dennis').free < 512 * 1024 * 1024:
    raise SystemExit('Not started: home disk reserve below 512 MiB')
CACHE='/home/dennis/.gradle/caches/modules-2/files-2.1/'
def jar(spec):
    found=glob.glob(CACHE+spec+'/*/*.jar')
    if len(found)!=1: raise RuntimeError('Missing or ambiguous local dependency: '+spec)
    return found[0]

compiler=[jar(x) for x in ('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20',
 'org.jetbrains.kotlin/kotlin-stdlib/2.2.20','org.jetbrains.kotlin/kotlin-script-runtime/2.2.20',
 'org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20',
 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0','org.jetbrains/annotations/13.0')]
stdlib=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21')
android='/home/dennis/Android/Sdk/platforms/android-36/android.jar'
build=Path('/home/dennis/Android/Sdk/build-tools/35.0.0')
out=Path(tempfile.mkdtemp(prefix='studio-appuid-fixture.',dir='/dev/shm'))
classes=out/'classes';dex=out/'dex';classes.mkdir();dex.mkdir()
print('Fixture build directory:',out,flush=True)
source=ROOT/'app/src/main/java/de/kontrakurs/kontraprompter/cloud'
fixture=ROOT/'experiments/studio-device-appuid'
subprocess.run(['java','-Xmx160m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join(compiler),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','2.0','-api-version','2.0',
 '-no-stdlib','-no-reflect','-classpath',os.pathsep.join([stdlib,android]),'-d',str(classes),
 *[str(source/name) for name in ('StudioDeviceTranscript.kt','StudioDeviceIdentity.kt','AndroidStudioIdentityStore.kt')],
 str(fixture/'StudioIdentityInstrumentation.kt'),str(fixture/'FixtureOwnership.kt')],check=True,timeout=120)
code=out/'code.jar';subprocess.run(['jar','cf',str(code),'-C',str(classes),'.'],check=True,timeout=20)
env={**os.environ,'JAVA_TOOL_OPTIONS':'-Xmx192m -XX:ActiveProcessorCount=1'}
result=subprocess.run([str(build/'d8'),'--min-api','26','--lib',android,'--output',str(dex),str(code),stdlib],
 env=env,capture_output=True,text=True,timeout=120)
if result.returncode or 'Warning:' in result.stderr or 'Error:' in result.stderr:
    print(result.stderr[-2500:]);raise SystemExit('D8 failed or produced warnings')
base=out/'base.apk';aligned=out/'aligned.apk';signed=out/'studio-appuid-fixture.apk';key=out/'fixture-test-key.p12'
subprocess.run([str(build/'aapt2'),'link','--manifest',str(fixture/'AndroidManifest.xml'),'-I',android,'-o',str(base)],check=True,timeout=30)
subprocess.run(['zip','-q',str(base),'classes.dex'],cwd=dex,check=True,timeout=30)
subprocess.run([str(build/'zipalign'),'-f','4',str(base),str(aligned)],check=True,timeout=30)
# A disposable test-only signing key, unrelated to either user's app or server.
subprocess.run(['keytool','-genkeypair','-alias','fixture','-keyalg','EC','-groupname','secp256r1',
 '-keystore',str(key),'-storetype','PKCS12','-storepass','fixture-only','-keypass','fixture-only',
 '-dname','CN=Temporary Studio Fixture','-validity','2','-noprompt'],check=True,capture_output=True,timeout=30)
subprocess.run([str(build/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:fixture-only',
 '--key-pass','pass:fixture-only','--out',str(signed),str(aligned)],check=True,timeout=30)
subprocess.run([str(build/'apksigner'),'verify','--verbose',str(signed)],check=True,timeout=30)
subprocess.run([str(build/'aapt'),'dump','badging',str(signed)],check=True,timeout=30)
print('APK SHA256:',hashlib.sha256(signed.read_bytes()).hexdigest(),flush=True)
print('APK:',signed,flush=True)

#!/usr/bin/env python3
"""Compile actual gated transport with JVM-only shims and exercise loopback fixture."""
import glob,os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
server=Path('/home/dennis/.videomaker-studio-device-http-fixture')
if root!=Path('/home/dennis/.kontraprompter-studio-device-client-main'):raise SystemExit('Unexpected worktree')
cache='/home/dennis/.gradle/caches/modules-2/files-2.1/'
def jar(spec):
 found=glob.glob(cache+spec+'/*/*.jar')
 if len(found)!=1:raise RuntimeError('Missing or ambiguous dependency: '+spec)
 return found[0]
compiler=[jar(x) for x in ('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20','org.jetbrains.kotlin/kotlin-stdlib/2.2.20','org.jetbrains.kotlin/kotlin-script-runtime/2.2.20','org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0','org.jetbrains/annotations/13.0')]
stdlib=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21');gson=jar('com.google.code.gson/gson/2.11.0');junit=jar('junit/junit/4.13.2');hamcrest=jar('org.hamcrest/hamcrest-core/1.3')
source=root/'app/src/main/java/de/kontrakurs/kontraprompter/cloud';fixture=root/'experiments/studio-device-transport'
with tempfile.TemporaryDirectory(prefix='studio-actual-transport.',dir='/dev/shm') as out:
 classes=Path(out)/'classes';classes.mkdir()
 subprocess.run(['java','-Xmx160m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','2.0','-api-version','2.0','-no-stdlib','-no-reflect','-classpath',os.pathsep.join([stdlib,gson,junit,hamcrest]),'-d',str(classes),*[str(source/n) for n in ('DeviceLoginCoordinator.kt','StudioDeviceLoginTransport.kt')],*[str(fixture/n) for n in ('StudioDeviceTranscript.kt','StudioOwnedDeviceTranscript.kt','ActualTransportHttpFixture.kt','shim/android/util/Base64.kt','shim/org/json/JSONObject.kt')],str(root/'app/src/test/java/de/kontrakurs/kontraprompter/cloud/StudioDeviceLoginTransportTest.kt')],check=True,timeout=120)
 subprocess.run(['java','-Xmx64m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join([str(classes),stdlib,gson,junit,hamcrest]),'org.junit.runner.JUnitCore','de.kontrakurs.kontraprompter.cloud.StudioDeviceLoginTransportTest'],check=True,timeout=60)
 env={**os.environ,'NODE_OPTIONS':'--max-old-space-size=128','TMPDIR':'/dev/shm','STUDIO_DEVICE_ACTUAL_JVM_CLASSES':str(classes),'STUDIO_DEVICE_ACTUAL_STDLIB':stdlib,'STUDIO_DEVICE_ACTUAL_GSON':gson}
 subprocess.run(['node','--test','tests/actual-device-transport-http.test.mjs'],cwd=server,env=env,check=True,timeout=60)

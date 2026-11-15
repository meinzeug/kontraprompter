#!/usr/bin/env python3
"""Compile isolated v2 transcript tests and run a synthetic Node/JVM proof."""
import glob
import os
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
server=Path('/home/dennis/.videomaker-studio-device-http-fixture')
if root != Path('/home/dennis/.kontraprompter-studio-device-identity'):
    raise SystemExit('Unexpected Android worktree')
cache='/home/dennis/.gradle/caches/modules-2/files-2.1/'
def jar(spec):
    found=glob.glob(cache+spec+'/*/*.jar')
    if len(found)!=1: raise RuntimeError('Missing or ambiguous dependency: '+spec)
    return found[0]
compiler=[jar(x) for x in ('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20','org.jetbrains.kotlin/kotlin-stdlib/2.2.20','org.jetbrains.kotlin/kotlin-script-runtime/2.2.20','org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0','org.jetbrains/annotations/13.0')]
stdlib=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21')
junit=jar('junit/junit/4.13.2')
hamcrest=jar('org.hamcrest/hamcrest-core/1.3')
gson=jar('com.google.code.gson/gson/2.11.0')
src=root/'app/src/main/java/de/kontrakurs/kontraprompter/cloud'
tests=root/'app/src/test/java/de/kontrakurs/kontraprompter/cloud'
with tempfile.TemporaryDirectory(prefix='studio-owned-device-cross.',dir='/dev/shm') as out:
    output=Path(out)/'classes';output.mkdir()
    subprocess.run(['java','-Xmx160m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','2.0','-api-version','2.0','-no-stdlib','-no-reflect','-classpath',os.pathsep.join([stdlib,junit,hamcrest,gson]),'-d',str(output),*[str(src/n) for n in ('StudioDeviceTranscript.kt','StudioOwnedDeviceTranscript.kt','StudioDeviceIdentity.kt')],*[str(tests/n) for n in ('StudioDeviceTranscriptTest.kt','StudioOwnedDeviceTranscriptTest.kt','StudioDeviceIdentityTest.kt')],*[str(root/'experiments/studio-owned-device'/n) for n in ('OwnedDeviceSignatureFixture.kt','OwnedDeviceHttpFixture.kt','StudioOwnedProofGate.kt','StudioOwnedProofGateTest.kt','StudioOwnedSharedSession.kt','StudioOwnedSharedSessionTest.kt')]],check=True,timeout=120)
    subprocess.run(['java','-Xmx64m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join([str(output),stdlib,junit,hamcrest]),'org.junit.runner.JUnitCore','de.kontrakurs.kontraprompter.cloud.StudioDeviceTranscriptTest','de.kontrakurs.kontraprompter.cloud.StudioOwnedDeviceTranscriptTest','de.kontrakurs.kontraprompter.cloud.StudioDeviceIdentityTest','de.kontrakurs.kontraprompter.cloud.StudioOwnedProofGateTest','de.kontrakurs.kontraprompter.cloud.StudioOwnedSharedSessionTest'],check=True,timeout=60)
    env={**os.environ,'NODE_OPTIONS':'--max-old-space-size=128','TMPDIR':'/dev/shm','STUDIO_DEVICE_JVM_CLASS_DIR':str(output),'STUDIO_DEVICE_KOTLIN_STDLIB':stdlib,'STUDIO_DEVICE_GSON':gson}
    subprocess.run(['node','--test','tests/owned-device-cross-runtime.test.mjs','tests/owned-device-jvm-http.test.mjs'],cwd=server,env=env,check=True,timeout=60)

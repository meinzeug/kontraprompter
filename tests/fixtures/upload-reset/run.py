#!/usr/bin/env python3
"""Compile original app upload sources with host-only Android/HTTPS stubs."""
import glob,os,subprocess,sys,tempfile,atexit
from pathlib import Path
root=Path(__file__).resolve().parents[3]
fixture=Path(__file__).resolve().parent
cache=str(Path.home()/'.gradle/caches/modules-2/files-2.1/')+'/'
def jar(spec):
 found=glob.glob(cache+spec+'/*/*.jar')
 if len(found)!=1:raise RuntimeError('missing or ambiguous '+spec)
 return found[0]
compiler=[jar(x) for x in ('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20','org.jetbrains.kotlin/kotlin-stdlib/2.2.20','org.jetbrains.kotlin/kotlin-script-runtime/2.2.20','org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0','org.jetbrains/annotations/13.0')]
runtime=[jar(x) for x in ('org.jetbrains.kotlin/kotlin-stdlib/2.2.20','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1','com.google.code.gson/gson/2.11.0','org.jetbrains/annotations/13.0')]
revision=sys.argv[1] if len(sys.argv)>1 else 'HEAD'
temp=tempfile.TemporaryDirectory(prefix='kp-upload-reset-test-');atexit.register(temp.cleanup)
snapshot=Path(temp.name)/'snapshot';snapshot.mkdir()
names=['StudioClient.kt','CloudUploadCoordinator.kt','UploadJournal.kt','UploadConflict.kt','UploadOperationGate.kt','SessionCookie.kt','UploadRules.kt']
paths=[*(f'app/src/main/java/de/kontrakurs/kontraprompter/cloud/{name}' for name in names),'app/src/main/java/de/kontrakurs/kontraprompter/video/RecordingOrigin.kt']
sources=[]
for path in paths:
 target=snapshot/Path(path).name;target.write_bytes(subprocess.check_output(['git','-C',str(root),'show',revision+':'+path]));sources.append(target)
sources += [*sorted((fixture/'stubs').glob('*.kt')),fixture/'UploadResetFixture.kt']
out=Path(temp.name)/'classes';out.mkdir()
subprocess.run(['java','-Xmx256m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','2.0','-api-version','2.0','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(runtime),'-d',str(out),*[str(x) for x in sources]],check=True,timeout=120)
subprocess.run(['java','-Xmx192m','-cp',os.pathsep.join([str(out),*runtime]),'de.kontrakurs.kontraprompter.cloud.UploadResetFixtureKt'],check=True,timeout=60)

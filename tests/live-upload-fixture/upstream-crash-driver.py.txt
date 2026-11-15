import glob,http.client,http.cookiejar,http.server,json,os,signal,socket,struct,subprocess,tempfile,threading,time,urllib.request,uuid
base='http://127.0.0.1:3391';jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def post(path,payload):
 request=urllib.request.Request(base+path,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','Origin':base},method='POST')
 with opener.open(request,timeout=15) as response:return json.load(response)
post('/api/login',{'password':os.environ['LIVE_FIXTURE_PASSWORD']});recording=str(uuid.uuid4());project=post('/api/projects/lan-captures',{'recordingId':recording})['projectId'];cookie='; '.join(f'{v.name}={v.value}' for v in jar)
class DropPut(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def proxy(self):
  conn=http.client.HTTPConnection('127.0.0.1',3391,timeout=15);headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection')};headers['Host']='127.0.0.1:3391'
  size=int(self.headers.get('Content-Length','0'));body=self.rfile.read(size) if size else None
  conn.request(self.command,self.path,body=body,headers=headers);reply=conn.getresponse();data=reply.read();self.send_response(reply.status)
  for name,value in reply.getheaders():
   if name.lower() not in ('connection','content-length','transfer-encoding'):self.send_header(name,value)
  self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);conn.close()
 do_GET=proxy
 do_POST=proxy
 def do_PUT(self):
  self.rfile.read(min(4096,int(self.headers['Content-Length'])));self.connection.shutdown(socket.SHUT_RDWR);self.connection.close()
proxy=http.server.ThreadingHTTPServer(('127.0.0.1',0),DropPut);threading.Thread(target=proxy.serve_forever,daemon=True).start()
cache=os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')
def lib(group,name,version):
 files=glob.glob(f'{cache}/{group}/{name}/{version}/*/*.jar');assert len(files)==1;return files[0]
stdlib=lib('org.jetbrains.kotlin','kotlin-stdlib','2.2.20');annotations=lib('org.jetbrains','annotations','23.0.0')
compiler=[lib('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.20'),stdlib,lib('org.jetbrains.kotlin','kotlin-script-runtime','2.2.20'),lib('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),lib('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),annotations]
classpath=[stdlib,annotations,os.environ['LIVE_JSON_JAR']];source=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),'app','src')
with tempfile.TemporaryDirectory(prefix='kontra-live-crash-') as folder:
 media=os.path.join(folder,'test.mp4');marker=os.path.join(folder,'intent');committed=os.path.join(folder,'committed');out=os.path.join(folder,'classes');stub=os.path.join(folder,'UploadRules.kt');os.mkdir(out)
 with open(media,'wb') as target,open(os.environ['LIVE_FIXTURE_SAMPLE'],'rb') as sample:target.write(sample.read());target.write(struct.pack('>I4s',1049000,b'free'));target.write(bytes(1048992))
 open(stub,'w').write(open(source+'/main/java/de/kontrakurs/kontraprompter/cloud/UploadRules.kt').read().split('/** A server response')[0])
 files=[source+'/main/java/de/kontrakurs/kontraprompter/cloud/LiveChunkEngine.kt',source+'/main/java/de/kontrakurs/kontraprompter/cloud/StudioLiveChunkTransport.kt',source+'/test/java/de/kontrakurs/kontraprompter/cloud/LiveChunkCrashFixture.kt',stub]
 subprocess.run(['java','-Xmx384m','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',os.pathsep.join(classpath),'-d',out,*files],check=True)
 env=os.environ.copy();env.update(LIVE_FIXTURE_ORIGIN=base,LIVE_FIXTURE_COOKIE=cookie,LIVE_FIXTURE_PROJECT=project,LIVE_FIXTURE_RECORDING=recording,LIVE_FIXTURE_MP4=media,LIVE_FIXTURE_MARKER=marker,LIVE_FIXTURE_COMMITTED=committed,LIVE_FIXTURE_PROXY=f'http://127.0.0.1:{proxy.server_port}')
 java=['java','-Dsun.net.http.allowRestrictedHeaders=true','-Xmx192m','-cp',os.pathsep.join([out,*classpath]),'de.kontrakurs.kontraprompter.cloud.LiveChunkCrashFixture']
 subprocess.run([*java,'interrupt'],env=env,check=True,timeout=45)
 child=subprocess.Popen([*java,'commit-and-wait'],env=env)
 try:
  end=time.monotonic()+90
  while time.monotonic()<end and not os.path.isfile(committed):
   if child.poll() is not None:raise RuntimeError('child failed before commit')
   time.sleep(.2)
  if not os.path.isfile(committed):raise TimeoutError('commit not reached')
  assert os.path.isfile(marker)
  os.kill(child.pid,signal.SIGKILL);assert child.wait(timeout=10)==-signal.SIGKILL
  subprocess.run([*java,'resume'],env=env,check=True,timeout=45)
  print('real network interruption and SIGKILL/restart passed',flush=True)
 finally:
  if child.poll() is None:child.kill();child.wait()
proxy.shutdown()

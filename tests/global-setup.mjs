import {spawn} from 'node:child_process';
import {existsSync} from 'node:fs';
import {join,resolve} from 'node:path';

const root=process.cwd();
const apiPort=Number(process.env.FOODFLOW_TEST_API_PORT??8094);
const webPort=Number(process.env.FOODFLOW_TEST_WEB_PORT??5181);
const apiUrl=`http://127.0.0.1:${apiPort}`;
const webUrl=`http://127.0.0.1:${webPort}`;
const jar=resolve(process.env.FOODFLOW_TEST_JAR??join(root,'backend','target','foodflow-api.jar'));

if(!existsSync(jar)) throw new Error('Build the Java API first: mvn -f backend/pom.xml package -DskipTests');

const children=[];
function start(command,args,env){
 const child=spawn(command,args,{cwd:root,env:{...process.env,...env},windowsHide:true,stdio:['ignore','pipe','pipe']});
 child.output='';
 child.stdout.on('data',data=>{child.output=(child.output+data.toString()).slice(-8000);});
 child.stderr.on('data',data=>{child.output=(child.output+data.toString()).slice(-8000);});
 children.push(child);
 return child;
}
async function waitFor(url,child,label){
 const deadline=Date.now()+60000;
 while(Date.now()<deadline){
  if(child.exitCode!==null) throw new Error(`${label} exited before becoming ready.\n${child.output}`);
  try{const response=await fetch(url);if(response.ok)return;}catch{}
  await new Promise(resolve=>setTimeout(resolve,400));
 }
 throw new Error(`${label} did not become ready at ${url}.\n${child.output}`);
}
async function stopAll(){
 for(const child of [...children].reverse()) if(child.exitCode===null) child.kill();
 await Promise.all(children.map(child=>new Promise(resolve=>{
  if(child.exitCode!==null)return resolve();
  const timeout=setTimeout(resolve,3000);
  child.once('exit',()=>{clearTimeout(timeout);resolve();});
 })));
}

export default async function globalSetup(){
 try{
  const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
  const backend=start(java,['-jar',jar,`--server.port=${apiPort}`],{
   DB_URL:'jdbc:h2:mem:foodflow_e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
   DEMO_OPERATIONS:'true',SANDBOX_PAYMENTS:'true',PAYMENT_WEBHOOK_SECRET:'playwright-local-test-secret',
  });
  await waitFor(`${apiUrl}/api/v1/health`,backend,'FoodFlow API');
  const frontend=start(process.execPath,[join(root,'node_modules','vite','bin','vite.js'),'--host','127.0.0.1','--port',String(webPort)],{FOODFLOW_API_URL:apiUrl});
  await waitFor(webUrl,frontend,'FoodFlow storefront');
 }catch(error){await stopAll();throw error;}
 return async()=>{await stopAll();};
}

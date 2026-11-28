from pathlib import Path
import hashlib,json,subprocess,sys
root=Path(__file__).parent
repo=Path('/workspace/scratch/49479c165f4e/work-translation-detection')
cache=Path('/home/agent/.gradle/caches/modules-2/files-2.1')
deps=Path('/workspace/scratch/4ae19a99b59d/jvm-deps')
java='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
compiler=[deps/x for x in ['kotlin-compiler-embeddable-2.4.20.jar','kotlin-stdlib-2.4.20.jar','kotlin-script-runtime-2.4.20.jar','kotlin-reflect-1.6.10.jar','kotlinx-coroutines-core-jvm-1.11.0.jar','annotations-23.0.0.jar']]
cp=[deps/x for x in ['kotlin-stdlib-2.4.20.jar','kotlinx-coroutines-core-jvm-1.11.0.jar','annotations-23.0.0.jar','junit-4.13.2.jar','hamcrest-core-1.3.jar']]
cp += [root/'tasks-core.jar',root/'tasks-text.jar',Path('/workspace/scratch/0f34c62a99bc/android-sdk/platforms/android-37.0/android.jar')]
cp += [next((cache/'com.google.guava/guava/33.7.2-jre').rglob('*-jre.jar')),next((cache/'com.google.protobuf/protobuf-javalite/4.28.2').rglob('*.jar'))]
paths=[
'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceTranslationLanguageDetector.kt',
'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/AndroidRecognitionService.kt',
'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MediaPipeJniContract.kt',
'app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceTranslationLanguageDetectorTest.kt',
'app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/TranslationRecognitionQualificationTest.kt']
inputs=[repo/x for x in paths]
out=root/'policy';out.mkdir(exist_ok=True)
manifest={'repository_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'scope':'Exact complete detector, JNI contract, recognition service and portable tests; actual MediaPipe Tasks text/core APIs and Android SDK 37 used for compilation. Policy, lifecycle, cancellation and dual-locale qualification execute on host with explicitly injected fake prediction backend. No native inference, Android speech service, microphone or device claim.','source_sha256':{x:hashlib.sha256((repo/x).read_bytes()).hexdigest() for x in paths},'compile_dependencies_sha256':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in cp},'production_stubs':[]}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
cmd=[java,'-Xmx384m','-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(map(str,cp)),'-d',str(out/'tests.jar'),*map(str,inputs)]
r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(out/'compile.log').write_text(r.stdout)
print('compile_exit',r.returncode);print(r.stdout)
if r.returncode:sys.exit(r.returncode)
r=subprocess.run([java,'-ea','-Xmx192m','-cp',str(out/'tests.jar')+':'+':'.join(map(str,cp)),'org.junit.runner.JUnitCore','dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationLanguageDetectorTest','dev.chungjungsoo.gptmobile.data.voice.TranslationRecognitionQualificationTest'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(out/'junit.log').write_text(r.stdout)
print('test_exit',r.returncode);print(r.stdout)
sys.exit(r.returncode)

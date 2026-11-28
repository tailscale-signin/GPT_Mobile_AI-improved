from pathlib import Path
import hashlib,json,subprocess,sys
ROOT=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/workspace/scratch/49479c165f4e/repo')
BASE=Path(__file__).parent
OUT=BASE/'wiring';OUT.mkdir(exist_ok=True)
CORE=BASE.parent/'translation-turn-host/integrated'
DETECTOR=BASE.parent/'translation-detection-host/policy'
DEPS=Path('/workspace/scratch/4ae19a99b59d/jvm-deps')
UI=BASE.parent/'voice-presentation-host-tests/deps'
CACHE=Path('/home/agent/.gradle/caches/modules-2/files-2.1')
PLUGIN=next((CACHE/'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.4.20').rglob('*.jar'))
SDK=Path('/workspace/scratch/0f34c62a99bc/android-sdk/platforms/android-37.0/android.jar')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
P='app/src/main/kotlin/dev/chungjungsoo/gptmobile/'
manifest={}
def read(p):
 data=(ROOT/p).read_bytes();manifest[p]=hashlib.sha256(data).hexdigest();return data.decode()
core=json.loads((CORE/'manifest.json').read_text())
for n in ['VoiceSession','VoiceTranslationPair','VoiceResponsePolicy','VoicePluginAudio','VoiceTranslationPlaybackPolicy']:
 p=P+'data/voice/'+n+'.kt';read(p);assert manifest[p]==core['sources_sha256'][p], 'core source binding changed: '+p
for p,expected in json.loads((DETECTOR/'manifest.json').read_text())['source_sha256'].items():
 if '/main/' in p:read(p);assert manifest[p]==expected, 'detector source binding changed: '+p
screen=read(P+'presentation/ui/voice/VoiceChatScreen.kt')
read(P+'presentation/ui/voice/VoiceBackendViewModel.kt')
vm=read(P+'presentation/ui/voice/VoiceRuntimeViewModel.kt')
helpers=screen[screen.index('    fun translationVoiceKey('):screen.index('    fun acceptedVoicePolicy(')]
effects=screen[screen.index('    LaunchedEffect(preferencesApplied, translationMode) {'):screen.index('    SideEffect {\n        if (preferencesApplied) applyAudioConfiguration()',screen.index('    fun translationVoiceKey('))]
profiles=screen[screen.index('    fun outputSpeechProfile()'):screen.index('    var kokoroVoice by ',screen.index('    fun outputSpeechProfile()'))]
playback=vm[vm.index('internal data class VoiceTranslationPlayback'):vm.index('\ninternal class VoiceRuntime(')]
detector=vm[vm.index('    private var translationDetector:'):vm.index('    fun acquire(',vm.index('    private var translationDetector:'))]
close='\n'.join(line for line in vm[vm.index('    override fun onCleared()'):].splitlines() if 'translationDetector' in line)
source='''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import android.content.Context
import androidx.compose.runtime.*
import dev.chungjungsoo.gptmobile.data.voice.*
import kotlinx.coroutines.*
'''+playback+'''
internal class ExtractedRuntimeDetectorOwner(private val context:Context) {
'''+detector+'\n    fun releaseDetector() {\n'+close+'\n    }\n}\n'+'''
internal class SystemAudioPort {var synthesisLanguageTag="en-US"}
internal class ReplyModelPort {var voiceReplyPolicy=VoiceResponsePolicy.RESPONSIVE}
internal class TuningConfigurationPort {fun apply(value:VoiceTuningPreferences,language:String){}}
@Composable
internal fun ExtractedTranslationWiring(
 session:VoiceSession, backendOwner:VoiceBackendViewModel, runtimeOwner:ExtractedRuntimeDetectorOwner,
 backend:VoiceBackendSettings, audio:VoicePluginAudio, systemAudio:SystemAudioPort, kokoroOutput:KokoroOutput?,
 model:ReplyModelPort, tuningConfiguration:TuningConfigurationPort, tuning:VoiceTuningPreferences,
 settingsScope:CoroutineScope, preferencesApplied:Boolean, translationMode:Boolean, translationPair:VoiceTranslationPair,
 translationModel:LocalVoiceModelState?, rate:Float, incrementalSpeech:Boolean,
 language:String, recognitionBackend:VoiceRecognitionBackend, synthesis:VoiceSynthesisPreferences
) {
 var translationFactories by remember {mutableStateOf<Map<String,VoicePhraseEngineFactory>>(emptyMap())}
 var translationOutput by remember {mutableStateOf<VoiceTranslationPlayback?>(null)}
 fun activeSpeechProfile()=backend.synthesisProfile(synthesis)
'''+profiles+helpers+effects+'\n}\n'
p=OUT/'ExtractedTranslationWiring.kt';p.write_text(source)
manifest['extracted_wiring_sha256']=hashlib.sha256(source.encode()).hexdigest()
cp=[SDK,CORE/'classes',BASE/'tests.jar',DETECTOR/'tests.jar']+list(DEPS.glob('*.jar'))+list(UI.glob('*.jar'))
friends=','.join(map(str,[CORE/'classes',BASE/'tests.jar',DETECTOR/'tests.jar']))
cmd=[JAVA,'-Xmx384m','-cp',str(DEPS/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(PLUGIN),'-Xfriend-paths='+friends,'-classpath',':'.join(map(str,cp)),'-d',str(OUT/'compile.jar'),str(p)]
(OUT/'source-manifest.json').write_text(json.dumps({'root_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'source_sha256':manifest,'core_manifest':str(CORE/'manifest.json'),'detector_manifest':str(DETECTOR/'manifest.json'),'settings_manifest':str(BASE/'source-manifest.json')},indent=2)+'\n')
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'compile.log').write_text(r.stdout+r.stderr);print('wiring_compile_exit',r.returncode);print(r.stdout+r.stderr)
(OUT/'qualification.json').write_text(json.dumps({'compile_exit':r.returncode,'scope':'Exact current VoiceChatScreen output/capture helpers, output-factory effect, immutable factory preparation, prepareDraft SideEffect/coroutine/commit callback and disposal block compile against actual full compiled VoiceSession/Pair/Policy/VoicePluginAudio, actual full compiled VoiceBackendViewModel API, and actual MediaPipe detector API. Exact VoiceRuntimeViewModel detector allocation/detect/close statements are compiled. Explicit surrounding model/system-audio/tuning state ports; no whole-screen compilation, Compose/device execution, native PCM or Android permission qualification.','source_manifest':'source-manifest.json'},indent=2)+'\n')
sys.exit(r.returncode)

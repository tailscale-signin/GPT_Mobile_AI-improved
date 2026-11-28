from pathlib import Path
import hashlib,json,subprocess,sys
ROOT=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/workspace/scratch/49479c165f4e/repo')
OUT=Path(__file__).parent/'ui';OUT.mkdir(exist_ok=True)
S=OUT/'sources';S.mkdir(exist_ok=True)
DEPS=Path('/workspace/scratch/4ae19a99b59d/jvm-deps')
CACHE=Path('/home/agent/.gradle/caches/modules-2/files-2.1')
UI_DEPS=Path('/workspace/scratch/49479c165f4e/voice-presentation-host-tests/deps')
SDK=Path('/workspace/scratch/0f34c62a99bc/android-sdk/platforms/android-37.0/android.jar')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
PLUGIN=next((CACHE/'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.4.20').rglob('*.jar'))
P='app/src/main/kotlin/dev/chungjungsoo/gptmobile/'
prior_manifest = json.loads((OUT/'source-manifest.json').read_text()) if '--refresh-binding-only' in sys.argv else None
manifest={};inputs=[]
def read(p):
 b=(ROOT/p).read_bytes();manifest[p]=hashlib.sha256(b).hexdigest();return b.decode()
def add(name,s):
 p=S/name;p.write_text(s);inputs.append(p)
for file in ['VoiceLanguageSettings.kt','VoiceConversationContent.kt','VoiceMicrophoneButton.kt']:
 add(file,read(P+'presentation/ui/voice/'+file))
state=read(P+'data/voice/VoiceSession.kt')
add('VoiceState.kt','package dev.chungjungsoo.gptmobile.data.voice\n'+state[state.index('enum class VoicePhase'):state.index('/** A state callback')])
chat=read(P+'presentation/ui/voice/VoiceChatScreen.kt')
start=chat.index('@Composable\ninternal fun VoiceChoice(');end=chat.index('@Composable\ninternal fun VoiceSettingSwitch(',start)
add('VoiceChoice.kt','''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
'''+chat[start:end])
models=read(P+'data/voice/LocalVoiceModelStore.kt')
add('LocalModelState.kt','package dev.chungjungsoo.gptmobile.data.voice\n'+models[models.index('enum class LocalVoiceModelStatus'):models.index('/** Model inspection')])
add('VoiceUiPorts.kt','''package dev.chungjungsoo.gptmobile.data.voice
internal class VoiceCaptionCursor
internal class VoiceInputLevel
internal enum class VoiceInterruptionStatus { OFF, STARTING, READY, DETECTED, UNAVAILABLE }
enum class VoiceTransferStage {CHECKING}
enum class VoiceAssetFailureKind {HOST_COMPILE_PORT}
class LocalVoiceModelStore {companion object{const val TOTAL_BYTES=0L}}
''')
add('PlatformPort.kt','package dev.chungjungsoo.gptmobile.data.database.entity\ndata class PlatformV2(val uid:String="",val name:String="",val model:String="")\n')
add('UiWidgetPorts.kt','''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import dev.chungjungsoo.gptmobile.data.voice.*
@Composable internal fun VoiceLiveCaptions(state:VoiceState,compact:Boolean,modifier:Modifier,cursor:StateFlow<VoiceCaptionCursor>?=null,replyOnly:Boolean=false){}
@Composable internal fun VoiceWave(state:VoiceState,modifier:Modifier,output:StateFlow<Float?>?,input:StateFlow<VoiceInputLevel>?){}
@Composable internal fun LocalVoiceSetupCard(state:LocalVoiceModelState,install:()->Unit,cancel:()->Unit,remove:()->Unit){}
@Composable internal fun VoiceTransferProgress(stage:VoiceTransferStage?,downloaded:Long,total:Long){}
internal fun voiceRepairLabel(action:VoiceRecoveryAction)=action.name
@Composable internal fun voiceMotionEnabled()=false
''')
add('DropdownPort.kt','''package dev.chungjungsoo.gptmobile.presentation.common
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Modifier
@Composable internal fun FadingDropdownMenu(expanded:Boolean,onDismissRequest:()->Unit,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){}
''')
test=read('app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/voice/VoiceConversationInstrumentedTest.kt')
names=['microphoneAndLanguageControlsFollowTheTurnAndPickerState','translationLanguageChoicesKeepEachVoiceAndOfferSourceRecoveryAndPackInstallation']
methods=[]
for n in names:
 a=test.index('    @Test fun '+n);b=test.index('\n    @Test',a+1);methods.append(test[a:b])
helpers=test[test.index('    private fun state(session: VoiceState)'):test.rfind('}')]
add('SelectedUiInstrumentation.kt','''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class SelectedUiInstrumentation {
@get:Rule val compose=createComposeRule()
'''+ '\n'.join(methods)+helpers+'\n}\n')
cp=[SDK,OUT.parent/'tests.jar']+list(DEPS.glob('*.jar'))+list(UI_DEPS.glob('*.jar'))
classpath=':'.join(map(str,cp))
cmd=[JAVA,'-Xmx384m','-cp',str(DEPS/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(PLUGIN),'-Xfriend-paths='+str(OUT.parent/'tests.jar'),'-classpath',classpath,'-d',str(OUT/'compile.jar')]+list(map(str,inputs))
(OUT/'source-manifest.json').write_text(json.dumps({'root_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'source_sha256':manifest,'generated_inputs_sha256':{str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}},indent=2)+'\n')
if prior_manifest is not None:
 current_manifest=json.loads((OUT/'source-manifest.json').read_text())
 assert prior_manifest['generated_inputs_sha256']==current_manifest['generated_inputs_sha256'], 'compiled declarations changed; a fresh compile is required'
 changed=[p for p,h in current_manifest['source_sha256'].items() if prior_manifest['source_sha256'].get(p)!=h]
 assert changed==['app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/voice/VoiceChatScreen.kt'], changed
 (OUT/'source-readback.json').write_text(json.dumps({'kind':'same_compilation_inputs_source_binding_refresh','root_head':current_manifest['root_head'],'compile_rerun':False,'prior_manifest':'before-local-output/source-manifest.json','changed_containing_files':changed,'compiled_input_count':len(current_manifest['generated_inputs_sha256']),'all_compiled_inputs_byte_identical':True,'compiled_artifact_sha256':hashlib.sha256((OUT/'compile.jar').read_bytes()).hexdigest(),'explanation':'VoiceChatScreen changed outside the extracted VoiceChoice declaration. The exact full UI components, instrumentation methods/fixtures and every generated compilation input remain byte-identical to the successful compile. The original receipt and source manifest are retained separately.'},indent=2)+'\n')
 print('ui_binding_refresh: all compiled inputs byte-identical; no compiler invoked')
 sys.exit(0)
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'compile.log').write_text(r.stdout+r.stderr);print('ui_compile_exit',r.returncode);print(r.stdout+r.stderr)
(OUT/'qualification.json').write_text(json.dumps({'compile_exit':r.returncode,'instrumentation_methods':names,'device_executed':False,'scope':'Actual full VoiceLanguageSettings, VoiceConversationContent and VoiceMicrophoneButton compile against cached pinned Android37/Compose APIs. Exact VoiceChoice, state/model DTO and2 instrumentation method+fixture declarations extracted. Unrelated caption/wave/install widgets and popup motion are explicit compile-only ports. Domain/boundary types imported from settings host artifact. This does not execute Compose, native speech, Android persistence or a device.'},indent=2)+'\n')
sys.exit(r.returncode)

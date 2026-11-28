from pathlib import Path
import hashlib,json,re,subprocess,sys
ROOT=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/workspace/scratch/49479c165f4e/work-translation-settings')
PAIR=Path(sys.argv[2]) if len(sys.argv)>2 else Path('/workspace/scratch/49479c165f4e/work-translation-turn')
OUT=Path(__file__).parent
S=OUT/'sources';S.mkdir(exist_ok=True)
DEPS=Path('/workspace/scratch/4ae19a99b59d/jvm-deps')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
prefix='app/src/main/kotlin/dev/chungjungsoo/gptmobile/'
inputs=[];manifest={}
def source(path, root=ROOT):
 text=(root/path).read_text();manifest[str(root/path)]=hashlib.sha256(text.encode()).hexdigest();return text
def add(name,text):
 p=S/name;p.write_text(text);inputs.append(p)
for folder,names in [('data/voice',['MediaPipeSpeechCatalog','MediaPipeSpeechVoices','SpeechLanguages','VoiceCpuSchedulingPolicy','VoicePreferenceWriter','VoiceSpeechBackend','VoiceRecognitionBackend','KokoroVoice','MediaPipeKokoroBucket']),('presentation/ui/voice',['VoiceBackendViewModel','VoiceBackendPreferenceStore','VoicePreferenceStore'])]:
 for name in names:add(name+'.kt',source(prefix+folder+'/'+name+'.kt'))
add('VoiceTranslationPair.kt',source(prefix+'data/voice/VoiceTranslationPair.kt',PAIR))
prefs=source(prefix+'presentation/ui/voice/VoicePreferencesViewModel.kt')
start=prefs.index('internal const val STANDARD_SPEECH_RATE')
end=prefs.index('internal data class VoiceModelStores')
add('VoicePreferences.kt','package dev.chungjungsoo.gptmobile.presentation.ui.voice\nimport dev.chungjungsoo.gptmobile.data.voice.*\n'+prefs[start:end])
# The tested store uses real writer/read logic. Android storage is an explicit in-memory adapter.
add('AndroidStoragePort.kt',r'''package android.content
open class Context {
 companion object { const val MODE_PRIVATE=0 }
 val contentResolver=ContentResolver()
 private val stores=mutableMapOf<String,SharedPreferences>()
 fun getSharedPreferences(name:String,mode:Int)=stores.getOrPut(name){SharedPreferences()}
}
class ContentResolver { fun openInputStream(uri:android.net.Uri):java.io.InputStream? = error("unused content import") }
class SharedPreferences {
 private val values=mutableMapOf<String,Any>()
 val all:Map<String,Any> get()=values.toMap()
 fun contains(key:String)=key in values
 fun edit()=Editor()
 inner class Editor {
  private val writes=mutableMapOf<String,Any?>();private var clear=false
  fun putString(k:String,v:String?)=apply{writes[k]=v}
  fun putStringSet(k:String,v:Set<String>?)=apply{writes[k]=v?.toSet()}
  fun putBoolean(k:String,v:Boolean)=apply{writes[k]=v}
  fun putInt(k:String,v:Int)=apply{writes[k]=v}
  fun putFloat(k:String,v:Float)=apply{writes[k]=v}
  fun clear()=apply{clear=true}
  fun commit():Boolean {if(clear)values.clear();writes.forEach{(k,v)->if(v==null)values.remove(k)else values[k]=v};return true}
 }
}
''')
add('AndroidApplicationPort.kt','package android.app\nclass Application:android.content.Context()\n')
add('AndroidUriPort.kt','package android.net\nclass Uri(val scheme:String)\n')
add('ApplicationProviderPort.kt','package androidx.test.core.app\nobject ApplicationProvider {private val app=android.app.Application(); @Suppress("UNCHECKED_CAST") fun <T> getApplicationContext():T=app as T}\n')
add('LifecyclePort.kt','''package androidx.lifecycle
import kotlinx.coroutines.*
open class ViewModel { internal val hostScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate) }
val ViewModel.viewModelScope:CoroutineScope get()=hostScope
class ViewModelStore {private val all=mutableListOf<ViewModel>();fun put(key:String,value:ViewModel){all+=value};fun clear(){all.forEach{it.hostScope.cancel()};all.clear()}}
''')
add('InjectPort.kt','package javax.inject\nannotation class Inject\nannotation class Singleton\n')
add('HiltPort.kt','package dagger.hilt.android.lifecycle\nannotation class HiltViewModel\n')
add('HiltContextPort.kt','package dagger.hilt.android.qualifiers\nannotation class ApplicationContext\n')
add('ProviderPort.kt','package dev.chungjungsoo.gptmobile.data.database.entity\nimport dev.chungjungsoo.gptmobile.data.model.ClientType\ndata class ProviderConnection(val uid:String="",val name:String="",val apiUrl:String="",val compatibleType:ClientType=ClientType.LLAMA)\n')
add('ClientPort.kt','package dev.chungjungsoo.gptmobile.data.model\nenum class ClientType{LLAMA,OLLAMA,OPENAI}\n')
add('SettingsPort.kt','''package dev.chungjungsoo.gptmobile.data.repository
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import kotlinx.coroutines.flow.*
class SettingRepository {fun observeProviderConnections():Flow<List<ProviderConnection>> = MutableStateFlow(emptyList());suspend fun getProviderConnection(uid:String):ProviderConnection? = error("unused gateway");suspend fun getProviderCredentials(uid:String):String?=error("unused gateway")}
''')
add('TuningPort.kt','''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import dev.chungjungsoo.gptmobile.data.voice.*
internal data class VoiceTuningPreferences(val listeningStyle:VoiceListeningStyle=VoiceListeningStyle.NATURAL,val captureBatchMs:Int=40,val handsFreeInterruption:Boolean=false,val spokenAcknowledgments:Boolean=true,val pronunciation:Map<String,String> = emptyMap(),val localizedPronunciations:Map<String,Map<String,String>> = emptyMap(),val pronunciationLocales:Map<String,String> = emptyMap(),val captionOffsetMs:Int=0)
internal fun readVoiceTuningPreferences(saved:Map<String,*>)=VoiceTuningPreferences()
''')
add('JsonPort.kt','package org.json\nclass JSONObject(value:Any?) {override fun toString()="{}"}\n')
add('VoiceBoundaryPorts.kt',r'''package dev.chungjungsoo.gptmobile.data.voice
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
internal const val VOICE_PCM_SAMPLE_RATE=24000
internal data class LocalVoiceAsset(val fileName:String,val bytes:Long,val sha256:String,val urls:List<String>)
internal enum class KokoroModelVariant {INT8,ORIGINAL_FP32}
internal enum class VoicePauseDuration {NATURAL,MINIMAL}
internal enum class VoicePerformanceMode {AUTO,FAST}
internal enum class VoiceListeningStyle {NATURAL}
internal object KokoroModelPack {const val GB_LEXICON="gb";const val US_LEXICON="us"}
internal enum class VoiceResponsePolicy { TRANSLATE; companion object {fun forLanguage(tag:String,translate:Boolean)=TRANSLATE} }
internal enum class LocalVoiceModelStatus {CHECKING,NOT_INSTALLED,DOWNLOADING,READY,FAILED}
internal data class LocalVoiceModelState(val status:LocalVoiceModelStatus=LocalVoiceModelStatus.CHECKING,val downloadedBytes:Long=0,val totalBytes:Long=0,val message:String?=null)
internal class LocalVoiceModelStore {val state=MutableStateFlow(LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED));suspend fun install(waitForOwner:Boolean=false,sourceOverride:(()->HttpsVoiceAssetSource)?=null):Unit=error("unused NPU dependency");fun cancelInstall():Unit=error("unused NPU dependency")}
internal class MediaPipeSpeechModelStore(context:Context,scope:CoroutineScope) {
 val acceleratorEnvironment="host-recording-port"
 val stores=MediaPipeSpeechEngine.entries.associateWith{LocalVoiceModelStore()}
 val state=MutableStateFlow(stores.mapValues{it.value.state.value})
 val installs=mutableListOf<MediaPipeSpeechEngine>();val cancelled=mutableListOf<MediaPipeSpeechEngine>();val refreshes=mutableListOf<MediaPipeSpeechEngine?>()
 fun localStore(engine:MediaPipeSpeechEngine)=stores.getValue(engine)
 fun isReady(engine:MediaPipeSpeechEngine)=localStore(engine).state.value.status==LocalVoiceModelStatus.READY
 suspend fun refresh(engine:MediaPipeSpeechEngine?=null,forceVerification:Boolean=true){refreshes+=engine}
 suspend fun install(engine:MediaPipeSpeechEngine){installs+=engine}
 fun cancelInstall(engine:MediaPipeSpeechEngine){cancelled+=engine}
 suspend fun remove(engine:MediaPipeSpeechEngine):Unit=error("unused removal")
 suspend fun importCompiledVocoder(open:()->java.io.InputStream):Unit=error("unused import")
}
internal interface VoicePhraseEngineFactory {val identity:String; val residentIdentity:String get()=identity;val selectedBackend:VoiceSpeechBackend;fun isReady():Boolean=true}
internal class MediaPipeVoicePhraseEngineFactory(
 val models:MediaPipeSpeechModelStore,val engine:MediaPipeSpeechEngine,override val selectedBackend:VoiceSpeechBackend,val rate:Float,val voiceId:String,
 val kokoroBucket:MediaPipeKokoroBucket=MediaPipeKokoroBucket.AUTO,val incrementalInput:Boolean=true,
 val onPlaybackDelegate:((VoiceSpeechBackend)->Unit)?=null,val onValidatedSynthesis:((String,VoiceSpeechBackend)->Unit)?=null
):VoicePhraseEngineFactory {override val identity="recorded:${engine.name}:$voiceId";override fun isReady()=models.isReady(engine)}
internal open class UnavailableVoicePhraseEngineFactory(message:String):VoicePhraseEngineFactory {override val identity="unavailable:$message";override val selectedBackend=VoiceSpeechBackend.CPU}
internal class GatewayVoicePhraseEngineFactory(url:String,credentials:String?,report:(String)->Unit):UnavailableVoicePhraseEngineFactory("gateway")
internal class QualcommVoicePhraseEngineFactory(context:Context,backend:VoiceSpeechBackend,report:(String)->Unit,calibrateAll:Boolean=false,voice:KokoroVoice,speechRate:Float,modelVariant:KokoroModelVariant):UnavailableVoicePhraseEngineFactory("native") {companion object{fun requestRetest(context:Context,backend:VoiceSpeechBackend):Unit=error("unused native retest")}}
internal class PocketCompiledVoicePhraseEngineFactory(context:Context,store:PocketCompiledSpeechModelStore,backend:VoiceSpeechBackend,rate:Float,voice:String,captions:PocketCaptionModelStore):UnavailableVoicePhraseEngineFactory("compiled")
internal class PocketCompiledSpeechModelStore(context:Context) {val state=MutableStateFlow(LocalVoiceModelState());suspend fun refresh()=Unit;suspend fun install():Unit=error("unused compiled install");suspend fun remove():Unit=error("unused compiled removal");fun cancelInstall()=Unit;fun isReady()=false}
internal class PocketCaptionModelStore(context:Context) {val state=MutableStateFlow(LocalVoiceModelState());suspend fun refresh()=Unit;suspend fun install():Unit=error("unused captions install");suspend fun remove():Unit=error("unused captions removal");fun cancelInstall()=Unit}
internal object PocketCompiledSpeechCatalog {val VOICES=listOf("alba")}
internal class HttpsVoiceAssetSource {fun cancel()=Unit}
internal object VoicePluginInstall {fun cancel(context:Context)=Unit}
internal class SpeechVoiceEvidence(context:Context,environment:String) {fun has(model:MediaPipeSpeechModel,backend:VoiceSpeechBackend,id:String)=false;fun record(model:MediaPipeSpeechModel,backend:VoiceSpeechBackend,id:String)=Unit}
internal fun isSm8750SpeechDevice()=false
''')
# Run the six production test bodies unchanged; only test environment imports are supplied.
testpath='app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/voice/VoicePreferenceStoreTest.kt'
tests=source(testpath)
names=['translationRestoresTheTargetIndependentlyOfTheMicrophoneLanguage','translationPairDefaultsStayEnglishAndFrenchIndependentlyOfRecognitionAndReply','unsupportedLegacyTranslationRequiresSetupWithoutChangingItsOrdinaryReplyLanguage','corruptTranslationFieldsCannotSelectAnotherLanguageVoiceOrAnUnrelatedSource','translationPairAndEachVoiceSurvivePreferenceWritesWithoutChangingNormalSpeech','legacyStreamingTargetMigratesOnceAndCannotReplaceAnExplicitSavedPair']
selected=[]
for name in names:
 start=tests.index('    @Test fun '+name)
 end=tests.find('\n    @Test',start+1)
 selected.append(tests[start:end])
add('SelectedPreferenceTests.kt','''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.voice.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedPreferenceTests {
'''+ '\n'.join(selected)+'\n}\n')
add('FactoryBoundaryTests.kt',r'''package dev.chungjungsoo.gptmobile.presentation.ui.voice
import android.content.Context
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class FactoryBoundaryTests {
 @Test fun bothDirectionsUseTheirOwnMultilingualVoiceWithoutChangingNormalSettings()=runTest {
  val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
  val owners=ViewModelStore();val scope=CoroutineScope(SupervisorJob()+dispatcher)
  try {
   val context=Context();val saved=context.getSharedPreferences("backend",0)
   saved.edit().putString("backend",VoiceSpeechBackend.POCKET_CPU.name).putStringSet("streaming_voices",setOf("POCKET=marius")).commit()
   val prefs=VoiceBackendPreferenceStore({saved},scope);val models=MediaPipeSpeechModelStore(context,scope)
   models.localStore(MediaPipeSpeechEngine.POCKET_MULTILINGUAL).state.value=LocalVoiceModelState(LocalVoiceModelStatus.READY)
   val model=VoiceBackendViewModel(context,SettingRepository(),prefs,dispatcher,createStreamingStore={_,_->models});owners.put("model",model);runCurrent()
   val before=model.state.value.factory;val stored=saved.all
   val english=model.translationFactory("en-GB","azelma") as MediaPipeVoicePhraseEngineFactory
   val french=model.translationFactory("fr-CA","estelle") as MediaPipeVoicePhraseEngineFactory
   assertEquals("azelma",english.voiceId);assertEquals("estelle",french.voiceId)
   for (factory in listOf(english,french)) {assertEquals(MediaPipeSpeechEngine.POCKET_MULTILINGUAL,factory.engine);assertEquals(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU,factory.selectedBackend);assertNull(factory.onPlaybackDelegate)}
   assertTrue(runCatching{model.translationFactory("fr-FR","azelma")}.exceptionOrNull() is IllegalArgumentException)
   assertSame(before,model.state.value.factory);assertEquals(VoiceSpeechBackend.POCKET_CPU,model.state.value.backend);assertEquals("marius",model.state.value.streamingVoiceId);assertEquals(stored,saved.all);assertTrue(models.installs.isEmpty())
  } finally {owners.clear();scope.cancel();Dispatchers.resetMain()}
 }
 @Test fun missingMultilingualPackDoesNotDownloadOrSelectTheInstalledEnglishPack()=runTest {
  val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
  val owners=ViewModelStore();val scope=CoroutineScope(SupervisorJob()+dispatcher)
  try {
   val context=Context();val saved=context.getSharedPreferences("backend",0);val prefs=VoiceBackendPreferenceStore({saved},scope);val models=MediaPipeSpeechModelStore(context,scope)
   models.localStore(MediaPipeSpeechEngine.POCKET).state.value=LocalVoiceModelState(LocalVoiceModelStatus.READY)
   val model=VoiceBackendViewModel(context,SettingRepository(),prefs,dispatcher,createStreamingStore={_,_->models});owners.put("model",model);runCurrent()
   val before=model.state.value.factory
   val failure=runCatching{model.translationFactory("fr-FR","estelle")}.exceptionOrNull()
   assertTrue(failure is java.io.IOException);assertTrue(failure!!.message!!.contains("Install the Pocket Multilingual voice pack"));model.refreshTranslationModel();runCurrent();assertTrue(models.installs.isEmpty())
   model.installTranslationModel();runCurrent();assertEquals(listOf(MediaPipeSpeechEngine.POCKET_MULTILINGUAL),models.installs)
   model.cancelTranslationModel();assertEquals(listOf(MediaPipeSpeechEngine.POCKET_MULTILINGUAL),models.cancelled)
   assertSame(before,model.state.value.factory);assertEquals(VoiceSpeechBackend.POCKET_CPU,model.state.value.backend)
  } finally {owners.clear();scope.cancel();Dispatchers.resetMain()}
 }
 @Test fun activeMultilingualDownloadCannotAdmitAReplyOrTriggerAnotherTransfer()=runTest {
  val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
  val owners=ViewModelStore();val scope=CoroutineScope(SupervisorJob()+dispatcher)
  try {
   val context=Context();val prefs=VoiceBackendPreferenceStore({context.getSharedPreferences("backend",0)},scope);val models=MediaPipeSpeechModelStore(context,scope)
   models.localStore(MediaPipeSpeechEngine.POCKET_MULTILINGUAL).state.value=LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING)
   val model=VoiceBackendViewModel(context,SettingRepository(),prefs,dispatcher,createStreamingStore={_,_->models});owners.put("model",model);runCurrent()
   assertTrue(runCatching{model.translationFactory("en-US","azelma")}.exceptionOrNull() is java.io.IOException)
   assertFalse(MediaPipeSpeechEngine.POCKET_MULTILINGUAL in models.refreshes);assertTrue(models.installs.isEmpty())
  } finally {owners.clear();scope.cancel();Dispatchers.resetMain()}
 }
}
''')
(OUT/'source-manifest.json').write_text(json.dumps({'repository_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'source_sha256':manifest,'generated_inputs_sha256':{str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}},indent=2)+'\n')
cp=':'.join(map(str,DEPS.glob('*.jar')))
cmd=[JAVA,'-Xmx512m','-cp',str(DEPS/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-classpath',cp,'-d',str(OUT/'tests.jar')]+list(map(str,inputs))
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'compile.log').write_text(r.stdout+r.stderr);print('compile_exit',r.returncode)
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
r=subprocess.run([JAVA,'-Xmx256m','-cp',str(OUT/'tests.jar')+':'+cp,'org.junit.runner.JUnitCore','dev.chungjungsoo.gptmobile.presentation.ui.voice.SelectedPreferenceTests','dev.chungjungsoo.gptmobile.presentation.ui.voice.FactoryBoundaryTests'],capture_output=True,text=True)
(OUT/'junit.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr)
(OUT/'qualification.json').write_text(json.dumps({'compile_exit':0,'test_exit':r.returncode,'unique_cases':9,'scope':'Exact VoicePreferenceStore/VoicePreferenceWriter/VoiceBackendPreferenceStore and full VoiceBackendViewModel. Exact VoicePreferences data declaration, catalog and runtime pair. Six existing production regression test bodies plus three boundary behavior cases. Explicit Android SharedPreferences/lifecycle/storage/native-factory recording ports; no actual SDK model open, PCM, captions, Compose device execution or Android build claim. The factory recording port verifies target/voice/admission and global-state invariants, not native residency identity.','source_manifest':'source-manifest.json'},indent=2)+'\n')
sys.exit(r.returncode)

from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import re
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--repo', default='/workspace/scratch/49479c165f4e/work-translation-turn')
parser.add_argument('--ref')
parser.add_argument('--out', default='current')
args = parser.parse_args()
repo = Path(args.repo)
root = Path(__file__).parent
out = root / args.out
out.mkdir(parents=True, exist_ok=True)
sources = out / 'sources'
sources.mkdir(exist_ok=True)
cache = Path('/home/agent/.gradle/caches/modules-2/files-2.1')
def jar(group, artifact, version):
    return next((cache / group / artifact / version).rglob(artifact + '-' + version + '.jar'))
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0')
annotations = jar('org.jetbrains', 'annotations', '13.0')
cp = [stdlib, coroutines, annotations,
      jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'),
      jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.11.0'),
      jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3'),
      Path('/workspace/scratch/4ae19a99b59d/jvm-deps/kotlinx-coroutines-test-jvm-1.11.0.jar')]
compiler_cp = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'), stdlib, coroutines, annotations,
               jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.20'), jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10')]
prefix = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/'
main_names = ['VoiceSession', 'VoiceSpeechBuffer', 'VoiceSpeechText', 'ReasoningTagScanner', 'VoiceSpokenLead',
              'VoicePhraseBoundaries', 'VoicePronunciationOverrides', 'LocalizedVoiceReadings', 'VoiceCaptionTiming',
              'VoicePresentationSignals', 'VoicePlaybackFailure', 'VoiceCaptureFailure', 'AndroidRecognitionFailure',
              'VoiceTurnAdmission', 'VoiceSynthesisFeedback', 'VoiceStreamDeliveryCadence', 'KokoroPcmBatches',
              'VoiceCpuSchedulingPolicy', 'VoicePerformanceHistory', 'VoiceReplyFailureDiagnostics',
              'VoiceAdmissionFailureDiagnostics', 'VoiceSpeechJoinTracker', 'VoicePluginAudio',
              'VoiceRecognitionInput', 'VoiceRecognitionBackend', 'KokoroVoice', 'VoiceListeningPolicy']
main_paths = [prefix + 'data/voice/' + name + '.kt' for name in main_names]
main_paths += [prefix + 'presentation/ui/voice/VoiceCaption.kt']
test_names = ['VoiceSessionTest', 'VoiceContinuousListeningTest', 'VoiceSessionAdmissionRecoveryTest', 'VoiceConversationListeningTest', 'VoiceDraftPreparationTest', 'VoiceTranslationPairTest', 'VoiceTranslationPlaybackPolicyTest', 'VoiceMessagePlaybackTest', 'VoiceTranslationSourceTest']
test_paths = ['app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/' + name + '.kt' for name in test_names]
main_paths += [prefix + 'data/voice/' + name + '.kt' for name in ['VoiceCaptureFeedback', 'VoiceTranslationPair', 'VoiceResponsePolicy', 'VoiceTranslationSource', 'SpeechLanguages', 'MediaPipeSpeechVoices', 'MediaPipeSpeechCatalog', 'VoiceMessagePlayback', 'VoiceTranslationPlaybackPolicy']]
stubs = {
    'ProfileFailurePort.kt': '''package dev.chungjungsoo.gptmobile.data.agent
enum class RemoteRequestAcceptance { YES, NO, UNKNOWN }
data class ProfileFailureRecord(val category: String, val retryAllowed: Boolean, val dispatched: Boolean? = null, val remoteAcceptance: RemoteRequestAcceptance = RemoteRequestAcceptance.UNKNOWN, val phase: String = "", val invocationId: String? = null, val requestReplayAllowed: Boolean? = null)
''',
    'PersistencePorts.kt': '''package dev.chungjungsoo.gptmobile.data.database.entity
data class AgentRun(val runId: String, val chatId: Int, val userMessageId: Int, val assistantMessageId: Int, val profileUid: String)
data class MessageV2(val id: Int, val chatId: Int, val currentRunId: String?)
''',
    'ReportingPorts.kt': '''package dev.chungjungsoo.gptmobile.data.diagnostics
object IssueReportCodes { fun known(code: String?): String? = code }
object IssueReportContext { fun identifierHash(namespace: String, value: String?): String = "host-reporting-port" }
''',
    'VoiceDiagnosticPorts.kt': '''package dev.chungjungsoo.gptmobile.data.voice
class VoiceDiagnosticTrace(private val now: () -> Long = { 0 }) {
    val performance = VoicePerformanceHistory()
    fun advance(generation: Long, reset: Boolean = false) = Probe()
    fun beginReply(generation: Long, fromCapture: Boolean = false) = Probe()
    fun incidentFields(): Map<String, Any?> = emptyMap()
    fun retain() = Unit
    fun close() = Unit
    class Probe {
        fun reportFailure(code: String, cause: Throwable? = null, fields: Map<String, Any?> = emptyMap()) = Unit
        fun phase(phase: VoicePhase) = Unit
        fun access(permission: Boolean, foreground: Boolean, muted: Boolean) = Unit
        fun permission(granted: Boolean) = Unit
        fun requestAccepted() = Unit
        fun speechEnded() = Unit
        fun recognized(length: Int) = Unit
        fun finalTranscript() = Unit
        fun openingAudioStarted(lookup: Boolean) = Unit
        fun responseText() = Unit
        fun publishableSpeech() = Unit
        fun queued() = Unit
        fun captionAlignment(alignment: String) = Unit
        fun playbackFailure(failure: VoicePlaybackFailure) = Unit
        fun playbackFallback(failure: VoicePlaybackFailure, outcome: String) = Unit
        fun replyFailure(fields: Map<String, Any?>) = Unit
        fun muted(muted: Boolean) = Unit
    }
}
class VoiceSpeechExecutionEvidence
enum class VoiceWordCueProvenance { NATIVE_DURATION, ESTIMATED, ASR_ALIGNMENT }
internal data class VoiceThinkingCue(val text: String, val lookup: Boolean = false)
internal typealias SpokenRangeObserver = (Int, String, IntRange) -> Unit
internal interface VoicePhraseEngineFactory
internal data class LocalVoiceAsset(val name: String, val bytes: Long, val sha256: String, val urls: List<String>)
internal object KokoroModelPack { const val US_LEXICON = "lexicon-us-en.txt"; const val GB_LEXICON = "lexicon-gb-en.txt" }
internal enum class VoiceInterruptionStatus { OFF, STARTING, READY, DETECTED, UNAVAILABLE }
internal interface VoiceInterruptionInput {
    val interruptionState: kotlinx.coroutines.flow.StateFlow<VoiceInterruptionStatus>
    fun startInterruptionMonitoring(outputLevel: () -> Float?, interrupted: () -> Unit)
    fun stopInterruptionMonitoring()
    fun afterInterruptionStopped(released: () -> Unit) { stopInterruptionMonitoring(); released() }
}
internal abstract class MoonshineVoiceRecognizer : VoiceRecognitionInput {
    var listeningStyle = VoiceListeningStyle.NATURAL
    var captureBatchMs = 40
    fun finishListening() = Unit
}
'''
}
manifest = {
    'repository_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'source_ref': args.ref or 'worktree',
    'scope': 'Full exact VoiceSession, VoiceTranslationPair, VoiceResponsePolicy, VoiceMessagePlayback, VoiceTranslationPlaybackPolicy and supporting text/audio policies plus complete selected test classes on the host JVM. Kotlin serialization plugin exercises persisted pair and policy round trips; fake audio drives real lifecycle transitions. No language detector/model or Pocket runtime is executed. No Compose composition, Android permission dialog, SoundPool/device audio, microphone hardware, native recognizer or complete Android build qualification.',
    'ports': 'Unrelated reporting/diagnostic sinks, Room receiver DTOs, ProfileFailureRecord DTO, speech execution evidence marker, thinking-cue/caption-provenance DTOs, native-recognizer settings interface, monitor interface and engine factory marker are explicit host ports and a LocalVoiceAsset download descriptor. No VoiceSession, translation pair, language/voice catalog or policy implementation is extracted or replaced. Probe includes the fc0 fromCapture argument.',
    'compiler_heap_mib': 384,
    'compilation_layout': 'Full production sources and full test classes compiled separately with Kotlin friend paths; no source extraction.',
    'sources_sha256': {}, 'ports_sha256': {}
}
main_inputs = []
test_inputs = []
for index, path in enumerate(main_paths + test_paths):
    data = subprocess.check_output(['git', 'show', args.ref + ':' + path], cwd=repo) if args.ref else (repo / path).read_bytes()
    target = sources / (str(index) + '-' + Path(path).name)
    target.write_bytes(data)
    manifest['sources_sha256'][path] = hashlib.sha256(data).hexdigest()
    (main_inputs if index < len(main_paths) else test_inputs).append(target)
for name, data in stubs.items():
    target = sources / name
    target.write_text(data)
    manifest['ports_sha256'][name] = hashlib.sha256(data.encode()).hexdigest()
    main_inputs.append(target)
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
java = '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
classes = out / 'classes'
test_classes = out / 'test-classes'
for directory in [classes, test_classes]:
    if directory.exists(): shutil.rmtree(directory)
    directory.mkdir(exist_ok=True)
for stale in ['qualification.json', 'junit.log']:
    (out / stale).unlink(missing_ok=True)
compiler = [java, '-Xmx384m', '-cp', ':'.join(map(str, compiler_cp)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-Xplugin=' + str(jar('org.jetbrains.kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.4.20'))]
logs = []
for phase, inputs, output, classpath, extra in [
    ('production', main_inputs, classes, cp, []),
    ('tests', test_inputs, test_classes, [classes, *cp], ['-Xfriend-paths=' + str(classes)])
]:
    compile_cmd = [*compiler, '-module-name', 'voice_translation_' + phase, '-classpath', ':'.join(map(str, classpath)), *extra, '-d', str(output), *map(str, inputs)]
    r = subprocess.run(compile_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / ('compile-' + phase + '.log')).write_text(r.stdout)
    logs.append(phase + ': exit=' + str(r.returncode) + '\n' + r.stdout)
    (out / 'compile.log').write_text('\n'.join(logs))
    print(phase + '_compile_exit', r.returncode, flush=True)
    print(r.stdout[-18000:], flush=True)
    if r.returncode:
        (out / 'qualification.json').write_text(json.dumps({'compile_exit': r.returncode, 'failed_compile_phase': phase, 'junit_ran': False, 'manifest': str(out / 'manifest.json')}, indent=2) + '\n')
        sys.exit(r.returncode)
tests = ['dev.chungjungsoo.gptmobile.data.voice.' + name for name in test_names]
r = subprocess.run([java, '-ea', '-Xmx256m', '-cp', str(test_classes) + ':' + str(classes) + ':' + ':'.join(map(str, cp)), 'org.junit.runner.JUnitCore', *tests], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(out / 'junit.log').write_text(r.stdout)
print('test_exit', r.returncode)
print(r.stdout)
(out / 'qualification.json').write_text(json.dumps({'compile_exit': 0, 'junit_exit': r.returncode, 'test_classes': tests, 'passed_test_count': int(re.search(r'OK \((\d+) tests?\)', r.stdout).group(1)) if re.search(r'OK \((\d+) tests?\)', r.stdout) else None, 'assertions_enabled': True, 'manifest': str(out / 'manifest.json'), 'full_android_qualification': False, 'device_audio_qualification': False}, indent=2) + '\n')
sys.exit(r.returncode)

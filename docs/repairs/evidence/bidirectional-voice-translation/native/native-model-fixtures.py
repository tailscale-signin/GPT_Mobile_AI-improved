from pathlib import Path
import hashlib, importlib.metadata, json, sys, types
root=Path(__file__).parent
package=root/'python'
sys.path.insert(0,str(package))
# Load only the installed, unmodified MediaPipe text task and native binding.
# Namespace parents avoid importing optional vision/audio solutions (OpenCV/JAX).
# No detector, graph, prediction, protobuf, or native-runtime implementation is replaced.
for name in ['mediapipe','mediapipe.python','mediapipe.tasks','mediapipe.tasks.python','mediapipe.tasks.python.text','mediapipe.tasks.python.components','mediapipe.tasks.python.components.containers']:
 module=types.ModuleType(name); module.__path__=[str(package/Path(*name.split('.')))];sys.modules[name]=module
from mediapipe.tasks.python.core.base_options import BaseOptions
from mediapipe.tasks.python.text.language_detector import LanguageDetector, LanguageDetectorOptions
model=Path('/workspace/scratch/49479c165f4e/work-translation-detection/app/src/main/assets/voice/language_detector.tflite')
assert hashlib.sha256(model.read_bytes()).hexdigest()=='7db4f23dfe1ad8966b050b419a865da451143fd43eb6b606a256aadeeb1e5417'
fixtures=[('english_greeting','Hello, how are you today?','en'),('french_greeting','Bonjour, comment allez-vous aujourd’hui ?','fr'),('english_question','Could you tell me where the nearest train station is?','en'),('french_question','Pourriez-vous me dire où se trouve la gare la plus proche ?','fr'),('short_english','Hello','en'),('short_french','Bonjour','fr'),('outside_pair','Guten Morgen, wie geht es Ihnen heute?','de'),('ambiguous','Paris',None),('mixed','Hello, je voudrais un café please.',None)]
results=[]
with LanguageDetector.create_from_options(LanguageDetectorOptions(base_options=BaseOptions(model_asset_path=str(model)),max_results=3,score_threshold=0.0)) as detector:
 for name,text,expected in fixtures:
  predictions=[{'language':p.language_code,'confidence':p.probability} for p in detector.detect(text).detections]
  accepted=bool(predictions and predictions[0]['confidence']>=0.8 and predictions[0]['confidence']-(predictions[1]['confidence'] if len(predictions)>1 else 0)>=0.2 and predictions[0]['language'] in ['en','fr'] and sum(c.isalpha() for c in text)>=3)
  results.append({'fixture':name,'text':text,'expected_language':expected,'predictions':predictions,'passes_app_confidence_policy':accepted})
  if name in ['english_greeting','french_greeting','english_question','french_question']:
   assert predictions[0]['language']==expected and accepted,results[-1]
manifest={'schema':'voice-translation-native-model-fixtures-v1','runtime':'Official MediaPipe Python 0.10.21 Linux x86_64 wheel, unmodified LanguageDetector/TextClassifierGraph/native binding; package namespace loading omits unrelated optional vision/audio imports.','model_sha256':hashlib.sha256(model.read_bytes()).hexdigest(),'model_bytes':model.stat().st_size,'package_files_sha256':{str(p.relative_to(package)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [package/'mediapipe/tasks/python/text/language_detector.py',*list((package/'mediapipe/python').glob('_framework_bindings*.so'))]},'options':{'max_results':3,'score_threshold':0.0,'category_allowlist':None},'required_fixtures_passed':4,'fixtures':results,'limitations':['This qualifies these text fixtures and model bytes on a Linux host, not Android JNI, Android SpeechRecognizer, acoustic recognition or device performance.','Short, named and mixed-language cases are observations, not accuracy guarantees. Mixed-language text is outside the publisher model card’s intended scope.','Native-model runtime version differs from the existing Android Tasks text 1.0.0 / core 1.2.3 dependency; Android API compilation is recorded separately.']}
(root/'native-model-fixtures.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
print(json.dumps(manifest,indent=2,ensure_ascii=False))

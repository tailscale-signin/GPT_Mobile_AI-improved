"""Prepare a local, digest-bound development image from the approved mirror."""
import json
from pathlib import Path
from v14 import workspace_environment
import re
import tempfile
import threading
import time

ANDROID = 'Android / Kotlin'
PYTHON = 'Python'
NODE = 'Node / TypeScript'
IMAGES = {ANDROID: 'docker.io/library/eclipse-temurin:21-jdk-jammy',
          PYTHON: 'docker.io/library/python:3.12-bookworm', NODE: 'docker.io/library/node:22-bookworm'}
COMMANDS = {
    ANDROID: [['gateway-gradle', ':app:assembleDebug'], ['gateway-gradle', ':app:testDebugUnitTest'], ['gateway-gradle', ':app:lintDebug']],
    PYTHON: [['python3', '-m', 'compileall', '-q', '.'], ['python3', '-m', 'pytest']],
    NODE: [['gateway-node', 'typecheck'], ['gateway-node', 'test'], ['gateway-node', 'build']]
}

ANDROID_PACKAGES = r"""#!/bin/sh
set -eu
install_tools() {
    apt-get -o Acquire::Retries=1 -o Acquire::https::Timeout=15 -o Acquire::http::Timeout=15 "$@" -o APT::Update::Error-Mode=any update &&
    apt-get -o Acquire::Retries=1 -o Acquire::https::Timeout=15 -o Acquire::http::Timeout=15 "$@" install -y --no-install-recommends python3 curl unzip build-essential cmake ninja-build git ca-certificates
}
. /etc/os-release
keyring=/usr/share/keyrings/ubuntu-archive-keyring.gpg
if [ "$ID" != ubuntu ] || [ "$(dpkg --print-architecture)" != amd64 ] || [ ! -r "$keyring" ]; then
    install_tools
else
    source_list=$(mktemp)
    trap 'rm -f "$source_list"' EXIT
    for archive in https://azure.archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu https://archive.ubuntu.com/ubuntu; do
        security=$archive
        if [ "$archive" = https://archive.ubuntu.com/ubuntu ]; then security=https://security.ubuntu.com/ubuntu; fi
        {
            for suite in "$VERSION_CODENAME" "$VERSION_CODENAME-updates" "$VERSION_CODENAME-backports"; do
                printf 'deb [signed-by=%s] %s %s main restricted universe multiverse\n' "$keyring" "$archive" "$suite"
            done
            printf 'deb [signed-by=%s] %s %s-security main restricted universe multiverse\n' "$keyring" "$security" "$VERSION_CODENAME"
        } > "$source_list"
        if install_tools -o "Dir::Etc::sourcelist=$source_list" -o Dir::Etc::sourceparts=-; then
            rm -rf /var/lib/apt/lists/*
            exit 0
        fi
    done
    exit 1
fi
rm -rf /var/lib/apt/lists/*
"""

ANDROID_TOOLS = """
COPY --chmod=0755 gateway-android-packages /usr/local/bin/gateway-android-packages
RUN gateway-android-packages
RUN curl -fsSL --retry 3 https://download.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.zip -o /tmp/kotlin.zip && echo "c004242158f4b5e1d917ddd848e6f6a279484fa58a3e2bce8846b807d1ad16b1  /tmp/kotlin.zip" | sha256sum -c - && mkdir -p /opt/kotlin && unzip -q /tmp/kotlin.zip -d /opt/kotlin && chmod 0755 /opt/kotlin/kotlin-lsp.sh /opt/kotlin/jre/bin/java /opt/kotlin/jre/lib/jspawnhelper && ln -s /opt/kotlin/kotlin-lsp.sh /usr/local/bin/kotlin-lsp && rm /tmp/kotlin.zip
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/sdk.zip && echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  /tmp/sdk.zip" | sha256sum -c - && mkdir -p /opt/android/cmdline-tools && unzip -q /tmp/sdk.zip -d /tmp/sdk && mv /tmp/sdk/cmdline-tools /opt/android/cmdline-tools/19 && rm /tmp/sdk.zip && yes | /opt/android/cmdline-tools/19/bin/sdkmanager --licenses >/dev/null
RUN /opt/android/cmdline-tools/19/bin/sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.22.1' 'platform-tools'
RUN mkdir -p /opt/gateway-kokoro-deps && \
    curl -fsSL --retry 3 https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/af2d8d81dbfa61c4cea4c568da78f3982edca005 -o /opt/gateway-kokoro-deps/sherpa-onnx-af2d8d81dbfa61c4cea4c568da78f3982edca005.tar.gz && \
    echo "afcafe3c34bc2f15a363ad791fa1644a69bf0977a58c1b4f617468976c2faeb5  /opt/gateway-kokoro-deps/sherpa-onnx-af2d8d81dbfa61c4cea4c568da78f3982edca005.tar.gz" | sha256sum -c - && \
    curl -fsSL --retry 3 https://codeload.github.com/festvox/flite/tar.gz/e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88 -o /opt/gateway-kokoro-deps/flite-e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88.tar.gz && \
    echo "1eebf476797cea10b4706cfbb9db9ba34f7b8ccb1fe7e0069b0b7528e0b0be46  /opt/gateway-kokoro-deps/flite-e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88.tar.gz" | sha256sum -c - && \
    curl -fsSL --retry 3 https://codeload.github.com/JuliaStrings/utf8proc/tar.gz/refs/tags/v2.12.0 -o /opt/gateway-kokoro-deps/utf8proc-v2.12.0.tar.gz && \
    echo "f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111  /opt/gateway-kokoro-deps/utf8proc-v2.12.0.tar.gz" | sha256sum -c - && \
    curl -fsSL --retry 3 https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-arm64-v8a-static_lib-1.28.2.zip -o /opt/gateway-kokoro-deps/onnxruntime-android-arm64-v8a-static_lib-1.28.2.zip && \
    echo "bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0  /opt/gateway-kokoro-deps/onnxruntime-android-arm64-v8a-static_lib-1.28.2.zip" | sha256sum -c - && \
    curl -fsSL --retry 3 https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-x86_64-static_lib-1.28.2.zip -o /opt/gateway-kokoro-deps/onnxruntime-android-x86_64-static_lib-1.28.2.zip && \
    echo "f49dbf456ffe71dd417afb2123ef15202f8f57a4b2a05f12af1cf71f55448137  /opt/gateway-kokoro-deps/onnxruntime-android-x86_64-static_lib-1.28.2.zip" | sha256sum -c -
ENV GPTMOBILE_KOKORO_DEPENDENCY_CACHE=/opt/gateway-kokoro-deps
"""
ANDROID_PREPARE_INIT = """// Only used while the image has network access. Resolve test runner and lint tooling too.
gradle.beforeProject { project ->
    project.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { test ->
        if (test.hasProperty("dryRun")) {
            test.dryRun.set(true)
            test.filter.setIncludePatterns("dev.chungjungsoo.gptmobile.data.github.GatewayGraphTest.requestAlwaysCarriesSelectedImmutableRevision")
        } else {
            test.filter.setIncludePatterns("gateway.preparation.DependencyCacheProbe")
        }
        test.filter.setFailOnNoMatchingTests(false)
    }
    if (project.path == ":app") {
        project.tasks.register("gatewayPrepareAndroidDependencies") {
            doLast {
                def configurations = [project.configurations.getByName("debugUnitTestRuntimeClasspath")]
                for (def name : ["jacocoAgent", "lintClassPath", "lintChecks"]) {
                    def optional = project.configurations.findByName(name)
                    if (optional != null && optional.canBeResolved) configurations.add(optional)
                }
                for (def configuration : configurations) {
                    // Android selects project artifact transforms in its own test tasks.
                    configuration.incoming.artifactView {
                        componentFilter { id -> !(id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier) }
                    }.files.files
                }
            }
        }
    }
}
"""
ANDROID_WARM = """
ENV GRADLE_USER_HOME=/opt/gradle-seed GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY=1
RUN if [ -f scripts/install_android_native_dependencies.sh ]; then GITHUB_ENV=/tmp/gateway-android-env bash scripts/install_android_native_dependencies.sh && mkdir -p /opt/gateway-native && cp -R "$(sed -n 's/^VULKAN_SDK=//p' /tmp/gateway-android-env)/." /opt/gateway-native/; fi
ENV VULKAN_SDK=/opt/gateway-native CMAKE_PREFIX_PATH=/opt/gateway-native
RUN if [ -f scripts/provision_memory_model.py ]; then python3 scripts/provision_memory_model.py; fi && if [ -f scripts/provision_robolectric_sdks.py ]; then python3 scripts/provision_robolectric_sdks.py; fi
RUN gateway-gradle-build prepare :app:assembleDebug :app:gatewayPrepareAndroidDependencies :app:testDebugUnitTest --init-script /opt/gateway-prepare-android.gradle --no-configuration-cache && chmod -R a+rX /opt/gradle-seed
RUN mkdir -p /opt/robolectric && if [ -d /root/.m2/repository ]; then cp -R /root/.m2/repository/. /opt/robolectric/; fi
"""
GRADLE_BUILD = """#!/bin/sh
set -eu
mode=$1
shift
run_gradle() {
    if [ "$mode" = offline ]; then set -- --offline "$@"; fi
    sh ./gradlew --no-daemon --max-workers=1 "$@"
}
if [ -f scripts/android_pipeline.py ] && [ -d app/src/main/kotlin/dev/chungjungsoo/gptmobile ]; then
    case " $* " in
        *:app:assembleDebug*|*:app:testDebugUnitTest*|*:app:compileDebugUnitTestKotlin*|*:app:lintDebug*)
            heap='-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=768m -XX:+UseG1GC -XX:+ParallelRefProcEnabled -Dfile.encoding=UTF-8'
            run_gradle :app:kspDebugKotlin
            run_gradle :app:kaptDebugKotlin "$heap"
            run_gradle :app:compileDebugKotlin :app:compileDebugJavaWithJavac "$heap"
            run_gradle "$@" "$heap"
            exit 0 ;;
    esac
fi
run_gradle "$@"
"""
GRADLE_WRAPPER = """#!/bin/sh
set -eu
# Keep writable Gradle transforms with the disposable checkout. The seed image stays read-only.
export GRADLE_USER_HOME="$(pwd -P)/.gradle/gateway-user-home"
export GRADLE_RO_DEP_CACHE=/opt/gradle-seed/caches
export GPTMOBILE_PREPARED_ANDROID_CXX=/seed/local-gguf-runtime/.cxx/Debug
mkdir -p "$GRADLE_USER_HOME/caches" "$HOME/.m2"
test -e "$GRADLE_USER_HOME/wrapper" || cp -R /opt/gradle-seed/wrapper "$GRADLE_USER_HOME/wrapper"
if [ -d /opt/gradle-seed/caches/gptmobile-models ] && [ ! -e "$GRADLE_USER_HOME/caches/gptmobile-models" ]; then ln -s /opt/gradle-seed/caches/gptmobile-models "$GRADLE_USER_HOME/caches/gptmobile-models"; fi
test -e "$HOME/.m2/repository" || ln -s /opt/robolectric "$HOME/.m2/repository"
# Room's verified SQLite JDBC driver loads its native library during KSP. Docker /tmp is noexec.
native_tmp="$GRADLE_USER_HOME/native-tmp"
mkdir -p "$native_tmp"
chmod 0700 "$native_tmp"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dorg.sqlite.tmpdir=$native_tmp"
exec gateway-gradle-build offline "$@"
"""
NODE_WRAPPER = """#!/bin/sh
set -eu
manager=$(cat /opt/package-manager)
if [ ! -d node_modules ]; then cp -R /opt/modules node_modules; fi
if [ "$manager" = yarn ]; then exec yarn run "$@"; fi
exec "$manager" run "$@"
"""
NODE_WARM = """
RUN if [ -f pnpm-lock.yaml ]; then corepack enable && pnpm install --frozen-lockfile && echo pnpm >/opt/package-manager; elif [ -f yarn.lock ]; then corepack enable && case "$(yarn --version)" in 1.*) yarn install --frozen-lockfile ;; *) YARN_NODE_LINKER=node-modules yarn install --immutable ;; esac && echo yarn >/opt/package-manager; elif [ -f package-lock.json ] || [ -f npm-shrinkwrap.json ]; then npm ci && echo npm >/opt/package-manager; else echo 'Commit an npm, pnpm or Yarn lockfile before preparing Node' >&2; exit 1; fi
RUN mkdir -p node_modules && mv node_modules /opt/modules
"""


def prepare_image(profile, base, mirror, head, cancel, *, run_build=None, custom=None):
    from desktop.repository_validation import docker_run
    from v14.engineering_validation import materialize_checkout
    build = run_build or docker_run
    commands, environment, _ = workspace_environment.read({'workspace_environment': custom or {}})
    if not re.fullmatch(r'[a-f0-9]{40}', head):
        raise ValueError('Image preparation requires a full source commit')
    if (profile not in IMAGES and not commands) or not re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}', base):
        raise ValueError('Choose a supported profile and verified base image')
    with tempfile.TemporaryDirectory(prefix='gateway-profile-') as directory:
        root = Path(directory)
        job = {'data': {'head_sha': head}, 'deadline': time.time() + 3600}
        checkout = materialize_checkout(job, mirror, [], root, cancel or threading.Event())
        # Only this verified archive and our recipe enter the build context.
        # Do not interpret a repository Dockerfile or .dockerignore.
        archive = root / 'source.tar'
        if profile == ANDROID and not (checkout / 'gradlew').is_file():
            raise ValueError('Android preparation requires the Gradle wrapper in the repository root')
        context = root / 'build'
        context.mkdir()
        archive.rename(context / 'source.tar')
        recipe = 'FROM ' + base + '\n'
        if profile == ANDROID:
            (context / 'gateway-android-packages').write_bytes(ANDROID_PACKAGES.encode())
            recipe += ANDROID_TOOLS
        if profile == NODE:
            recipe += 'RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*\nENV COREPACK_HOME=/opt/corepack\nRUN npm install -g typescript-language-server@4.3.4 typescript@5.9.3\n'
        recipe += 'WORKDIR /seed\nADD source.tar /seed/\n'
        if profile == ANDROID:
            # git archive omits .git. ARG supplies the approved source only while building.
            (context / 'gateway-gradle-build').write_text(GRADLE_BUILD, encoding='utf-8', newline='\n')
            recipe += 'COPY --chmod=0755 gateway-gradle-build /usr/local/bin/gateway-gradle-build\n'
            (context / 'gateway-prepare-android.gradle').write_bytes(ANDROID_PREPARE_INIT.encode())
            recipe += 'COPY gateway-prepare-android.gradle /opt/gateway-prepare-android.gradle\n'
            recipe += 'ARG GITHUB_SHA=' + head + '\n' + ANDROID_WARM
            (context / 'gateway-gradle').write_bytes(GRADLE_WRAPPER.encode())
            recipe += 'COPY --chmod=0755 gateway-gradle /usr/local/bin/gateway-gradle\n'
        elif profile == NODE:
            recipe += NODE_WARM
            (context / 'gateway-node').write_bytes(NODE_WRAPPER.encode())
            recipe += 'COPY --chmod=0755 gateway-node /usr/local/bin/gateway-node\n'
        elif profile == PYTHON:
            recipe += ('RUN python -m pip install pytest python-lsp-server && if [ -f requirements.txt ]; then python -m pip install -r requirements.txt; '
                       'elif [ -f pyproject.toml ]; then python -m pip install .; fi\n')
        if commands:
            for argv in commands:
                # JSON exec form gives the recipe no instruction or shell interpolation.
                assignments = [name + '=' + value for name, value in sorted(environment.items())]
                recipe += 'RUN ' + json.dumps(['env', *assignments, *argv], ensure_ascii=True) + '\n'
            recipe += 'RUN ["python3","--version"]\n'
        (context / 'Dockerfile').write_bytes((recipe + 'WORKDIR /workspace\n').encode())
        receipt = root / 'image-id'
        build(['build', '--quiet', '--iidfile', str(receipt), str(context)], cancel=cancel, timeout=3600, label='Prepare local development image')
        digest = receipt.read_text().strip()
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
            raise ValueError('Docker did not attest the prepared image ID')
        return {'profile': profile, 'base_image': base, 'image_id': digest, 'source_sha': head, **({'recipe_sha256': workspace_environment.recipe_id(commands, environment)} if commands else {})}

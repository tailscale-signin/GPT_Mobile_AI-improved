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

ANDROID_TOOLS = """
RUN apt-get update && apt-get install -y --no-install-recommends python3 curl unzip build-essential cmake ninja-build git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL --retry 3 https://download.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.zip -o /tmp/kotlin.zip && echo "c004242158f4b5e1d917ddd848e6f6a279484fa58a3e2bce8846b807d1ad16b1  /tmp/kotlin.zip" | sha256sum -c - && mkdir -p /opt/kotlin && unzip -q /tmp/kotlin.zip -d /opt/kotlin && chmod 0755 /opt/kotlin/kotlin-lsp.sh /opt/kotlin/jre/bin/java /opt/kotlin/jre/lib/jspawnhelper && ln -s /opt/kotlin/kotlin-lsp.sh /usr/local/bin/kotlin-lsp && rm /tmp/kotlin.zip
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/sdk.zip && echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  /tmp/sdk.zip" | sha256sum -c - && mkdir -p /opt/android/cmdline-tools && unzip -q /tmp/sdk.zip -d /tmp/sdk && mv /tmp/sdk/cmdline-tools /opt/android/cmdline-tools/19 && rm /tmp/sdk.zip && yes | /opt/android/cmdline-tools/19/bin/sdkmanager --licenses >/dev/null
RUN /opt/android/cmdline-tools/19/bin/sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.22.1' 'platform-tools'
"""
ANDROID_WARM = """
ENV GRADLE_USER_HOME=/opt/gradle-seed GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY=1
RUN if [ -f scripts/install_android_native_dependencies.sh ]; then GITHUB_ENV=/tmp/gateway-android-env bash scripts/install_android_native_dependencies.sh && mkdir -p /opt/gateway-native && cp -R "$(sed -n 's/^VULKAN_SDK=//p' /tmp/gateway-android-env)/." /opt/gateway-native/; fi
ENV VULKAN_SDK=/opt/gateway-native CMAKE_PREFIX_PATH=/opt/gateway-native
RUN if [ -f scripts/provision_memory_model.py ]; then python3 scripts/provision_memory_model.py; fi && if [ -f scripts/provision_robolectric_sdks.py ]; then python3 scripts/provision_robolectric_sdks.py; fi
RUN gateway-gradle-build prepare :app:assembleDebug :app:compileDebugUnitTestKotlin && chmod -R a+rX /opt/gradle-seed
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
mkdir -p "$GRADLE_USER_HOME/caches" "$HOME/.m2"
test -e "$GRADLE_USER_HOME/wrapper" || cp -R /opt/gradle-seed/wrapper "$GRADLE_USER_HOME/wrapper"
if [ -d /opt/gradle-seed/caches/gptmobile-models ] && [ ! -e "$GRADLE_USER_HOME/caches/gptmobile-models" ]; then ln -s /opt/gradle-seed/caches/gptmobile-models "$GRADLE_USER_HOME/caches/gptmobile-models"; fi
test -e "$HOME/.m2/repository" || ln -s /opt/robolectric "$HOME/.m2/repository"
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
            recipe += ANDROID_TOOLS
        if profile == NODE:
            recipe += 'RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*\nENV COREPACK_HOME=/opt/corepack\nRUN npm install -g typescript-language-server@4.3.4 typescript@5.9.3\n'
        recipe += 'WORKDIR /seed\nADD source.tar /seed/\n'
        if profile == ANDROID:
            # git archive omits .git. ARG supplies the approved source only while building.
            (context / 'gateway-gradle-build').write_text(GRADLE_BUILD, encoding='utf-8', newline='\n')
            recipe += 'COPY --chmod=0755 gateway-gradle-build /usr/local/bin/gateway-gradle-build\n'
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

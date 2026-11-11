#!/usr/bin/env bash
set -euo pipefail

# The hosted Ubuntu mirror list can select an unreachable HTTP archive.
# Give this install its own signed HTTPS sources and preserve the runner's apt configuration.
source /etc/os-release
if [[ ${ID:-} != ubuntu || ! ${VERSION_CODENAME:-} =~ ^[a-z][a-z0-9-]*$ ]]; then
    echo '::error::Android native dependencies require a supported Ubuntu runner.' >&2
    exit 1
fi

architecture=$(dpkg --print-architecture)
archive=https://archive.ubuntu.com/ubuntu
security=https://security.ubuntu.com/ubuntu
case "$architecture" in
    amd64|i386) ;;
    *) archive=https://ports.ubuntu.com/ubuntu-ports
       security=$archive ;;
esac

sources=$(mktemp --suffix=.sources)
trap 'rm -f -- "$sources"' EXIT
trap 'echo "::error::Android native dependency installation from Ubuntu HTTPS archives failed." >&2' ERR
keyring=/usr/share/keyrings/ubuntu-archive-keyring.gpg
test -r "$keyring"
cat > "$sources" <<EOF
Types: deb
URIs: $archive
Suites: $VERSION_CODENAME $VERSION_CODENAME-updates
Components: main universe
Architectures: $architecture
Signed-By: $keyring

Types: deb
URIs: $security
Suites: $VERSION_CODENAME-security
Components: main universe
Architectures: $architecture
Signed-By: $keyring
EOF

apt_options=(
    -o "Dir::Etc::sourcelist=$sources"
    -o Dir::Etc::sourceparts=-
    -o APT::Get::List-Cleanup=false
    -o Acquire::Languages=none
    -o Acquire::Retries=2
    -o Acquire::https::Timeout=20
    -o DPkg::Lock::Timeout=60
)
sudo timeout --kill-after=15s 180s apt-get "${apt_options[@]}" -o APT::Update::Error-Mode=any update
sudo env DEBIAN_FRONTEND=noninteractive timeout --kill-after=15s 180s apt-get \
    "${apt_options[@]}" install --no-install-recommends -y glslc libvulkan-dev spirv-headers

#!/usr/bin/env sh
# Builds the AgentMesh Android control app inside agentmesh/android-build.
#   1. gomobile-binds the exit-node engine (mobile/tunnel) into an AAR
#   2. creates a local release signing key on first run (kept out of git)
#   3. assembles two signed release APKs: dist/agentmesh-control.apk (normal app)
#      and dist/agentmesh-admin.apk (owner's admin app)
set -eu
cd /src
APP=mobile/android-control
mkdir -p "$APP/app/libs" dist

echo ">> gomobile bind (arm64, arm, x86_64)"
gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 \
  -javapkg=io.agentmesh -ldflags="-s -w" -trimpath \
  -o "$APP/app/libs/agentmesh-tunnel.aar" ./mobile/tunnel

cd "$APP"
if [ ! -f keystore/keystore.properties ]; then
  echo ">> generating release signing key (keystore/, not committed)"
  mkdir -p keystore
  PASS=$(head -c 24 /dev/urandom | base64 | tr -d '+/=')
  keytool -genkeypair -v -keystore keystore/release.jks -alias agentmesh -keyalg RSA -keysize 3072 \
    -validity 9125 -storepass "$PASS" -keypass "$PASS" -dname "CN=AgentMesh Control, O=AgentMesh" >/dev/null 2>&1
  printf 'storeFile=keystore/release.jks\nstorePassword=%s\nkeyAlias=agentmesh\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore/keystore.properties
fi

echo ">> gradle assembleControlRelease assembleAdminRelease"
gradle --no-daemon --console=plain -q assembleControlRelease assembleAdminRelease
VER=${AGENTMESH_VERSION_NAME:-0.0.0-dev}
rm -f /src/dist/agentmesh-control-v*.apk /src/dist/agentmesh-admin-v*.apk
for role in control admin; do
  out=app/build/outputs/apk/$role/release/app-$role-release.apk
  cp "$out" /src/dist/agentmesh-$role-v$VER.apk
  cp "$out" /src/dist/agentmesh-$role.apk   # stable name for the /latest link
done
ls -l /src/dist/agentmesh-control*.apk /src/dist/agentmesh-admin*.apk

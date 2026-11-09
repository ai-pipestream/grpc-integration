#!/usr/bin/env bash
# Verify Maven publication, including Gradle test-fixture variants, without
# allowing a sibling composite checkout to substitute for the artifacts.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo "usage: $0 /path/to/grpc-confluence /path/to/grpc-microsoft" >&2
  exit 2
fi
core=$(cd -- "$(dirname -- "$0")/.." && pwd)
confluence=$(cd -- "$1" && pwd)
microsoft=$(cd -- "$2" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
export INTEGRATION_VERIFICATION_REPOSITORY="$scratch/maven"
cat > "$scratch/publish.gradle" <<'GRADLE'
allprojects {
    plugins.withId('maven-publish') {
        publishing.repositories.maven {
            name = 'Verification'
            url = new File(System.getenv('INTEGRATION_VERIFICATION_REPOSITORY')).toURI()
        }
    }
}
GRADLE
cat > "$scratch/consume.gradle" <<'GRADLE'
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories.withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository).configureEach { repo ->
        if (repo.name == 'PipestreamRegistry') {
            repo.url = new File(System.getenv('INTEGRATION_VERIFICATION_REPOSITORY')).toURI()
        }
    }
}
GRADLE
run_gradle() {
  local checkout=$1
  shift
  "$checkout/gradlew" -p "$checkout" --no-daemon --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx1g -XX:ActiveProcessorCount=2' "$@"
}
run_gradle "$core" -I "$scratch/publish.gradle" publishAllPublicationsToVerificationRepository
for checkout in "$confluence" "$microsoft"; do
  run_gradle "$checkout" -I "$scratch/consume.gradle" \
    "-PintegrationDir=$scratch/no-composite" build
done

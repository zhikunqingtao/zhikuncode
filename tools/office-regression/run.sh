#!/usr/bin/env bash
# R-12 offline regression launcher (single entry point).
#
#   ./run.sh                 build the pinned image, then run the full pytest
#                            suite inside `docker run --network none`
#   R12_SKIP_BUILD=1 ./run.sh   reuse an existing image
#   OFFICE_REGRESSION_OUT=DIR ./run.sh   override the output/evidence directory
#   R12_PYTEST_ARGS='...' ./run.sh       extra pytest arguments
#
# Exit code: pytest exit code (0 = all tests passed). Missing docker / daemon /
# pinned base image or a build failure produces an explicit R12 ERROR line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMAGE_TAG="${R12_IMAGE_TAG:-office-regression-r12:local}"
# Default outside the repository and in a Docker-shareable location (/tmp is
# shared by Docker Desktop on macOS; $TMPDIR often is not).
OUT_DIR="${OFFICE_REGRESSION_OUT:-/tmp/office-regression-out}"
PYTEST_ARGS="${R12_PYTEST_ARGS:--v -p no:cacheprovider --timeout=600 --durations=10}"

fail() { printf 'R12 ERROR: %s\n' "$1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "docker CLI not found on PATH; install Docker or run the suite manually"
docker info >/dev/null 2>&1 || fail "docker daemon is not reachable (is Docker Desktop running?)"
[ -f "${REPO_ROOT}/backend/pom.xml" ] || fail "repository root detection failed: ${REPO_ROOT} has no backend/pom.xml"
[ -f "${SCRIPT_DIR}/Dockerfile" ] || fail "missing ${SCRIPT_DIR}/Dockerfile"

OUT_DIR_PREEXISTED=0
if [ -d "${OUT_DIR}" ]; then OUT_DIR_PREEXISTED=1; fi
mkdir -p "${OUT_DIR}" || fail "cannot create output directory: ${OUT_DIR}"
# Evidence must never land inside the repository: resolve real paths with
# `pwd -P` (follows symlinks) and reject any OUT_DIR at or under the repo root.
OUT_DIR_REAL="$(cd "${OUT_DIR}" && pwd -P)"
REPO_ROOT_REAL="$(cd "${REPO_ROOT}" && pwd -P)"
case "${OUT_DIR_REAL}" in
  "${REPO_ROOT_REAL}" | "${REPO_ROOT_REAL}"/*)
    # Leave no residue when rejecting: remove the directory only when this run
    # created it and it is still empty; pre-existing content is never deleted.
    if [ "${OUT_DIR_PREEXISTED}" != "1" ]; then rmdir "${OUT_DIR}" 2>/dev/null || true; fi
    fail "OFFICE_REGRESSION_OUT must be outside the repository: ${OUT_DIR_REAL} is inside ${REPO_ROOT_REAL}" ;;
esac
: > "${OUT_DIR}/pytest.log"
IMAGE_IDENTITY="${OUT_DIR}/image-identity.txt"

if [ "${R12_SKIP_BUILD:-0}" != "1" ]; then
  echo "== building ${IMAGE_TAG} from ${SCRIPT_DIR}/Dockerfile"
  if ! docker build -t "${IMAGE_TAG}" "${SCRIPT_DIR}"; then
    fail "image build failed (network is allowed during build; check registry/apt access)"
  fi
else
  echo "== R12_SKIP_BUILD=1: reusing ${IMAGE_TAG}"
fi

# Resolve the image id before writing any evidence. A daemon that just woke up
# (Docker VM cold start) can transiently answer "No such image" while `docker
# info` already succeeds; an unguarded `$(...)` inside echo would silently turn
# that into an empty image_id. Retry with backoff, then fail loudly.
IMAGE_ID=""
for ((ATTEMPT = 1; ATTEMPT <= 30; ATTEMPT++)); do
  IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${IMAGE_TAG}" 2>/dev/null || true)"
  if [ -n "${IMAGE_ID}" ]; then
    break
  fi
  if [ "${ATTEMPT}" -eq 1 ]; then
    echo "== docker has not resolved ${IMAGE_TAG} yet (engine still starting?); retrying up to ~10s"
  fi
  if [ "${ATTEMPT}" -le 20 ]; then
    sleep 0.25
  else
    sleep 0.5
  fi
done
[ -n "${IMAGE_ID}" ] || fail "docker could not resolve a non-empty image id for ${IMAGE_TAG} after ~10s of retries (engine still starting, or image/tag missing); refusing to write empty evidence"

{
  echo "image_tag: ${IMAGE_TAG}"
  echo "image_id: ${IMAGE_ID}"
  echo "base_image: $(sed -n 's/^BASE_IMAGE=//p' "${SCRIPT_DIR}/pins.env")"
  echo "base_digest: $(sed -n 's/^BASE_IMAGE_DIGEST=//p' "${SCRIPT_DIR}/pins.env")"
  echo "repo_root: ${REPO_ROOT}"
  echo "built_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "${IMAGE_IDENTITY}"
cat "${IMAGE_IDENTITY}"

echo "== running suite with --network none (output -> ${OUT_DIR})"
set +e
docker run --rm --network none \
  -v "${REPO_ROOT}":/work:ro \
  -v "${OUT_DIR}":/out \
  -e OFFICE_REGRESSION_OUT=/out \
  -e R12_REPO_ROOT=/work \
  -e R12_TOOLS_DIR=/work/tools/office-regression \
  -e PYTHONPATH=/work/python-service/src \
  -e HOME=/tmp \
  -w /work/tools/office-regression \
  "${IMAGE_TAG}" \
  /opt/r12/venv/bin/python -m pytest ${PYTEST_ARGS} tests \
  2>&1 | tee -a "${OUT_DIR}/pytest.log"
STATUS=${PIPESTATUS[0]}
set -e

{
  echo "exit_code: ${STATUS}"
  echo "finished_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >> "${OUT_DIR}/pytest.log"

echo "== evidence directory listing: ${OUT_DIR}"
find "${OUT_DIR}" -type f | sort > "${OUT_DIR}/listing.txt"
cat "${OUT_DIR}/listing.txt"
exit "${STATUS}"

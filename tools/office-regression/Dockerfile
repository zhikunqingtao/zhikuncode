# R-12 offline Office/HTML regression image.
#
# Base image is pinned by digest (see pins.env, discovered with
# `docker pull --platform linux/arm64 mcr.microsoft.com/playwright/python:v1.58.0-noble`).
# Build-time network is allowed; the suite itself always runs with --network none.
FROM mcr.microsoft.com/playwright/python:v1.58.0-noble@sha256:678457c4c323b981d8b4befc57b95366bb1bb6aa30057b1269f6b171e8d9975a

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

COPY pins.env /opt/r12/pins.env
COPY requirements.lock /opt/r12/requirements.lock

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    OFFICE_REGRESSION_PINS=/opt/r12/pins.env

# LibreOffice + CJK fonts + poppler, all pinned to exact noble-updates versions.
# The apt cache mount keeps downloaded .debs across rebuild attempts (it is not
# part of the final image), so an interrupted build does not restart downloads.
# The install itself is wrapped in a retry loop: the LibreOffice/font .debs are
# large and flaky-proxy failures mid-download are common; each retry resumes
# from the cache and only re-fetches what is still missing.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    set -eux; \
    set -a; . /opt/r12/pins.env; set +a; \
    apt-get update -o Acquire::Retries=5; \
    for attempt in 1 2 3 4 5; do \
        echo "== apt install attempt ${attempt}"; \
        if DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
            -o Acquire::Retries=5 -o Acquire::http::Timeout=90 \
            "libreoffice-core=${LIBREOFFICE_VERSION}" \
            "libreoffice-common=${LIBREOFFICE_VERSION}" \
            "libreoffice-calc=${LIBREOFFICE_VERSION}" \
            "libreoffice-writer=${LIBREOFFICE_VERSION}" \
            "libreoffice-impress=${LIBREOFFICE_VERSION}" \
            "fonts-noto-cjk=${FONTS_NOTO_CJK_VERSION}" \
            "poppler-utils=${POPPLER_UTILS_VERSION}"; then \
            break; \
        fi; \
        if [ "${attempt}" = "5" ]; then \
            echo "apt install failed after ${attempt} attempts" >&2; \
            exit 1; \
        fi; \
        sleep 5; \
    done; \
    rm -rf /var/lib/apt/lists/*

# Pinned python test/runtime packages in an isolated venv (system python is
# externally managed on noble). Browsers come from the base image at /ms-playwright.
# The base image has no `ensurepip` (Ubuntu splits it into python3-venv), so the
# venv is created with --without-pip and populated through the base image's own
# pip via `pip --python` (checked against the pinned base image digest).
# The pip cache mount likewise survives interrupted builds (not part of the image).
RUN --mount=type=cache,target=/root/.cache/pip,sharing=locked \
    set -eux; \
    set -a; . /opt/r12/pins.env; set +a; \
    python3 -m venv --without-pip /opt/r12/venv; \
    python3 -m pip --python /opt/r12/venv/bin/python install --disable-pip-version-check \
        --no-input --retries 5 --timeout 60 \
        -r /opt/r12/requirements.lock; \
    /opt/r12/venv/bin/python - <<'PY'
from importlib.metadata import version
for name in ("playwright", "pytest", "pytest-timeout", "fastapi", "starlette", "uvicorn", "pydantic"):
    print("pinned", name, version(name))
PY

# Fail-fast image self check: soffice + poppler + a CJK font must exist.
# NOTE: avoid `cmd | grep -q` / `cmd | head` under `set -o pipefail` here --
# the reader may exit early and kill the producer with SIGPIPE (141).
RUN set -eux; \
    soffice --version; \
    fc-list > /tmp/fc-list.txt; \
    grep -qi "Noto Sans CJK" /tmp/fc-list.txt; \
    pdftotext -v > /tmp/pdftotext-version.txt 2>&1 || true; \
    grep -q "pdftotext version" /tmp/pdftotext-version.txt; \
    head -1 /tmp/pdftotext-version.txt; \
    rm -f /tmp/fc-list.txt /tmp/pdftotext-version.txt; \
    echo "office-regression image self-check OK"

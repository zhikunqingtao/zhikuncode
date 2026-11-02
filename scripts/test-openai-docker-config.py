#!/usr/bin/env python3
"""Check Compose wiring and key preflight with fake keys; no Docker daemon needed.

Run from the repository root: python3 scripts/test-openai-docker-config.py
Requires Python 3, Bash, and Docker Compose. Does not start containers or the app.
"""

import json
import os
from pathlib import Path
import subprocess
import unittest


REPO = Path(__file__).resolve().parent.parent


class OpenAiDockerConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        entrypoint = REPO / "docker-entrypoint.sh"
        subprocess.run(["bash", "-n", str(entrypoint)], check=True, timeout=10)
        source = entrypoint.read_text()
        # Execute the real key check without mkdir, key logging, or launching Java.
        start = source.index("HAS_PROVIDER_KEY=false")
        end = source.index("# 2. Print configuration summary", start)
        cls.preflight = "set -e\n" + source[start:end]

    def check_config(self, overrides, expected_exit):
        # Do not inherit LLM keys or Compose overrides from the developer's shell.
        env = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}
        result = subprocess.run(
            ["docker", "compose", "--env-file", os.devnull,
             "-f", str(REPO / "docker-compose.yml"), "config", "--format", "json"],
            cwd=REPO, env={**env, **overrides},
            text=True, capture_output=True, check=True, timeout=30,
        )
        container_env = json.loads(result.stdout)["services"]["zhikuncode"]["environment"]
        self.assertEqual(container_env.get("LLM_PROVIDER_OPENAI_API_KEY"),
                         overrides.get("LLM_PROVIDER_OPENAI_API_KEY", ""))
        self.assertEqual(container_env.get("LLM_PROVIDER_OPENAI_MODELS"),
                         overrides.get("LLM_PROVIDER_OPENAI_MODELS") or "gpt-6-astra")
        preflight = subprocess.run(
            ["bash", "-c", self.preflight],
            env={key: str(value or "") for key, value in container_env.items()},
            text=True, capture_output=True, timeout=10,
        )
        self.assertEqual(preflight.returncode, expected_exit,
                         preflight.stdout + preflight.stderr)

    def test_openai_only_and_model_list(self):
        for models in (None, "", "gpt-6-astra,fixture-model"):
            with self.subTest(models=models):
                overrides = {"LLM_PROVIDER_OPENAI_API_KEY": "test-openai-fixture"}
                if models is not None:
                    overrides["LLM_PROVIDER_OPENAI_MODELS"] = models
                self.check_config(overrides, 0)

    def test_missing_and_blank_keys_are_rejected(self):
        for key in (None, "", " \t "):
            with self.subTest(key=key):
                overrides = {} if key is None else {"LLM_PROVIDER_OPENAI_API_KEY": key}
                self.check_config(overrides, 1)

    def test_existing_providers_and_mixed_config(self):
        providers = ("DASHSCOPE", "DASHSCOPE_TOKEN_PLAN", "DEEPSEEK",
                     "MOONSHOT", "ZHIPU", "ZENMUX")
        for provider in providers:
            with self.subTest(provider=provider):
                self.check_config({f"LLM_PROVIDER_{provider}_API_KEY": "test-existing-fixture"}, 0)
        self.check_config({"LLM_API_KEY": "test-legacy-fixture"}, 0)
        self.check_config({"LLM_API_KEY": "your-api-key-here"}, 1)
        self.check_config({"LLM_PROVIDER_OPENAI_API_KEY": "test-openai-fixture",
                           "LLM_PROVIDER_ZENMUX_API_KEY": "test-zenmux-fixture"}, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)

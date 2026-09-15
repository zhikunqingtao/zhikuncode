#!/bin/sh
set -eu
export PORT="${PORT:-9000}"
exec node server.js

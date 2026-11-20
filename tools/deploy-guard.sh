#!/bin/sh
# Refuses a Worker deploy while any of the named top-level directories holds uncommitted or
# untracked files. A deploy uploads whatever is on disk, so without this "what is live?" has no
# answer in git. To deploy a deliberate local state anyway, call `pnpm exec wrangler deploy`.
set -eu
cd "$(git rev-parse --show-toplevel)"
dirty=$(git status --porcelain -- "$@")
if [ -n "$dirty" ]; then
  echo "deploy refused: uncommitted changes in: $*" >&2
  echo "$dirty" >&2
  exit 1
fi

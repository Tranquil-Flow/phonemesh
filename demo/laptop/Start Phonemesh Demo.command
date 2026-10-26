#!/bin/bash
# Double-click to start Phonemesh Demo on a Mac. It runs start-demo.sh from this folder.
cd "$(dirname "$0")" || exit 1
./start-demo.sh "$@"
rc=$?
if [ "$rc" != 0 ]; then
  printf '\nSomething went wrong (above). Press Return to close this window. '
  read -r _ || true
fi
exit "$rc"

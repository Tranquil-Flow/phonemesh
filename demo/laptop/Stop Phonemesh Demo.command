#!/bin/bash
# Double-click to stop the Phonemesh Demo virtual phone. Your demo chats are kept for next time.
cd "$(dirname "$0")" || exit 1
./start-demo.sh stop
printf 'Press Return to close this window. '
read -r _ || true

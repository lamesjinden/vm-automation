#!/usr/bin/env bash

cd "$(dirname "$0")";

# if necessary, update path to bb executable
bb=/usr/local/bin/bb

$bb -m scheduling.shutdown $1

#!/usr/bin/env bash
set -euo pipefail
sbt package
echo "Jar(s) in target/scala-*/"

#!/usr/bin/env bash
# Compiles the converter and runs it over samples/ into AdHoc/.
set -eu
cd "$(dirname "$0")"
rm -rf out
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.Thrift2AdHoc samples AdHoc

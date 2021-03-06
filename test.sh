#!/bin/bash

#
# Runs all tests.
#
# Prerequisites:
#   - Redis must be running.
#

export PATH="$PATH:$PWD/phantomjs/bin/"

lein version
lein compile
lein uberjar

mkdir resources
cp test-config.clj resources/config.clj

lein test && lein test :integration

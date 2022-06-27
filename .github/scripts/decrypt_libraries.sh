#!/bin/bash

set -e

if [ -z "$E4LINK_PASSPHRASE" ]; then
  touch plugins/radar-android-empatica/gradle.skip
else
  gpg --quiet --batch --yes --decrypt --passphrase="$E4LINK_PASSPHRASE" \
    --output plugins/radar-android-empatica/libs/E4link-1.0.0.aar .github/libs/E4link-1.0.0.aar.gpg
fi

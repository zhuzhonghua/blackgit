#!/bin/sh
# blackgit server entrypoint: translates env vars to CLI args.
#
#   BLACKGIT_PORT       (default 8081)
#   BLACKGIT_ROOT       (default /data)
#   BLACKGIT_UPSTREAM   (optional, e.g. https://github.com/user/repo.git)
#   BLACKGIT_READ_ONLY  (set to "1" to reject pushes)
#   BLACKGIT_TLS        (set to "1" to enable HTTPS)
#   BLACKGIT_KEYSTORE   (path to keystore when TLS=1)
#   BLACKGIT_KEYSTORE_PASSWORD
#   BLACKGIT_KEY_PASSWORD
#
# Any extra args passed to the container are appended as-is.

set -e

ARGS="--port ${BLACKGIT_PORT:-8081} --root ${BLACKGIT_ROOT:-/data}"

if [ -n "$BLACKGIT_UPSTREAM" ]; then
  ARGS="$ARGS --upstream $BLACKGIT_UPSTREAM"
fi

if [ "$BLACKGIT_READ_ONLY" = "1" ]; then
  ARGS="$ARGS --read-only"
fi

if [ "$BLACKGIT_TLS" = "1" ]; then
  ARGS="$ARGS --tls"
  [ -n "$BLACKGIT_KEYSTORE" ] && ARGS="$ARGS --keystore $BLACKGIT_KEYSTORE"
  [ -n "$BLACKGIT_KEYSTORE_PASSWORD" ] && ARGS="$ARGS --keystore-password $BLACKGIT_KEYSTORE_PASSWORD"
  [ -n "$BLACKGIT_KEY_PASSWORD" ] && ARGS="$ARGS --key-password $BLACKGIT_KEY_PASSWORD"
fi

# Allow overriding with extra args (e.g. --http-threads 8)
ARGS="$ARGS $@"

echo "blackgit starting: java -jar /app/blackgit.jar $ARGS"
exec java -jar /app/blackgit.jar $ARGS

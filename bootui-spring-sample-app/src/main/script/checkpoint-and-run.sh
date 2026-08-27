#!/bin/sh
# CRaC (Coordinated Restore at Checkpoint) entrypoint for the BootUI sample app.
#
# On the first start no checkpoint exists yet, so the app is launched with
# `spring.context.checkpoint=onRefresh`: Spring boots the application context and,
# as soon as the refresh finishes, the JVM writes a full process image into
# $CRAC_CHECKPOINT_DIR before CRIU terminates the process. Every subsequent start
# simply restores that image, which brings the application back in a few tens of
# milliseconds.
#
# The sample app runs with its default "dev" profile (in-memory H2 + in-memory
# cache), so nothing holds an open network socket at checkpoint time and the
# checkpoint succeeds out of the box. For the external-services variant
# (PostgreSQL + Redis) see bootui-spring-sample-app/README.md.
#
# Taking and restoring a checkpoint needs Linux kernel 5.9+ and the
# CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN/NET_ADMIN capabilities; see the sample README.
set -eu

CRAC_CHECKPOINT_DIR="${CRAC_CHECKPOINT_DIR:-/opt/crac/checkpoint}"
APP_JAR="${APP_JAR:-/app/app.jar}"

# JVM tuning flags (see Dockerfile-crac). Applied only when the checkpoint is created below; a
# restore (-XX:CRaCRestoreFrom) replays the checkpointed JVM, so heap/GC flags cannot be re-specified
# there. Empty by default so the script also works when run outside the image.
JAVA_OPTS="${JAVA_OPTS:-}"

# Run with the "dev" profile *active* so BootUI turns on (its activation condition
# inspects the active profiles, not spring.profiles.default) and the app uses the
# in-memory H2 database / cache. CRaC reads this when the checkpoint is taken (the
# first start) and freezes that value into the image; changing it for a later
# restore-only start has no effect until the checkpoint is regenerated.
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export SPRING_PROFILES_ACTIVE

mkdir -p "$CRAC_CHECKPOINT_DIR"

# A *complete* checkpoint is what matters, not merely a non-empty directory: a CRIU dump
# that fails halfway still leaves its dump log and partial images behind. CRIU writes
# inventory.img as the last step of a successful dump and requires it to restore, so its
# presence is the signal to use. Testing for a non-empty directory instead would make a
# failed dump look like a checkpoint - the app would then "restore" from a broken image,
# and a persistent checkpoint volume would stay poisoned on every later start.
checkpoint_is_complete() {
  [ -f "$CRAC_CHECKPOINT_DIR/inventory.img" ]
}

# CRIU writes the reason a dump failed to its own log file (dump*.log), not to stdout, so
# surface it: without this the container log only shows the JVM's generic
# "Native checkpoint failed".
print_criu_dump_log() {
  for criu_log in "$CRAC_CHECKPOINT_DIR"/dump*.log; do
    [ -f "$criu_log" ] || continue
    echo "[crac] ----- CRIU $criu_log (last 50 lines) -----" >&2
    tail -n 50 "$criu_log" >&2
  done
}

# Restore immediately when a previous, complete checkpoint is present.
if checkpoint_is_complete; then
  echo "[crac] Restoring the application from the checkpoint in $CRAC_CHECKPOINT_DIR"
  exec java -XX:CRaCRestoreFrom="$CRAC_CHECKPOINT_DIR"
fi

# Discard the remains of an earlier failed dump: CRIU refuses to write into a directory
# that already holds images, so leaving them would make every retry fail too.
if [ -n "$(ls -A "$CRAC_CHECKPOINT_DIR" 2>/dev/null)" ]; then
  echo "[crac] $CRAC_CHECKPOINT_DIR holds an incomplete checkpoint (no inventory.img); discarding it"
  rm -rf "${CRAC_CHECKPOINT_DIR:?}"/..?* "${CRAC_CHECKPOINT_DIR:?}"/.[!.]* "${CRAC_CHECKPOINT_DIR:?}"/* 2>/dev/null || true
fi

echo "[crac] No checkpoint found; starting the app to create one (spring.context.checkpoint=onRefresh)"
# CRIU kills the process once the checkpoint is written, so a non-zero exit code
# here is expected. Check for a complete image rather than the exit code to
# decide whether the checkpoint succeeded.
set +e
java $JAVA_OPTS -XX:CRaCCheckpointTo="$CRAC_CHECKPOINT_DIR" \
  -Dspring.context.checkpoint=onRefresh \
  -jar "$APP_JAR"
checkpoint_status=$?
set -e

if ! checkpoint_is_complete; then
  echo "[crac] Checkpoint creation failed (exit code $checkpoint_status): CRIU wrote no complete image." >&2
  print_criu_dump_log
  echo "[crac] CRIU needs Linux kernel 5.9+ and the CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN/NET_ADMIN capabilities." >&2
  echo "[crac] If you switched the app to PostgreSQL/Redis, an open connection at checkpoint time" >&2
  echo "[crac] aborts CRaC; keep the default H2 'dev' profile or see the README's external-services note." >&2
  # Never fall through to a restore: the image is incomplete and restoring it would fail
  # with a far more confusing error than the CRIU log printed above.
  [ "$checkpoint_status" -ne 0 ] || checkpoint_status=1
  exit "$checkpoint_status"
fi

echo "[crac] Checkpoint created in $CRAC_CHECKPOINT_DIR; restoring the application"
exec java -XX:CRaCRestoreFrom="$CRAC_CHECKPOINT_DIR"

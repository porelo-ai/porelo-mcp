# shellcheck shell=bash
# The one rule by which a pinned download is tried again, sourced by the steps of this action and by any script that
# fetches something pinned (shfmt, the wrapper's Maven, fd, ripgrep, Node): the same numbers as porelo-machines'
# PinnedRetry, which a test there holds this file to. The pauses double from 15 s, none longer than 5 min, until 15 min
# of pauses are spent, the last one cut to fit: 15 30 60 120 240 300 135, eight attempts in all. GitHub's release
# downloads answered 504 for over ten minutes on 2026-11-08.
# The 15 min are also a deadline from the start of the first attempt, the attempts' own time counted: no attempt starts
# later, and a pause is cut so the next one starts by then. A failing attempt took 2 min on 2026-11-08.
# The time is the system's uptime, which no caller resets and no clock step moves.
# pinned_fetch tries again only what may be better next time: a 5xx, or a connection that failed, timed out or broke
# off. A 4xx, a refused certificate, a full disk and a wrong digest fail at once. A file is checked against its sha256
# after every fetch and before it is taken from a cache. What the functions say goes to standard error.

pinned_attempts=8

pinned_now() { # whole seconds on a clock that only counts up; a test replaces it
    local up
    read -r up _ </proc/uptime || return 1
    echo "${up%%.*}"
}

pinned_pause() { # <attempt that failed, from 1> [<seconds since the first attempt started>]: the pause before the next
    # attempt, or a failure when none is to start
    local n=1 next=15 spent=0 elapsed=${2:-0} left
    [ "$elapsed" -ge 0 ] || elapsed=0
    left=$((900 - elapsed))
    [ "$1" -ge 1 ] && [ "$1" -lt "$pinned_attempts" ] && [ "$left" -gt 0 ] || return 1
    while [ "$n" -lt "$1" ]; do
        spent=$((spent + next))
        next=$((next * 2 > 300 ? 300 : next * 2))
        n=$((n + 1))
    done
    [ "$spent" -lt 900 ] || return 1
    [ $((spent + next)) -le 900 ] || next=$((900 - spent))
    [ "$next" -le "$left" ] || next=$left
    echo "$next"
}

pinned_sha256() { # <file>: its sha256
    sha256sum "$1" | cut -d' ' -f1
}

pinned_fetch() { # <url> <sha256> <file>: the file the url serves, checked; one already there that matches is kept
    local url=$1 sha256=$2 file=$3 attempt=1 part code exit wait start elapsed
    # A link is no cached file: it would hand over whatever it points at.
    if [ -f "$file" ] && [ ! -L "$file" ] && [ "$(pinned_sha256 "$file")" = "$sha256" ]; then
        echo "$file taken from the cache, checked against $sha256" >&2
        return 0
    fi
    rm -f "$file"
    mkdir -p "$(dirname "$file")" || return 1
    start=$(pinned_now) || return 1
    while :; do
        part=$(mktemp "$file.XXXXXX") || return 1
        exit=0
        code=$(curl -sS -L --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time 600 \
            -o "$part" -w '%{http_code}' "$url") || exit=$?
        case $exit:$code in
            0:200)
                if [ "$(pinned_sha256 "$part")" != "$sha256" ]; then
                    rm -f "$part"
                    echo "::error::$url has the digest of something else, not the pinned $sha256" >&2
                    return 1
                fi
                mv -f "$part" "$file" || { rm -f "$part"; return 1; }
                return 0
                ;;
            # A 5xx, or no answer: could not resolve or connect, timed out, an empty reply, a transfer broken off.
            0:5?? | 6:* | 7:* | 18:* | 28:* | 35:* | 52:* | 55:* | 56:*) ;;
            *)
                rm -f "$part"
                echo "::error::$url answered HTTP ${code:-000} (curl exit $exit)" >&2
                return 1
                ;;
        esac
        rm -f "$part"
        elapsed=$(($(pinned_now) - start))
        if ! wait=$(pinned_pause "$attempt" "$elapsed"); then
            echo "::error::$url answered HTTP ${code:-000} (curl exit $exit); given up after $attempt of at most" \
                "$pinned_attempts attempts in $elapsed s" >&2
            return 1
        fi
        echo "::warning::$url answered HTTP ${code:-000} (curl exit $exit) on attempt $attempt of $pinned_attempts;" \
            "trying again in $wait s" >&2
        sleep "$wait"
        attempt=$((attempt + 1))
    done
}

# For a fetch the script does not make itself and whose failure does not say why, such as the wrapper's Maven: every
# failure is tried again, by the same pauses. The command runs in a subshell of its own under set -e, outside any
# condition, so a function given stops at its first failing command; the caller's errexit is left as it was. Called as
# a condition itself (`if pinned_retry ...`, `pinned_retry ... || ...`), bash ignores set -e all the way down, so a
# function given there must chain its own steps with &&.
pinned_retry() { # <what> <command>...: runs the command until it succeeds
    local what=$1 attempt=1 wait start elapsed status errexit=
    shift
    start=$(pinned_now) || return 1
    case $- in *e*) errexit=1 ;; esac
    while :; do
        set +e
        (
            set -e
            "$@"
        )
        status=$?
        [ -z "$errexit" ] || set -e
        [ "$status" -ne 0 ] || return 0
        elapsed=$(($(pinned_now) - start))
        if ! wait=$(pinned_pause "$attempt" "$elapsed"); then
            echo "::error::$what failed; given up after $attempt of at most $pinned_attempts attempts in" \
                "$elapsed s" >&2
            return 1
        fi
        echo "::warning::$what failed (exit $status) on attempt $attempt of $pinned_attempts; trying again in" \
            "$wait s" >&2
        sleep "$wait"
        attempt=$((attempt + 1))
    done
}

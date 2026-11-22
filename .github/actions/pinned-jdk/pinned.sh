# shellcheck shell=bash
# The one rule by which a pinned download is tried again, sourced by the steps of this action and by any script that
# fetches something pinned (shfmt, the wrapper's Maven, fd, ripgrep, Node): the same numbers as porelo-pinned-rule's
# PinnedRetry, which a test in porelo-machines holds this file to. The pauses double from 15 s, none longer than 5 min,
# until 15 min of pauses are spent, the last one cut to fit: 15 30 60 120 240 300 135, eight attempts in all. GitHub's
# release downloads answered 504 for over ten minutes on 2026-11-08.
# The 15 min are also a deadline from the start of the first attempt, the attempts' own time counted: no attempt starts
# later, and a pause is cut so the next one starts by then. A failing attempt took 2 min on 2026-11-08.
# The time is the system's uptime, which no caller resets and no clock step moves.
# pinned_fetch tries again only what may be better next time: a 5xx, or a connection that failed, timed out or broke
# off. A 4xx, a refused certificate and a full disk are not tried again at the same source, and a wrong digest fails
# at once. A file is checked against its sha256
# after every fetch and before it is taken from a cache. What the functions say goes to standard error.
# A file the first source does not serve - a 5xx or no answer past 600 s, a 4xx, anything but a wrong digest - is
# fetched from the organization's Artifactory, pinned_mirror, by its digest and name:
# <mirror>/pinned/<sha256>/<file name>, without a token, until the 900 s are spent, and taken only when its digest
# matches. PORELO_PINNED_MIRROR names another mirror, an https address, or none when it is set empty; without a mirror
# the first source has the whole 900 s. An attempt runs no longer than its source's deadline leaves, at least 30 s.
# On GitHub, a download from https://github.com goes with the job's token, PORELO_PINNED_GITHUB_TOKEN, so it counts
# against the job's own limit: only when GITHUB_SERVER_URL is exactly https://github.com, the address is on it and the
# token has a token's form. The token is handed to curl on its standard input, never as an argument, and goes with the
# address asked for alone: the address it redirects to is fetched without it. A 401 or 403 is not tried again.

pinned_attempts=8
pinned_first_deadline=600
pinned_mirror=https://fuinorg.jfrog.io/artifactory/porelo-files

pinned_now() { # whole seconds on a clock that only counts up; a test replaces it
    local up
    read -r up _ </proc/uptime || return 1
    echo "${up%%.*}"
}

pinned_pause() { # <attempt that failed, from 1> [<seconds since the first attempt started>] [<deadline, 900>]: the
    # pause before the next attempt, or a failure when none is to start
    local n=1 next=15 spent=0 elapsed=${2:-0} deadline=${3:-900} left
    [ "$elapsed" -ge 0 ] || elapsed=0
    left=$((deadline - elapsed))
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

pinned_token_for() { # <url>: succeeds when the job's token goes with it
    [ "${GITHUB_SERVER_URL:-}" = https://github.com ] || return 1
    case $1 in https://github.com/*) ;; *) return 1 ;; esac
    # The whole value, not line by line: a second line would reach curl's configuration.
    [[ ${PORELO_PINNED_GITHUB_TOKEN:-} =~ ^[A-Za-z0-9_]{20,255}$ ]]
}

pinned_sha256() { # <file>: its sha256
    sha256sum "$1" | cut -d' ' -f1
}

pinned_fetch() { # <url> <sha256> <file>: the file the url serves, checked, or the mirror's copy of it when the url
    # fails; one already there that matches is kept
    local url=$1 sha256=$2 file=$3 mirror=${PORELO_PINNED_MIRROR-$pinned_mirror} name second= start status=0
    if [ -n "$mirror" ] && [[ ! $mirror =~ ^https://[^][:space:]@?#[]+$ ]]; then
        # Not echoed: a user or a query in the address could carry a secret into the log.
        echo "::error::PORELO_PINNED_MIRROR is no https:// address without whitespace, a user, a query or a fragment" >&2
        return 1
    fi
    # A link is no cached file: it would hand over whatever it points at.
    if [ -f "$file" ] && [ ! -L "$file" ] && [ "$(pinned_sha256 "$file")" = "$sha256" ]; then
        echo "$file taken from the cache, checked against $sha256" >&2
        return 0
    fi
    if [ -n "$mirror" ]; then
        name=${url%%[?#]*}
        name=${name##*/}
        if [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]]; then
            second=${mirror%/}/pinned/$sha256/$name
        else
            echo "::warning::$url names no file the mirror can hold; it is not asked" >&2
        fi
    fi
    rm -f "$file"
    mkdir -p "$(dirname "$file")" || return 1
    start=$(pinned_now) || return 1
    if [ -z "$second" ]; then
        pinned_from only "$url" "$sha256" "$file" "$start" || status=$?
        [ "$status" -eq 0 ] || return 1
        return 0
    fi
    pinned_from first "$url" "$sha256" "$file" "$start" || status=$?
    # 1 is a fault the mirror must not cover, such as a wrong digest; 2 is a source that did not serve the file.
    [ "$status" -eq 2 ] || return "$status"
    echo "::warning::$url did not serve the file; asking the mirror, $second" >&2
    status=0
    pinned_from mirror "$second" "$sha256" "$file" "$start" || status=$?
    [ "$status" -eq 0 ] || return 1
}

pinned_from() { # <only|first|mirror> <url> <sha256> <file> <start>: one source tried by the rule until its deadline;
    # 0 when the file is in place, 1 on a fault no other source mends, 2 when this source did not serve it
    local role=$1 url=$2 sha256=$3 file=$4 start=$5 attempt=1 deadline=900 level=error part code exit wait elapsed \
        answer next most
    case $role in
        first)
            deadline=$pinned_first_deadline
            level=warning
            ;;
    esac
    while :; do
        # An attempt ends by its source's deadline, with 30 s for one that starts on it.
        elapsed=$(($(pinned_now) - start))
        most=$((deadline - elapsed))
        [ "$most" -ge 30 ] || most=30
        [ "$most" -le 600 ] || most=600
        part=$(mktemp "$file.XXXXXX") || return 1
        exit=0
        next=$url
        # The mirror is read without a token, wherever it is.
        if [ "$role" != mirror ] && pinned_token_for "$url"; then
            answer=$(printf 'header = "Authorization: Bearer %s"\n' "$PORELO_PINNED_GITHUB_TOKEN" \
                | curl -sS --proto '=https' --connect-timeout 30 --max-time "$most" -K - -o "$part" \
                    -w '%{http_code} %{redirect_url}' "$url") || exit=$?
            code=${answer%% *}
            next=
            case $exit:$code in 0:301 | 0:302 | 0:303 | 0:307 | 0:308) next=${answer#* } ;; esac
        fi
        if [ -n "$next" ]; then
            code=$(curl -sS -L --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time "$most" \
                -o "$part" -w '%{http_code}' "$next") || exit=$?
        fi
        case $exit:$code in
            0:200)
                if [ "$(pinned_sha256 "$part")" != "$sha256" ]; then
                    rm -f "$part"
                    echo "::error::$url has the digest of something else, not the pinned $sha256" >&2
                    return 1
                fi
                mv -f "$part" "$file" || { rm -f "$part"; return 1; }
                echo "$file taken from $url, checked against $sha256" >&2
                return 0
                ;;
            # A 5xx, or no answer: could not resolve or connect, timed out, an empty reply, a transfer broken off.
            0:5?? | 6:* | 7:* | 18:* | 28:* | 35:* | 52:* | 55:* | 56:*) ;;
            *)
                rm -f "$part"
                echo "::$level::$url answered HTTP ${code:-000} (curl exit $exit)" >&2
                return 2
                ;;
        esac
        rm -f "$part"
        elapsed=$(($(pinned_now) - start))
        if ! wait=$(pinned_pause "$attempt" "$elapsed" "$deadline"); then
            echo "::$level::$url answered HTTP ${code:-000} (curl exit $exit); given up after $attempt of at most" \
                "$pinned_attempts attempts in $elapsed s" >&2
            return 2
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

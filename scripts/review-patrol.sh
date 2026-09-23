#!/usr/bin/env bash
# 常駐 review 巡邏（見 reviewer.md）。巡邏本身不用模型：只列 PR、比對 head。
# 每個還沒審過的 PR head 開一個全新行程的 reviewer，輸出原樣貼成 PR 留言。
#
#   scripts/review-patrol.sh            跑一輪
#   scripts/review-patrol.sh --loop     每 INTERVAL 秒一輪（預設 900）
#   DRY_RUN=1 scripts/review-patrol.sh  只印要審哪些 PR，不叫 reviewer、不留言
#
#   REVIEWER=codex|opencode|claude     預設 codex
#   REVIEWER_MODEL=<模型 ID>           不設就用該工具的預設模型
set -euo pipefail
# ponytail: 本機 /usr/local 的 git 是 x86（goal.md「環境事實」），arm64 的放前面
export PATH=/opt/homebrew/bin:/usr/bin:/bin:$PATH
cd "$(git rev-parse --show-toplevel)"
repo_root=$PWD
REVIEWER=${REVIEWER:-codex}
MAX_ROUNDS=2
MARK='<!-- senior-review'

run_reviewer() { # $1 工作目錄  $2 prompt 檔  $3 輸出檔
  case "$REVIEWER" in
    codex)    codex exec --sandbox workspace-write ${REVIEWER_MODEL:+-m "$REVIEWER_MODEL"} -C "$1" -o "$3" - < "$2" ;;
    opencode) (cd "$1" && opencode run ${REVIEWER_MODEL:+-m "$REVIEWER_MODEL"} "$(cat "$2")") > "$3" ;;
    claude)   (cd "$1" && claude -p --model "${REVIEWER_MODEL:-opus}" \
                --allowedTools "Read Grep Glob Bash(git:*) Bash(mvn:*)" < "$2") > "$3" ;;
    *) echo "未知 REVIEWER=$REVIEWER" >&2; return 2 ;;
  esac
}

review_pr() { # $1 PR 編號  $2 head sha  $3 輪次
  local pr=$1 head=$2 round=$3
  local wt; wt=$(mktemp -d "${TMPDIR:-/tmp}/gigaxfer-review-$pr.XXXX")
  git fetch -q origin "pull/$pr/head"
  git worktree add -q --detach "$wt" "$head"
  trap 'git -C "$repo_root" worktree remove --force "$wt" 2>/dev/null || true' RETURN

  # reviewer 不需要 GitHub 權限：材料先放進拋棄式 worktree 的 .review/
  mkdir -p "$wt/.review"
  gh pr view "$pr" > "$wt/.review/pr.md"
  gh pr diff "$pr" > "$wt/.review/diff.patch"
  gh pr view "$pr" --json reviews,comments \
    --jq '[.reviews[], .comments[]] | sort_by(.submittedAt // .createdAt) | .[] | "---- \(.author.login) \(.submittedAt // .createdAt)\n\(.body)\n"' \
    > "$wt/.review/history.md"
  local ticket; ticket=$(gh pr view "$pr" --json body --jq '.body | capture("(?i)closes #(?<n>[0-9]+)").n' 2>/dev/null || true)
  [ -n "$ticket" ] && gh issue view "$ticket" > "$wt/.review/ticket.md"

  cat > "$wt/.review/prompt.md" <<PROMPT
你是這個 repo 的 senior reviewer，審 PR #$pr 第 $round 輪，head $head。
目前目錄是這個 head 的拋棄式 checkout。不要 commit、不要 push、不要改原始碼；跑測試產生的建置檔可以。

1. 讀 reviewer.md 的「審查標準」與「留言格式」兩節，照做。
2. 材料在 .review/：pr.md（PR 本文）、diff.patch、ticket.md（驗收條件，可能不存在）、history.md（歷次 review 與回覆）。
   另讀 PR 引用的 validation 檔與相關設計文件：docs/spec.md、docs/design/system-design.md、docs/design/design-decisions.md。
   不要讀 ledger、progress.md 或 docs/reports/。
3. 第 2 輪起：從 history.md 找上一則 senior review 與作者之後的回覆，逐條確認上一輪 finding 是否真的修好，再審新增的 diff。
   作者的說明只當線索，以程式與測試為準。
4. 需要跑測試時，先照 goal.md「環境事實與修法」的 export 設好 Java 與 Maven，Maven 用 -o 離線模式。
5. 你的最後一則訊息就是要貼到 PR 的留言本文，從「**Senior review 第 $round 輪**」那行開始，最後一行是 VERDICT。
PROMPT

  local out="$wt/.review/out.md"
  run_reviewer "$wt" "$wt/.review/prompt.md" "$out"
  {
    echo "$MARK round: $round; head: $head; reviewer: $REVIEWER${REVIEWER_MODEL:+/$REVIEWER_MODEL} -->"
    cat "$out"
    if ! grep -qxE 'VERDICT: (CLEAN|CHANGES|DESIGN)' <(grep -v '^[[:space:]]*$' "$out" | tail -1); then
      printf '\n（巡邏腳本：reviewer 輸出的最後一行不是 VERDICT，依規則視為 CHANGES）\n\nVERDICT: CHANGES\n'
    fi
  } | gh pr review "$pr" --comment --body-file -
  echo "PR #$pr 第 $round 輪已留言"
}

patrol_once() {
  local prs; prs=$(gh pr list --state open --json number,headRefOid,isDraft --jq '.[] | select(.isDraft|not) | "\(.number) \(.headRefOid)"')
  [ -z "$prs" ] && { echo "沒有 open PR"; return; }
  while read -r pr head; do
    local bodies; bodies=$(gh pr view "$pr" --json reviews | jq --arg m "$MARK" '[.reviews[].body | select(startswith($m))]')
    local rounds; rounds=$(jq length <<<"$bodies")
    if jq -e --arg h "head: $head;" 'any(.[]; contains($h))' <<<"$bodies" >/dev/null; then
      echo "PR #$pr head ${head:0:7} 已審過"; continue
    fi
    if [ "$rounds" -ge "$MAX_ROUNDS" ]; then
      if ! gh pr view "$pr" --json comments --jq '.comments[].body' | grep -q 'senior-review-cap'; then
        [ -n "${DRY_RUN:-}" ] || gh pr comment "$pr" --body "<!-- senior-review-cap -->
Senior review 已達 $MAX_ROUNDS 輪上限，之後的 commit 不再自動審，交由人決定。"
      fi
      echo "PR #$pr 已達 $MAX_ROUNDS 輪上限"; continue
    fi
    if [ -n "${DRY_RUN:-}" ]; then
      echo "PR #$pr head ${head:0:7} 待審，第 $((rounds + 1)) 輪（DRY_RUN）"; continue
    fi
    review_pr "$pr" "$head" "$((rounds + 1))"
  done <<<"$prs"
}

if [ "${1:-}" = "--loop" ]; then
  while true; do patrol_once || echo "本輪失敗，下輪重試" >&2; sleep "${INTERVAL:-900}"; done
else
  patrol_once
fi

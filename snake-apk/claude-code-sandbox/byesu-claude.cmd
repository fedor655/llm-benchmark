@echo off
rem Claude Code on the byesu.com API, isolated profile + guard hook.
rem Your main Claude Code profile (~/.claude) is not touched.
setlocal
set "ROOT=C:\Users\semin\byesu-claude"
set "CLAUDE_CONFIG_DIR=%ROOT%\config"
set "ANTHROPIC_BASE_URL=https://byesu.com"
set "ANTHROPIC_AUTH_TOKEN=sk-YOUR-KEY-HERE"
set "ANTHROPIC_API_KEY="
set "ANTHROPIC_MODEL=claude-opus-5-5"
set "ANTHROPIC_DEFAULT_OPUS_MODEL=claude-opus-5-5"
set "ANTHROPIC_DEFAULT_SONNET_MODEL=claude-sonnet-5-5"
set "ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-4-5"
set "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
set "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR=1"
cd /d "%ROOT%\workspace"
claude %*
endlocal

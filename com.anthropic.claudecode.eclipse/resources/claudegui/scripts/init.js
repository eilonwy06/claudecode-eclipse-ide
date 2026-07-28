/* Boot sequence — the top-level statements that used to run inline, in their
   ORIGINAL relative order. Runs last, after every declaration file has loaded.
   buildActionsSlash stays last (it needs SLASH_COMMANDS). */

initModelConfig();
createTab();
updateCtxChip();
setEffort(effortIdx);
updateThinkingCheck();

/* Populate the actions-menu slash list (needs SLASH_COMMANDS from slash.js). */
buildActionsSlash();

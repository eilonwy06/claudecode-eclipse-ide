/* Boot sequence — the top-level statements that used to run inline, in their
   ORIGINAL relative order. Runs last, after every declaration file has loaded.
   buildActionsSlash stays last (it needs SLASH_COMMANDS). */

initModelConfig();
/* Before createTab: the workspace root has to exist for the first conversation to
   belong to, and #tabs renders only the ACTIVE root's tabs. */
initRoots();
createTab();
updateCtxChip();
setEffort(effortIdx);
updateThinkingCheck();

/* Populate the actions-menu slash list (needs SLASH_COMMANDS from slash.js). */
buildActionsSlash();

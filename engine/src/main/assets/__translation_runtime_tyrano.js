  // Engine-specific source module inserted into __translation_runtime.js by WebTranslationRuntime.
  root.__TRS_ENGINE__ = 'Tyrano';
  function patchTyrano() {
    var kag = root.TYRANO && root.TYRANO.kag;
    var tags = kag && kag.tag;
    var tagNames = ['text', 'p', 'glink', 'button'];
    var i;
    if (!tags) return;
    for (i = 0; i < tagNames.length; i++) {
      wrapTyranoTag(tags[tagNames[i]]);
    }
  }

  function wrapTyranoTag(tag) {
    if (!tag || !tag.start || tag.start.__trsWrapped) return;
    var original = tag.start;
    var wrapped = function (pm) { return original.call(this, translateArgs(pm, 0)); };
    Object.defineProperty(wrapped, '__trsWrapped', {value: true});
    tag.start = wrapped;
    stats.installed++;
  }
  function installSelectedEngine() { patchTyrano(); }
  install();

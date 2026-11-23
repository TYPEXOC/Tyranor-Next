  // Engine-specific source module inserted into __translation_runtime.js by WebTranslationRuntime.
  root.__TRS_ENGINE__ = 'Tyrano';
  function patchTyrano() {
    var kag = root.TYRANO && root.TYRANO.kag;
    if (!kag) return;
    if (typeof kag.on === 'function') {
      if (kag.__trsTranslationEventsInstalled) return;
      try {
        kag.on('tag-text-message.__tyranor_translation', function (event) {
          translateTyranoParameter(event, 'val', 'tyrano-message');
        }, {priority: 10000});
        kag.on(
          'tag-glink.__tyranor_translation tag-ptext.__tyranor_translation tag-button.__tyranor_translation',
          function (event) {
            translateTyranoParameter(event, 'text', 'tyrano-' + event.name.replace(/^tag-/, ''));
          },
          {priority: 10000}
        );
        kag.__trsTranslationEventsInstalled = true;
        stats.installed += 4;
        return;
      } catch (_) {}
    }

    // Older Tyrano builds may not expose the public event bus. In that case
    // wrap the live master tags (ftag.init clones kag.tag before the first scene).
    var tags = kag.ftag && kag.ftag.master_tag ? kag.ftag.master_tag : kag.tag;
    if (!tags) return;
    wrapTyranoTag(tags.text, 'val', 'tyrano-message');
    wrapTyranoTag(tags.glink, 'text', 'tyrano-glink');
    wrapTyranoTag(tags.ptext, 'text', 'tyrano-ptext');
    wrapTyranoTag(tags.button, 'text', 'tyrano-button');
  }

  function translateTyranoParameter(event, field, site) {
    var target = event && event.target;
    if (!target || typeof target[field] !== 'string') return;
    target[field] = field === 'val'
      ? translateMessageBlock(target[field], site)
      : tr(target[field], site);
  }

  function wrapTyranoTag(tag, field, site) {
    if (!tag || !tag.start || tag.start.__trsWrapped) return;
    var original = tag.start;
    var wrapped = function (pm) {
      if (pm && typeof pm[field] === 'string') {
        pm[field] = field === 'val' ? translateMessageBlock(pm[field], site) : tr(pm[field], site);
      }
      return original.call(this, pm);
    };
    Object.defineProperty(wrapped, '__trsWrapped', {value: true});
    tag.start = wrapped;
    stats.installed++;
  }
  function installSelectedEngine() { patchTyrano(); }
  install();

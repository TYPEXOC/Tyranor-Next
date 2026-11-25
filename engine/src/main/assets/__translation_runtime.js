// __translation_runtime.js — MTool-export-format translation runtime for Tyranor-Next web hosts.
// Idempotent (window.__TRS_RUNTIME__ latch); installs lookup hooks on a
// 500ms interval and exposes window.__TRS_SET_TRANSLATIONS__ / __TRS_STATUS__.
window.__TRS_WRITEBACK__ = true;

(function () {
  var root = window;
  if (root.__TRS_RUNTIME__) return;
  if (typeof root.__TRS_WRITEBACK__ === 'undefined') root.__TRS_WRITEBACK__ = true;
  var originalTextRecords = new WeakMap();
  var patched = [];
  var seen = {};
  var map = {};
  var targets = {};
  var lineToBlock = {};
  var domObserver = null;
  var stats = {installed: 0, replacements: 0};
  var missSamples = [];
  function hasOwn(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
  }

  // The extractor exports one key per message box instead of one per line, so a
  // 3-line box is a single multi-line key and its individual lines are not in
  // the map. MV splits that box again when it draws each line, so a per-line
  // lookup would miss. This index maps a line back to its block, which lets the
  // block translation be sliced by line number.
  function rebuildLineIndex() {
    lineToBlock = {};
    var key;
    var block;
    var text;
    var lines;
    var i;
    var lineKey;
    for (key in map) {
      if (!hasOwn(map, key)) continue;
      text = key.slice(1);
      if (text.indexOf('\n') === -1) continue;
      lines = text.split('\n');
      for (i = 0; i < lines.length; i++) {
        lineKey = '$' + lines[i];
        if (lines[i] && !hasOwn(lineToBlock, lineKey)) {
          lineToBlock[lineKey] = {block: key, index: i, count: lines.length};
        }
        // Some draw paths trim the line first; register that spelling too so a
        // trimmed lookup still resolves to the right slice.
        var trimmed = lines[i].trim();
        if (trimmed && trimmed !== lines[i] && !hasOwn(lineToBlock, '$' + trimmed)) {
          lineToBlock['$' + trimmed] = {block: key, index: i, count: lines.length};
        }
      }
    }
  }

  function blockLineText(value) {
    var ref = hasOwn(lineToBlock, '$' + value) ? lineToBlock['$' + value] : null;
    if (!ref) return undefined;
    var blockText = hasOwn(map, ref.block) ? map[ref.block] : undefined;
    if (typeof blockText !== 'string' || blockText === ref.block.slice(1)) return undefined;
    var parts = blockText.split('\n');
    // A translation that changed the line count cannot be sliced reliably;
    // leave it to the whole-box path instead of returning a wrong fragment.
    if (parts.length !== ref.count) return undefined;
    var piece = parts[ref.index];
    if (typeof piece !== 'string' || !piece) return undefined;
    return piece;
  }

  function record(value, site) {
    if (typeof value !== 'string' || value.length < 2 || !/\S/.test(value) || hasOwn(targets, '$' + value)) return;
    var key = '$' + site + '\u0000' + value;
    if (hasOwn(seen, key)) return;
    seen[key] = true;
    try {
      if (typeof root.__trsRecordCandidate === 'function') {
        root.__trsRecordCandidate(JSON.stringify({original: value, site: site}));
      }
    } catch (_) {}
  }

  function tr(value, site) {
    if (typeof value !== 'string' || !value) return value;
    // A string that is already a translated value must not be translated a
    // second time: one hook may translate before another hook sees the text.
    if (hasOwn(targets, '$' + value) && !hasOwn(map, '$' + value)) return value;
    record(value, site || 'render');
    var translated = hasOwn(map, '$' + value) ? map['$' + value] : undefined;
    // convertEscapeCharacters rewrites \C[1] into ESC C[1] before drawing.
    // Hooks downstream of it still need to find the exported source key.
    if (translated === undefined && value.indexOf('\u001b') !== -1) {
      var plain = value.replace(/\u001b/g, '\\');
      if (hasOwn(map, '$' + plain)) translated = map['$' + plain];
    }
    if (translated === undefined) {
      // Fall back to the message box this line belongs to. The box key is what
      // the translator actually edited, so its translation carries this line.
      translated = blockLineText(value);
      if (typeof translated === 'string') stats.blockLines = (stats.blockLines || 0) + 1;
    }
    if (typeof translated === 'string' && translated !== value) {
      stats.replacements++;
      return translated;
    }
    // Diagnostics: keep a few samples of text that reached a hook but found no
    // entry in the map. This is what tells "wrong draw path" apart from
    // "key mismatch" without guessing.
    if (translated === undefined) {
      stats.misses = (stats.misses || 0) + 1;
      if (missSamples.length < 6 && !/\u001b/.test(value)) {
        missSamples.push((site || 'render') + ' | ' + value.slice(0, 48));
      }
    }
    return value;
  }

  function translateMessageBlock(value, site) {
    if (typeof value !== 'string') return value;
    var wholeTranslation = tr(value, site || 'rpg-message');
    if (wholeTranslation !== value) return wholeTranslation;
    return value.replace(/[^\r\n]+/g, function (line) { return tr(line, site || 'rpg-message'); });
  }

  function translateWithMap(value, lookupMap, lookupLineIndex) {
    if (typeof value !== 'string' || !value) return value;
    var translated = hasOwn(lookupMap, '$' + value) ? lookupMap['$' + value] : undefined;
    if (translated === undefined && value.indexOf('\u001b') !== -1) {
      var plain = value.replace(/\u001b/g, '\\');
      if (hasOwn(lookupMap, '$' + plain)) translated = lookupMap['$' + plain];
    }
    if (translated === undefined && lookupLineIndex) {
      var ref = hasOwn(lookupLineIndex, '$' + value) ? lookupLineIndex['$' + value] : null;
      if (ref && hasOwn(lookupMap, ref.block)) {
        var blockText = lookupMap[ref.block];
        if (typeof blockText === 'string' && blockText !== ref.block.slice(1)) {
          var parts = blockText.split('\n');
          if (parts.length === ref.count && typeof parts[ref.index] === 'string' && parts[ref.index]) {
            translated = parts[ref.index];
          }
        }
      }
    }
    return typeof translated === 'string' ? translated : value;
  }

  function messageBlockWithMap(value, lookupMap, lookupLineIndex) {
    if (typeof value !== 'string') return value;
    var wholeTranslation = translateWithMap(value, lookupMap, lookupLineIndex);
    if (wholeTranslation !== value) return wholeTranslation;
    return value.replace(/[^\r\n]+/g, function (line) {
      return translateWithMap(line, lookupMap, lookupLineIndex);
    });
  }

  function hashText(value) {
    var hash = 0;
    var i;
    for (i = 0; i < value.length; i++) hash = ((hash << 5) - hash + value.charCodeAt(i)) | 0;
    return ('00000000' + (hash >>> 0).toString(16)).slice(-8);
  }

  function summarizeText(value) {
    value = typeof value === 'string' ? value : '';
    var previewLength = 32;
    return {
      length: value.length,
      hash: hashText(value),
      head: value.slice(0, previewLength).replace(/\r/g, '\\r').replace(/\n/g, '\\n'),
      tail: value.length > previewLength
        ? value.slice(-previewLength).replace(/\r/g, '\\r').replace(/\n/g, '\\n')
        : ''
    };
  }

  function lookupWithMap(value, lookupMap, lookupLineIndex) {
    if (typeof value !== 'string' || !value) {
      return {translated: value, mode: 'miss', key: null, lineIndex: -1};
    }
    var mapKey = '$' + value;
    var plain;
    var ref;
    var blockText;
    var parts;
    if (hasOwn(lookupMap, mapKey)) {
      return {translated: lookupMap[mapKey], mode: 'exact', key: value, lineIndex: -1};
    }
    if (value.indexOf('\u001b') !== -1) {
      plain = value.replace(/\u001b/g, '\\');
      if (hasOwn(lookupMap, '$' + plain)) {
        return {translated: lookupMap['$' + plain], mode: 'escape-normalized', key: plain, lineIndex: -1};
      }
    }
    ref = lookupLineIndex && hasOwn(lookupLineIndex, mapKey) ? lookupLineIndex[mapKey] : null;
    if (ref && hasOwn(lookupMap, ref.block)) {
      blockText = lookupMap[ref.block];
      if (typeof blockText === 'string' && blockText !== ref.block.slice(1)) {
        parts = blockText.split('\n');
        if (parts.length === ref.count && typeof parts[ref.index] === 'string' && parts[ref.index]) {
          return {
            translated: parts[ref.index],
            mode: 'line-index',
            key: ref.block.slice(1),
            lineIndex: ref.index
          };
        }
      }
    }
    return {translated: value, mode: 'miss', key: null, lineIndex: -1};
  }

  function summarizeLookupHit(source, result) {
    return {
      source: summarizeText(source),
      mode: result.mode,
      key: result.key === null ? null : summarizeText(result.key),
      target: summarizeText(result.translated),
      lineIndex: result.lineIndex,
      changed: result.translated !== source
    };
  }

  function summarizeMessageLookup(value, lookupMap, lookupLineIndex) {
    var whole = lookupWithMap(value, lookupMap, lookupLineIndex);
    var output = value;
    var matches = [];
    var matchedKeys = [];
    if (whole.translated !== value) {
      output = whole.translated;
      matches.push(summarizeLookupHit(value, whole));
      if (whole.key !== null) matchedKeys.push(whole.key);
    } else {
      output = value.replace(/[^\r\n]+/g, function (line) {
        var result = lookupWithMap(line, lookupMap, lookupLineIndex);
        if (matches.length < 4) {
          matches.push(summarizeLookupHit(line, result));
          if (result.key !== null) matchedKeys.push(result.key);
        }
        return result.translated;
      });
    }
    return {
      source: summarizeText(value),
      output: summarizeText(output),
      outputText: output,
      matches: matches,
      matchedKeys: matchedKeys
    };
  }

  function sameMatchedKeys(left, right) {
    if (!left || !right) return null;
    var i;
    if (left.matchedKeys.length !== right.matchedKeys.length) return false;
    for (i = 0; i < left.matchedKeys.length; i++) {
      if (left.matchedKeys[i] !== right.matchedKeys[i]) return false;
    }
    return true;
  }

  function summarizeMapChanges(previousMap, currentMap) {
    previousMap = previousMap || {};
    currentMap = currentMap || {};
    var changed = {};
    var entries = [];
    var count = 0;
    var key;
    var recordChange = function (mapKey) {
      var marker = '$' + mapKey;
      if (hasOwn(changed, marker)) return;
      changed[marker] = true;
      count++;
      if (entries.length < 4) {
        entries.push({
          key: summarizeText(mapKey.slice(1)),
          previousTarget: hasOwn(previousMap, mapKey) ? summarizeText(previousMap[mapKey]) : null,
          currentTarget: hasOwn(currentMap, mapKey) ? summarizeText(currentMap[mapKey]) : null
        });
      }
    };
    for (key in previousMap) {
      if (!hasOwn(previousMap, key)) continue;
      if (!hasOwn(currentMap, key) || previousMap[key] !== currentMap[key]) recordChange(key);
    }
    for (key in currentMap) {
      if (!hasOwn(currentMap, key)) continue;
      if (!hasOwn(previousMap, key) || previousMap[key] !== currentMap[key]) recordChange(key);
    }
    return {count: count, entries: entries};
  }

  function translateArgs(value, depth) {
    if (typeof value === 'string') return tr(value, 'render-argument');
    if (!value || typeof value !== 'object' || depth > 4) return value;
    var index;
    var key;
    var out;
    if (Array.isArray(value)) {
      out = [];
      for (index = 0; index < value.length; index++) out[index] = translateArgs(value[index], depth + 1);
      return out;
    }
    out = {};
    for (key in value) {
      if (hasOwn(value, key)) out[key] = translateArgs(value[key], depth + 1);
    }
    return out;
  }

  function isPatched(fn) {
    return patched.indexOf(fn) !== -1;
  }

  function wrapMethod(proto, name, stringIndexes) {
    if (!proto || typeof proto[name] !== 'function' || isPatched(proto[name])) return;
    var original = proto[name];
    var wrapped = function () {
      var args = Array.prototype.slice.call(arguments);
      var index;
      for (index = 0; index < stringIndexes.length; index++) {
        args[stringIndexes[index]] = translateArgs(args[stringIndexes[index]], 0);
      }
      return original.apply(this, args);
    };
    try {
      Object.defineProperty(wrapped, '__trsOriginal', {value: original});
      proto[name] = wrapped;
      patched.push(wrapped);
      stats.installed++;
    } catch (_) {}
  }

  function patchCanvas() {
    var proto = root.CanvasRenderingContext2D && root.CanvasRenderingContext2D.prototype;
    if (!proto) return;
    wrapMethod(proto, 'fillText', [0]);
    wrapMethod(proto, 'strokeText', [0]);
    wrapMethod(proto, 'measureText', [0]);
  }

  var HOOK_SITES = [];

  // Arg0 wrapping: replace the first argument of a method with the
  // looked-up translation and then call the original.
  function wrapArg0(owner, name, site) {
    if (!owner || typeof owner[name] !== 'function') return false;
    var original = owner[name];
    if (isPatched(original)) return true;
    var wrapped = function () {
      if (arguments.length) {
        var value = arguments[0];
        if (typeof value === 'string' && value) {
          var next = tr(value, site);
          if (next !== value) arguments[0] = next;
        }
      }
      return original.apply(this, arguments);
    };
    try {
      Object.defineProperty(wrapped, '__trsOriginal', {value: original});
      owner[name] = wrapped;
      patched.push(wrapped);
      stats.installed++;
      HOOK_SITES.push(site);
      return true;
    } catch (_) {
      return false;
    }
  }

  // Some engine entry points return the string instead of taking it
  // (Window_Command.commandName, TextManager getters). Those need the result
  // wrapped, otherwise the drawn text is produced after every argument hook.
  function wrapResult(owner, name, site) {
    if (!owner || typeof owner[name] !== 'function') return false;
    var original = owner[name];
    if (isPatched(original)) return true;
    var wrapped = function () {
      var value = original.apply(this, arguments);
      return typeof value === 'string' ? tr(value, site) : value;
    };
    try {
      Object.defineProperty(wrapped, '__trsOriginal', {value: original});
      owner[name] = wrapped;
      patched.push(wrapped);
      stats.installed++;
      HOOK_SITES.push(site);
      return true;
    } catch (_) {
      return false;
    }
  }

  /*__TRS_ENGINE_HOOKS__*/

  function originalTextFor(node) {
    var record = originalTextRecords.get(node);
    if (!record) {
      record = {source: node.nodeValue, lastOutput: node.nodeValue};
      originalTextRecords.set(node, record);
    } else if (node.nodeValue !== record.lastOutput) {
      // The game reused this text node for new content; translate from that new source.
      record.source = node.nodeValue;
    }
    return record;
  }

  function visitNode(node) {
    if (!node) return;
    if (node.nodeType === 3) {
      var record = originalTextFor(node);
      var translated = tr(record.source, 'dom-text');
      // Set before writing so the observer can distinguish our output from game text.
      record.lastOutput = translated;
      if (node.nodeValue !== translated) {
        node.nodeValue = translated;
      }
      return;
    }
    if (node.nodeType !== 1) return;
    var attrs = ['title', 'alt', 'placeholder', 'aria-label', 'value'];
    var i;
    var attr;
    var key;
    var translatedAttr;
    for (i = 0; i < attrs.length; i++) {
      attr = attrs[i];
      if (!node.hasAttribute(attr)) continue;
      key = '__trs_original_' + attr;
      if (!node.hasAttribute(key)) node.setAttribute(key, node.getAttribute(attr));
      translatedAttr = tr(node.getAttribute(key), 'dom-attribute:' + attr);
      if (node.getAttribute(attr) !== translatedAttr) node.setAttribute(attr, translatedAttr);
    }
    for (i = 0; i < node.childNodes.length; i++) visitNode(node.childNodes[i]);
  }

  function patchDOM() {
    if (!root.document || !root.document.documentElement) return;
    visitNode(root.document.documentElement);
    if (!domObserver && root.MutationObserver) {
      domObserver = new root.MutationObserver(function (records) {
        var i;
        var j;
        var recordItem;
        for (i = 0; i < records.length; i++) {
          recordItem = records[i];
          if (recordItem.type === 'characterData') visitNode(recordItem.target);
          for (j = 0; recordItem.addedNodes && j < recordItem.addedNodes.length; j++) {
            visitNode(recordItem.addedNodes[j]);
          }
        }
      });
      domObserver.observe(root.document.documentElement, {subtree: true, childList: true, characterData: true});
      stats.installed++;
    }
  }

  function install() {
    patchCanvas();
    patchDOM();
    if (typeof installSelectedEngine === 'function') installSelectedEngine();
  }
  function describeRuntime() {
    var runtime = {
      engine: root.__TRS_ENGINE__ || 'Web game',
      bootstrap: root.__TRS_BOOTSTRAP_MODE__ || 'host-injection',
      title: (root.document && root.document.title) || ''
    };
    if (typeof describeEngineRuntime === 'function') {
      var engineRuntime = describeEngineRuntime();
      if (engineRuntime) Object.assign(runtime, engineRuntime);
    }
    return runtime;
  }

  root.__TRS_SET_TRANSLATIONS__ = function (next) {
    var source;
    var target;
    var previousMap = map;
    var previousLineIndex = lineToBlock;
    var mapChanges;
    map = {};
    targets = {};
    next = next || {};
    for (source in next) {
      if (!hasOwn(next, source)) continue;
      target = next[source];
      if (typeof target !== 'string') continue;
      map['$' + source] = target;
      targets['$' + target] = true;
    }
    rebuildLineIndex();
    mapChanges = summarizeMapChanges(previousMap, map);
    if (typeof beforeTranslationUpdate === 'function') {
      beforeTranslationUpdate(previousMap, previousLineIndex);
    }
    install();
    patchDOM();
    var result = {
      pairs: Object.keys(map).length,
      mapChanges: mapChanges,
      hooks: stats.installed,
      replacements: stats.replacements,
      runtime: describeRuntime()
    };
    if (typeof translationUpdateDiagnostics === 'function') {
      Object.assign(result, translationUpdateDiagnostics(previousMap, previousLineIndex));
    }
    return result;
  };
  root.__TRS_RUNTIME__ = true;
  root.__TRS_STATUS__ = function () {
    var status = {
      replacements: stats.replacements,
      hooks: stats.installed,
      pairs: Object.keys(map).length,
      unmatched: stats.misses || 0,
      samples: missSamples.slice(0, 6),
      runtime: describeRuntime()
    };
    if (typeof engineStatus === 'function') Object.assign(status, engineStatus());
    return status;
  };
  // In virtual-plugin mode the host may queue a map before PluginManager loads
  // this script. Apply that map once the public setter is ready.
  if (hasOwn(root, '__TRS_PENDING_TRANSLATIONS__')) {
    var pendingTranslations = root.__TRS_PENDING_TRANSLATIONS__;
    try {
      delete root.__TRS_PENDING_TRANSLATIONS__;
      root.__TRS_SET_TRANSLATIONS__(pendingTranslations);
    } catch (pendingError) {
      root.__TRS_PENDING_TRANSLATIONS__ = pendingTranslations;
    }
  }
  install();
  root.setInterval(install, 500);
})();

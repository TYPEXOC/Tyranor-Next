// __mtool_translator.js — MTool-export-format translation runtime for Tyranor-Next web hosts.
// Provenance: exported from MTOOL 多引擎独立工具 cdp_runtime.BOOTSTRAP (writeback=true).
// Idempotent (window.__MTOOL_RUNTIME_TRANSLATOR__ latch); installs lookup hooks on a
// 500ms interval and exposes window.__MTOOL_SET_TRANSLATIONS__ / __MTOOL_STATUS__.
// Regenerate with the same export path when the source tool changes.
window.__MTOOL_WRITEBACK__ = true;

(function () {
  var root = window;
  if (root.__MTOOL_RUNTIME_TRANSLATOR__) return;
  if (typeof root.__MTOOL_WRITEBACK__ === 'undefined') root.__MTOOL_WRITEBACK__ = true;
  var originalNodes = [];
  var originalValues = [];
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
      if (typeof root.__mtoolRecordCandidate === 'function') {
        root.__mtoolRecordCandidate(JSON.stringify({original: value, site: site}));
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

  function messageBlockHasTranslation(value) {
    var found = false;
    if (typeof value !== 'string') return false;
    var wholeKey = '$' + value;
    if (hasOwn(map, wholeKey) && typeof map[wholeKey] === 'string' && map[wholeKey] !== value) return true;
    value.replace(/[^\r\n]+/g, function (line) {
      var key = '$' + line;
      if (hasOwn(map, key) && typeof map[key] === 'string' && map[key] !== line) found = true;
      return line;
    });
    return found;
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
      Object.defineProperty(wrapped, '__mtoolOriginal', {value: original});
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

  // MTool's MV/MZ loader replaces the first argument of a method with the
  // looked-up translation and then calls the original. This mirrors the
  // `arguments[0] = wch.fixInStrToTrsNR(arguments[0])` template.
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
      Object.defineProperty(wrapped, '__mtoolOriginal', {value: original});
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
      Object.defineProperty(wrapped, '__mtoolOriginal', {value: original});
      owner[name] = wrapped;
      patched.push(wrapped);
      stats.installed++;
      HOOK_SITES.push(site);
      return true;
    } catch (_) {
      return false;
    }
  }

  // Every TextManager accessor is a getter over $dataSystem.terms/words
  // (TextManager.newGame -> terms.commands[0]). Wrapping the getters keeps the
  // title menu, menu labels and messages correct even when the game reads the
  // term before the writeback pass has run.
  function patchTextManager() {
    var manager = root.TextManager;
    if (!manager) return;
    var names;
    try { names = Object.getOwnPropertyNames(manager); } catch (_) { return; }
    for (var i = 0; i < names.length; i++) {
      var name = names[i];
      var descriptor;
      try { descriptor = Object.getOwnPropertyDescriptor(manager, name); } catch (_) { continue; }
      if (!descriptor || typeof descriptor.get !== 'function' || !descriptor.configurable) continue;
      if (descriptor.get.__mtoolWrapped) continue;
      var getter = descriptor.get;
      var wrapped = (function (label, source) {
        return function () {
          var value = source.call(this);
          return typeof value === 'string' ? tr(value, 'textmanager:' + label) : value;
        };
      })(name, getter);
      try {
        Object.defineProperty(wrapped, '__mtoolWrapped', {value: true});
        Object.defineProperty(manager, name, {
          configurable: true,
          enumerable: descriptor.enumerable,
          get: wrapped,
          set: descriptor.set
        });
        stats.installed++;
        HOOK_SITES.push('textmanager:' + name);
      } catch (_) {}
    }
    // The accessor flavours (TextManager.basic(1), .command(0), ...) return the
    // string instead of being getters, so they need the result wrapper.
    var methods = ['basic', 'param', 'command', 'message', 'partyMember'];
    for (var m = 0; m < methods.length; m++) {
      wrapResult(manager, methods[m], 'textmanager:' + methods[m]);
    }
  }

  // Point 7: PIXI.Text.updateText reads this._text, so the text field itself
  // has to be rewritten before the glyph atlas is rebuilt.
  function patchPixiUpdateText() {
    var pixiText = root.PIXI && root.PIXI.Text;
    var proto = pixiText && pixiText.prototype;
    if (!proto || typeof proto.updateText !== 'function' || isPatched(proto.updateText)) return;
    var original = proto.updateText;
    var wrapped = function () {
      if (typeof this._text === 'string' && this._text) {
        var next = tr(this._text, 'pixi-updateText');
        if (next !== this._text) this._text = next;
      }
      return original.apply(this, arguments);
    };
    try {
      Object.defineProperty(wrapped, '__mtoolOriginal', {value: original});
      proto.updateText = wrapped;
      patched.push(wrapped);
      stats.installed++;
      HOOK_SITES.push('pixi-updateText');
    } catch (_) {}
  }

  // Points 2/3: TAIMA registers Sprite_Name / Sprite_EventName style classes
  // on window at plugin load time, so they are matched by constructor name.
  var nameSpriteTick = 0;
  function patchNameSprites() {
    var pattern = /(Sprite_?(Name|EventName)|(Name|EventName)Sprite|EventNameText|nameSprite)/i;
    var keys;
    try { keys = Object.keys(root); } catch (_) { return; }
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var value;
      try { value = root[key]; } catch (_) { continue; }
      if (typeof value !== 'function' || !value.prototype) continue;
      if (!pattern.test(key) && !pattern.test(value.name || '')) continue;
      wrapArg0(value.prototype, 'setText', 'taima-name:' + key);
    }
  }

  // Points 1-23, the engine/plugin level lookup points that the MTool loader
  // installs on MV and MZ. Missing plugins and missing methods are skipped so
  // the same set works on a bare project and on a heavily patched one.
  function patchRpgMakerDeep() {
    var windowBase = root.Window_Base && root.Window_Base.prototype;
    if (windowBase) {
      wrapArg0(windowBase, 'prepareWordWrapEscapeCharacters', 'rpg-prepareWordWrap');
      wrapArg0(windowBase, 'sliceText', 'rpg-sliceText');
      wrapArg0(windowBase, 'processAutoColorWords', 'rpg-autoColorWords');
      wrapArg0(windowBase, 'drawTextRuby', 'rpg-drawTextRuby');
      wrapArg0(windowBase, 'drawTextEx', 'rpg-drawTextEx');
      wrapArg0(windowBase, 'convertEscapeCharacters', 'rpg-convertEscape');
      wrapArg0(windowBase, 'obtainEscapeCode', 'rpg-obtainEscapeCode');
      wrapArg0(windowBase, 'createTextState', 'rpg-createTextState');
    }
    var battleLog = root.Window_BattleLog && root.Window_BattleLog.prototype;
    if (battleLog) {
      wrapArg0(battleLog, 'addText', 'rpg-battleLog');
      wrapArg0(battleLog, 'push', 'rpg-battleLogPush');
    }
    var gameMessage = root.Game_Message && root.Game_Message.prototype;
    if (gameMessage) {
      wrapArg0(gameMessage, 'addText', 'rpg-msgAddText');
      wrapArg0(gameMessage, 'add', 'rpg-msgAdd');
      wrapArg0(gameMessage, 'applyTextReplace', 'rpg-applyTextReplace');
    }
    var titleCommand = root.Window_TitleCommand && root.Window_TitleCommand.prototype;
    if (titleCommand) wrapArg0(titleCommand, 'arinaDrawSpaced', 'rpg-arinaDrawSpaced');
    if (root.Window_TitleCommand) wrapArg0(root.Window_TitleCommand, 'arinaDrawSpaced', 'rpg-arinaDrawSpaced');
    var bitmap = root.Bitmap && root.Bitmap.prototype;
    if (bitmap) {
      // Bitmap.drawText is the lowest level text call. Plugins that draw their
      // own menus call contents.drawText() directly and never reach the
      // Window_Base hooks above, so this one is what keeps custom UIs covered.
      wrapArg0(bitmap, 'drawText', 'rpg-bitmapDrawText');
      wrapArg0(bitmap, 'drawTextWithBitmapFont', 'rpg-bitmapFontText');
      wrapArg0(bitmap, 'measureTextWidth', 'rpg-measureTextWidth');
    }
    // Command windows keep the label in this._list[index].name, so the string
    // is produced by commandName() and then handed to drawText.
    var commandWindow = root.Window_Command && root.Window_Command.prototype;
    if (commandWindow) {
      wrapResult(commandWindow, 'commandName', 'rpg-commandName');
    }
    var messageWindow = root.Window_Message && root.Window_Message.prototype;
    if (messageWindow) wrapResult(messageWindow, 'commandName', 'rpg-msgCommandName');
    patchTextManager();
    if (root.PluginUtils) wrapArg0(root.PluginUtils, 'splitText', 'rpg-splitText');
    if (typeof root.insertLineBreaks === 'function') wrapArg0(root, 'insertLineBreaks', 'rpg-insertLineBreaks');
    var iavra = root.IAVRA;
    if (iavra && iavra.I18N) wrapArg0(iavra.I18N, 'localize', 'plugin-iavra');
    var dktools = root.DKTools;
    if (dktools && dktools.Localization) {
      wrapArg0(dktools.Localization, 'text', 'plugin-dktools');
      wrapArg0(dktools.Localization, 'localize', 'plugin-dktools');
      wrapArg0(dktools.Localization, 'convertText', 'plugin-dktools');
    }
    var srd = root.SRD;
    if (srd && srd.TranslationEngine) {
      wrapArg0(srd.TranslationEngine, 'translate', 'plugin-srd');
      wrapArg0(srd.TranslationEngine, 'getTranslation', 'plugin-srd');
    }
    patchPixiUpdateText();
  }

  // MTool also writes translations back into $dataXxx so menus, item names and
  // the database-derived windows change without a per-window hook. Originals
  // are kept so a reloaded map can be re-applied instead of compounding.
  var DATA_FIELDS = ['name', 'nickname', 'description', 'profile', 'message1', 'message2', 'message3', 'message4'];
  var dataSlots = null;

  function collectDataSlots() {
    var tables = ['$dataActors', '$dataClasses', '$dataSkills', '$dataItems', '$dataWeapons', '$dataArmors', '$dataEnemies', '$dataStates'];
    var slots = [];
    for (var t = 0; t < tables.length; t++) {
      var table = root[tables[t]];
      if (!table || typeof table !== 'object') continue;
      for (var i = 0; i < table.length; i++) {
        var record = table[i];
        if (!record || typeof record !== 'object') continue;
        for (var f = 0; f < DATA_FIELDS.length; f++) {
          var field = DATA_FIELDS[f];
          if (typeof record[field] !== 'string' || !record[field]) continue;
          slots.push({holder: record, field: field, original: record[field], site: 'writeback:' + tables[t]});
        }
      }
    }
    var system = root.$dataSystem;
    if (system && typeof system === 'object') {
      if (typeof system.gameTitle === 'string' && system.gameTitle) {
        slots.push({holder: system, field: 'gameTitle', original: system.gameTitle, site: 'writeback:$dataSystem'});
      }
      var groups = ['terms', 'words'];
      for (var g = 0; g < groups.length; g++) {
        var group = system[groups[g]];
        if (!group || typeof group !== 'object') continue;
        for (var key in group) {
          if (!hasOwn(group, key)) continue;
          var value = group[key];
          if (typeof value === 'string' && value) {
            slots.push({holder: group, field: key, original: value, site: 'writeback:' + groups[g] + '.' + key});
          } else if (Array.isArray(value)) {
            for (var j = 0; j < value.length; j++) {
              if (typeof value[j] !== 'string' || !value[j]) continue;
              slots.push({holder: value, field: String(j), original: value[j], site: 'writeback:' + groups[g] + '.' + key});
            }
          }
        }
      }
    }
    return slots;
  }

  function applyDataSlots() {
    if (!dataSlots) return 0;
    var changed = 0;
    for (var i = 0; i < dataSlots.length; i++) {
      var slot = dataSlots[i];
      var next = tr(slot.original, slot.site);
      if (slot.holder[slot.field] !== next) {
        slot.holder[slot.field] = next;
        changed++;
      }
    }
    if (changed) stats.writeback = (stats.writeback || 0) + changed;
    return changed;
  }

  function installDataWriteback() {
    if (root.__MTOOL_WRITEBACK__ === false) return 0;
    // $dataSystem / $dataActors are still undefined when the bootstrap runs at
    // document start, so an empty collection has to be retried. Caching [] here
    // would silently disable writeback for the whole session.
    if (!dataSlots || dataSlots.length === 0) {
      dataSlots = collectDataSlots();
      if (!dataSlots.length) return 0;
    }
    return applyDataSlots();
  }

  // A late install, or a writeback that changed the text source, leaves the
  // windows that are already on screen showing the old strings. Refreshing them
  // rebuilds command lists and redraws through the patched paths, which is what
  // makes the title/menu switch to the translation immediately.
  //
  // Detection is duck-typed on purpose: obfuscated or bundled MV builds rename
  // the constructors, and a name check (/^Window_/) then matches nothing at all.
  var refreshBurst = 0;
  var lastRefreshScene = null;

  function refreshSceneWindows() {
    var scene = root.SceneManager && root.SceneManager._scene;
    if (!scene) return 0;
    var count = 0;
    var visit = function (node, depth) {
      if (!node || depth > 6) return;
      var isWindow = typeof node.refresh === 'function'
        && node.contents
        && typeof node.contents.drawText === 'function';
      if (isWindow) {
        // Never restart a message that is currently being typed out.
        if (node._textState || typeof node.startMessage === 'function' || node._scrollText !== undefined) return;
        try { node.refresh(); count++; } catch (_) {}
        return;
      }
      var kids = node.children;
      if (kids && kids.length) {
        for (var i = 0; i < kids.length; i++) visit(kids[i], depth + 1);
      }
    };
    visit(scene, 0);
    return count;
  }

  function patchGameObjects() {
    var ctorNames = ['Bitmap', 'Window_Base', 'Window_Message', 'Window_BattleLog'];
    var methods = ['drawText', 'drawTextEx', 'drawTextWithIcon', 'addText', 'startMessage'];
    var i;
    var j;
    var ctor;
    for (i = 0; i < ctorNames.length; i++) {
      ctor = root[ctorNames[i]];
      if (!ctor || !ctor.prototype) continue;
      for (j = 0; j < methods.length; j++) wrapMethod(ctor.prototype, methods[j], [0]);
    }
    var pixiText = root.PIXI && root.PIXI.Text;
    var descriptor = pixiText && Object.getOwnPropertyDescriptor(pixiText.prototype, 'text');
    if (descriptor && descriptor.configurable && descriptor.set && !descriptor.set.__mtoolWrapped) {
      var setter = descriptor.set;
      var wrappedSetter = function (value) { return setter.call(this, tr(value)); };
      var wrappedDescriptor = {configurable: descriptor.configurable, enumerable: descriptor.enumerable};
      if (descriptor.get) wrappedDescriptor.get = descriptor.get;
      wrappedDescriptor.set = wrappedSetter;
      try {
        Object.defineProperty(wrappedSetter, '__mtoolWrapped', {value: true});
        Object.defineProperty(pixiText.prototype, 'text', wrappedDescriptor);
        stats.installed++;
      } catch (_) {}
    }
  }

  function patchRpgMakerMessages() {
    var proto = root.Game_Message && root.Game_Message.prototype;
    if (!proto || typeof proto.allText !== 'function' || proto.allText.__mtoolWrapped) return;
    var original = proto.allText;
    var wrapped = function () {
      return translateMessageBlock(original.apply(this, arguments), 'rpg-message');
    };
    try {
      Object.defineProperty(wrapped, '__mtoolWrapped', {value: true});
      Object.defineProperty(wrapped, '__mtoolOriginal', {value: original});
      proto.allText = wrapped;
      patched.push(wrapped);
      stats.installed++;
    } catch (_) {}
  }

  function refreshCurrentRpgMessage() {
    var message = root.$gameMessage;
    var proto = root.Game_Message && root.Game_Message.prototype;
    var wrappedAllText = proto && proto.allText;
    var originalAllText = wrappedAllText && wrappedAllText.__mtoolOriginal;
    var scene = root.SceneManager && root.SceneManager._scene;
    var messageWindow = scene && scene._messageWindow;
    if (!message || !originalAllText || !messageWindow || !messageWindow._textState || typeof messageWindow.startMessage !== 'function') return false;
    if (typeof message.isBusy === 'function' && !message.isBusy()) return false;
    var originalText = originalAllText.call(message);
    if (!messageBlockHasTranslation(originalText)) return false;
    try {
      messageWindow.startMessage();
      return true;
    } catch (_) {
      return false;
    }
  }

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
    if (!tag || !tag.start || tag.start.__mtoolWrapped) return;
    var original = tag.start;
    var wrapped = function (pm) { return original.call(this, translateArgs(pm, 0)); };
    Object.defineProperty(wrapped, '__mtoolWrapped', {value: true});
    tag.start = wrapped;
    stats.installed++;
  }

  function originalTextFor(node) {
    var index = originalNodes.indexOf(node);
    if (index === -1) {
      originalNodes.push(node);
      originalValues.push(node.nodeValue);
      return node.nodeValue;
    }
    return originalValues[index];
  }

  function visitNode(node) {
    if (!node) return;
    if (node.nodeType === 3) {
      var source = originalTextFor(node);
      var translated = tr(source, 'dom-text');
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
      key = '__mtool_original_' + attr;
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
    patchGameObjects();
    patchRpgMakerDeep();
    patchRpgMakerMessages();
    patchTyrano();
    patchDOM();
    var hooksBefore = stats.installed;
    var writebackChanged = installDataWriteback();
    nameSpriteTick++;
    // Plugins register their sprite classes during boot, so scan eagerly for
    // the first few seconds and then fall back to a slow periodic sweep.
    if (nameSpriteTick <= 8 || nameSpriteTick % 8 === 0) patchNameSprites();
    // Text that is already on screen stays stale until its window redraws.
    // A scene change also needs one pass, because the new scene may have been
    // built after the map was applied. The burst keeps retrying for a few ticks
    // so a slowly-built scene (splash -> title) is covered as well.
    var scene = root.SceneManager && root.SceneManager._scene;
    var sceneChanged = !!scene && scene !== lastRefreshScene;
    if (sceneChanged) {
      lastRefreshScene = scene;
      if (refreshBurst < 2) refreshBurst = 2;
    }
    if (writebackChanged || stats.installed !== hooksBefore || sceneChanged || refreshBurst > 0) {
      stats.refreshed = refreshSceneWindows();
      if (refreshBurst > 0) refreshBurst--;
    }
  }

  function describeRuntime() {
    var engine = '';
    try { engine = (root.Utils && root.Utils.RPGMAKER_NAME) || ''; } catch (_) {}
    if (!engine) engine = root.Scene_Boot ? 'RPG Maker (unknown version)' : 'not an RPG Maker page';
    var scene = root.SceneManager && root.SceneManager._scene;
    return {
      engine: engine,
      scene: (scene && scene.constructor && scene.constructor.name) || '',
      dataLoaded: !!root.$dataSystem,
      messageLoaded: !!root.Game_Message,
      title: (root.document && root.document.title) || ''
    };
  }

  root.__MTOOL_SET_TRANSLATIONS__ = function (next) {
    var source;
    var target;
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
    // Several passes after a map change: the title screen may still be built
    // when the map lands, so one refresh is not always enough.
    if (refreshBurst < 8) refreshBurst = 8;
    install();
    patchDOM();
    var writebackApplied = stats.writeback || 0;
    var refreshed = refreshCurrentRpgMessage();
    var messageProto = root.Game_Message && root.Game_Message.prototype;
    var rpgMessageHook = !!(messageProto && messageProto.allText && messageProto.allText.__mtoolWrapped);
    return {pairs: Object.keys(map).length, hooks: stats.installed, replacements: stats.replacements, rpgMessageHook: rpgMessageHook, currentMessageRefreshed: refreshed, hookSites: HOOK_SITES.length, writebackSlots: dataSlots ? dataSlots.length : 0, writebackApplied: writebackApplied, refreshedWindows: stats.refreshed || 0, runtime: describeRuntime()};
  };
  root.__MTOOL_RUNTIME_TRANSLATOR__ = true;
  root.__MTOOL_STATUS__ = function () {
    return {
      replacements: stats.replacements,
      hooks: stats.installed,
      pairs: Object.keys(map).length,
      writeback: stats.writeback || 0,
      writebackSlots: dataSlots ? dataSlots.length : 0,
      refreshed: stats.refreshed || 0,
      unmatched: stats.misses || 0,
      samples: missSamples.slice(0, 6),
      runtime: describeRuntime()
    };
  };
  install();
  root.setInterval(install, 500);
})();
